package io.deferredrequests

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Очередь отложенных запросов к серверу.
 *
 * Запрос сначала пытаются отправить сразу; если не вышло — он сохраняется в
 * [DeferredStore] и уходит позже, при очередном проходе. Правила:
 *
 * - **Порядок.** Очередь обходится строго в порядке добавления. Пока очередь не пуста,
 *   новый запрос не обгоняет старые: иначе дочерний запрос ушёл бы раньше родителя.
 * - **Родители.** Запрос с `parentId` выполняется только после успеха родителя и
 *   получает его ответ ([ExecutionContext.parentAnswer]). Ждущий родителя запрос
 *   пропускается и не задерживает остальных.
 * - **Отказ — каскадом.** Отклонённый запрос удаляется вместе со всем поддеревом
 *   потомков; отказ запоминается, так что потомки, созданные позже, отбрасываются сразу.
 * - **Повторы.** Нет сети — проход прерывается, запрос ждёт. Временная ошибка сервера —
 *   повтор с экспоненциальной выдержкой, остальные запросы идут мимо. Безнадёжные
 *   запросы отсекаются по [DeferredConfig.maxAttempts] и [DeferredConfig.requestTtl].
 * - **Сессия.** На 401 очередь встаёт на паузу до смены токена ([SessionProvider]) или
 *   [resume]. Запросы другого владельца при восстановлении удаляются.
 *
 * Очередь — обычный объект без глобального состояния: создайте один экземпляр на процесс
 * (например, через DI), зарегистрируйте исполнители и вызовите [start].
 */
public class DeferredQueue(
    private val store: DeferredStore,
    private val codec: RequestCodec,
    public val registry: ExecutorRegistry = ExecutorRegistry(),
    private val config: DeferredConfig = DeferredConfig(),
    private val session: SessionProvider = SessionProvider.None,
    private val responseClassifier: ResponseClassifier = HttpResponseClassifier,
    private val exceptionClassifier: ExceptionClassifier = DefaultExceptionClassifier,
    private val logger: DeferredLogger = DeferredLogger.None,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private class Entry(
        val id: UUID,
        val parentId: UUID?,
        val request: Any,
        val typeKey: String,
        val payload: String,
        val owner: String?,
        val createdAt: Long,
        var seq: Long = 0,
        var attempts: Int = 0,
        var lastAttemptAt: Long = 0,
    )

    private val mutex = Mutex()

    // Всё ниже меняется только под [mutex].
    private val entries = LinkedHashMap<UUID, Entry>()
    private var seqCounter = 1L
    private var restored = false
    private var lastNetworkFailureAt: Long? = null
    private var lastPruneAt = 0L
    private var paused = false
    private var pausedToken: String? = null

    /** Зеркало таблицы ответов: [answerOf] должен быть синхронным. */
    private val answers = ConcurrentHashMap<UUID, String>()

    private val _pending = MutableStateFlow<List<PendingRequest>>(emptyList())
    private val _statuses = MutableSharedFlow<RequestStatus>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    private val loopLock = Any()
    private var loopJob: Job? = null
    private var started = false

    /** Запросы, ожидающие отправки, в порядке очереди. */
    public val pending: StateFlow<List<PendingRequest>> = _pending.asStateFlow()

    /** Итог каждой попытки отправки — для уведомлений и журнала. */
    public val statuses: SharedFlow<RequestStatus> = _statuses.asSharedFlow()

    /** Очередь стоит из-за истёкшей сессии. */
    public val isPausedForAuthorization: Boolean get() = paused

    // region Жизненный цикл

    /** Запускает периодическую отправку очереди. Повторный вызов ничего не делает. */
    public fun start() {
        synchronized(loopLock) {
            started = true
            if (loopJob?.isActive == true) return
            loopJob = scope.launch {
                while (isActive) {
                    try {
                        mutex.withLock {
                            restoreLocked()
                            drainLocked()
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Сбой хранилища не должен навсегда остановить отправку.
                        logger.error("Проход по очереди завершился ошибкой", e)
                    }
                    delay(config.retryInterval)
                }
            }
        }
    }

    /** Останавливает периодическую отправку. Очередь при этом сохраняется. */
    public fun stop() {
        synchronized(loopLock) {
            started = false
            loopJob?.cancel()
            loopJob = null
        }
    }

    /** Останавливает очередь и отменяет [scope]. После этого экземпляр не используется. */
    public fun close() {
        stop()
        scope.cancel()
    }

    // endregion

    // region Публичные операции

    /**
     * Отправляет запрос сейчас или ставит его в очередь.
     *
     * @param request Модель запроса; её тип должен быть зарегистрирован в [registry].
     * @param parentId Запрос, от ответа которого зависит этот.
     * @param id Идентификатор запроса. Задайте заранее, если на него будут ссылаться
     * дочерние запросы, созданные до ответа.
     * @return Итог попытки. Если [Outcome.isPending] — запрос сохранён и уйдёт позже.
     */
    public suspend fun submit(
        request: Any,
        parentId: UUID? = null,
        id: UUID = UUID.randomUUID(),
    ): RequestStatus = mutex.withLock {
        restoreLocked()
        require(id !in entries) { "Запрос $id уже в очереди" }

        val registration = registry.forRequest(request)
            ?: return@withLock reject(
                id, request, null, "не зарегистрирован исполнитель для ${request::class.java.name}"
            )
        val payload = try {
            codec.encode(request)
        } catch (e: Exception) {
            logger.error("Не удалось сохранить запрос $id (${titleOf(request)})", e)
            return@withLock reject(id, request, null, "запрос не сериализуется: ${e.message}")
        }
        val entry = Entry(
            id = id,
            parentId = parentId,
            request = request,
            typeKey = registration.typeKey,
            payload = payload,
            owner = session.owner,
            createdAt = clock(),
        )

        val paused = isPausedLocked()
        // Сеть только что пропала — не ждать таймаут повторно на каждом новом запросе.
        val offline = lastNetworkFailureAt?.let { clock() - it < config.retryInterval } == true
        // Очередь непустая — сначала уходит всё, что встало раньше. Запросы, оставшиеся
        // после полного прохода (в выдержке или ждущие родителя), новый не задерживают.
        val canSendNow = !paused && !offline && (entries.isEmpty() || passLocked() != Pass.INTERRUPTED)

        if (!canSendNow) {
            enqueue(entry)
            return@withLock emit(
                RequestStatus(
                    id,
                    if (paused) Outcome.Unauthorized else Outcome.Retry(networkUnavailable = true),
                    body = "Запрос сохранён в очереди",
                    request = request,
                )
            )
        }
        process(entry)
    }

    /**
     * Немедленно проходит по очереди (например, по кнопке «Отправить» или при появлении сети).
     * @return `true`, если очередь опустела.
     */
    public suspend fun retryNow(): Boolean {
        synchronized(loopLock) {
            if (started && loopJob?.isActive != true) start()
        }
        return mutex.withLock {
            restoreLocked()
            drainLocked()
        }
    }

    /**
     * Снимает паузу после [Outcome.Unauthorized], не дожидаясь смены токена,
     * и перечитывает очередь из хранилища.
     */
    public suspend fun resume(): Boolean = mutex.withLock {
        restoreLocked()
        if (paused) resumeLocked()
        drainLocked()
    }

    /**
     * Убирает запрос из очереди вместе с потомками. Потомки, созданные позже,
     * тоже будут отклонены.
     * @return `false`, если такого запроса в очереди нет.
     */
    public suspend fun cancel(id: UUID): Boolean = mutex.withLock {
        restoreLocked()
        val entry = entries[id] ?: return@withLock false
        reject(id, entry.request, null, "отменён")
        true
    }

    /** Ответ сервера на ранее выполненный запрос. */
    public fun answerOf(id: UUID): String? = answers[id]

    // endregion

    // region Восстановление

    private suspend fun restoreLocked() {
        if (restored) return
        restored = true
        loadStateLocked()
    }

    private suspend fun loadStateLocked() {
        session.owner?.let { store.deleteRequestsOfOtherOwners(it) }

        answers.clear()
        answers.putAll(store.loadAnswers())

        seqCounter = (store.maxSeq() ?: 0L) + 1
        entries.clear()
        for (stored in store.loadRequests()) {
            val registration = registry.forKey(stored.typeKey)
            val request = registration?.let {
                try {
                    codec.decode(stored.payload, it.type)
                } catch (e: Exception) {
                    logger.error("Запрос ${stored.id} не читается", e)
                    null
                }
            }
            if (request == null) {
                val reason = if (registration == null) {
                    "не зарегистрирован исполнитель для ${stored.typeKey}"
                } else {
                    "запрос не читается"
                }
                logger.warn("Запрос ${stored.id} удалён из очереди: $reason")
                store.deleteRequest(stored.id)
                // Потомки этого запроса будут отброшены при первой же попытке.
                store.markRejected(stored.id, reason, null, clock())
                continue
            }
            entries[stored.id] = Entry(
                id = stored.id,
                parentId = stored.parentId,
                request = request,
                typeKey = stored.typeKey,
                payload = stored.payload,
                owner = stored.owner,
                createdAt = if (stored.createdAt > 0) stored.createdAt else clock(),
                seq = if (stored.seq > 0) stored.seq else seqCounter++,
                attempts = stored.attempts,
            )
        }
        publish()
        logger.debug("Восстановлено запросов: ${entries.size}, ответов: ${answers.size}")
    }

    // endregion

    // region Сессия

    private suspend fun isPausedLocked(): Boolean {
        if (!paused) return false
        val current = session.token
        if (current.isNullOrBlank() || current == pausedToken) return true
        resumeLocked()
        return false
    }

    /** Новая сессия — возможно, другой пользователь: перечитываем очередь заново. */
    private suspend fun resumeLocked() {
        paused = false
        pausedToken = null
        loadStateLocked()
    }

    // endregion

    // region Отправка

    /**
     * Один проход по очереди в порядке добавления.
     *
     * Запрос, ждущий родителя или выдержки, пропускается и не мешает остальным. Проход
     * прерывается, только если сеть недоступна или сессия истекла.
     *
     * @return `true`, если очередь опустела.
     */
    private suspend fun drainLocked(): Boolean = passLocked() == Pass.EMPTY

    private enum class Pass { EMPTY, LEFTOVERS, INTERRUPTED }

    private suspend fun passLocked(): Pass {
        if (isPausedLocked()) return Pass.INTERRUPTED

        val batch = entries.values.sortedBy { it.seq }
        for (entry in batch) {
            if (clock() - entry.lastAttemptAt < config.backoffFor(entry.attempts)) continue
            // Мог быть удалён каскадом вместе с отклонённым родителем на этом же проходе.
            if (entry.id !in entries) continue

            when (val outcome = process(entry).outcome) {
                is Outcome.Retry -> if (outcome.networkUnavailable) return Pass.INTERRUPTED
                is Outcome.Unauthorized -> return Pass.INTERRUPTED
                else -> Unit
            }
        }
        pruneLocked()
        return if (entries.isEmpty()) Pass.EMPTY else Pass.LEFTOVERS
    }

    /** Выполняет запрос и приводит очередь в соответствие с результатом. */
    private suspend fun process(entry: Entry): RequestStatus {
        val parentId = entry.parentId
        if (parentId != null && store.isRejected(parentId)) {
            return reject(entry.id, entry.request, null, "родительский запрос $parentId отклонён")
        }

        val registration = registry.forKey(entry.typeKey)
            ?: return reject(
                entry.id, entry.request, null, "не зарегистрирован исполнитель для ${entry.typeKey}"
            )

        val parentAnswer = if (parentId != null) {
            answers[parentId] ?: return waitForParent(entry)
        } else {
            null
        }

        var response: Response? = null
        val outcome = try {
            val context = ExecutionContext(entry.id, parentId, parentAnswer, entry.attempts, answers::get)
            registration.run(entry.request, context).let {
                response = it
                it.outcome ?: responseClassifier.classify(it)
            }
        } catch (e: CancellationException) {
            // Отмена очереди — не ошибка запроса, иначе остановка выглядела бы как сбой.
            currentCoroutineContext().ensureActive()
            // А вот таймаут внутри исполнителя (withTimeout) — обычный обрыв связи.
            logger.debug("Таймаут (${titleOf(entry.request)}): ${e.message}")
            Outcome.Retry(networkUnavailable = true)
        } catch (e: Exception) {
            logger.debug("Исполнитель бросил ${e::class.java.simpleName} (${titleOf(entry.request)}): ${e.message}")
            exceptionClassifier.classify(e)
        }

        return when (outcome) {
            is Outcome.Success -> {
                val body = response?.body.orEmpty()
                answers[entry.id] = body
                store.saveAnswer(entry.id, body, clock())
                removeFromQueue(entry.id)
                emit(RequestStatus(entry.id, Outcome.Success, response?.code, body, entry.request))
            }

            is Outcome.Rejected -> reject(entry.id, entry.request, response, outcome.reason)

            is Outcome.WaitingForParent -> waitForParent(entry)

            is Outcome.Unauthorized -> {
                paused = true
                pausedToken = session.token
                enqueue(entry)
                logger.warn("Сессия истекла, очередь ждёт входа в аккаунт (${titleOf(entry.request)})")
                emit(
                    RequestStatus(
                        entry.id, Outcome.Unauthorized, response?.code,
                        "Требуется повторный вход в аккаунт", entry.request,
                    )
                )
            }

            is Outcome.Retry -> {
                val now = clock()
                if (outcome.networkUnavailable) lastNetworkFailureAt = now
                // Считаются только ответы сервера: обрыв сети — не вина запроса.
                val attempts = if (response != null && !outcome.networkUnavailable) {
                    entry.attempts + 1
                } else {
                    entry.attempts
                }
                when {
                    attempts > config.maxAttempts ->
                        reject(entry.id, entry.request, response, "исчерпаны попытки отправки ($attempts)")

                    now - entry.createdAt > config.requestTtl ->
                        reject(
                            entry.id, entry.request, response,
                            "запрос старше ${config.requestTtl / DeferredConfig.DAY} суток",
                        )

                    else -> {
                        entry.attempts = attempts
                        enqueue(entry)
                        emit(
                            RequestStatus(
                                entry.id, outcome, response?.code,
                                response?.errorBody ?: response?.body?.takeIf { it.isNotEmpty() }
                                    ?: "Запрос сохранён в очереди",
                                entry.request,
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun waitForParent(entry: Entry): RequestStatus {
        val parentId = entry.parentId
        if (parentId == null || parentId !in entries) {
            return reject(
                entry.id, entry.request, null,
                "ответ родительского запроса $parentId недоступен, а сам он не в очереди",
            )
        }
        enqueue(entry)
        return emit(
            RequestStatus(
                entry.id, Outcome.WaitingForParent,
                body = "Ожидает выполнения родительского запроса", request = entry.request,
            )
        )
    }

    // endregion

    // region Очередь

    private suspend fun enqueue(entry: Entry) {
        if (entry.seq == 0L) entry.seq = seqCounter++
        entry.lastAttemptAt = clock()
        store.saveRequest(
            StoredRequest(
                id = entry.id,
                typeKey = entry.typeKey,
                payload = entry.payload,
                parentId = entry.parentId,
                seq = entry.seq,
                attempts = entry.attempts,
                createdAt = entry.createdAt,
                owner = entry.owner,
            )
        )
        entries[entry.id] = entry
        publish()
    }

    private suspend fun removeFromQueue(id: UUID) {
        store.deleteRequest(id)
        if (entries.remove(id) != null) publish()
    }

    /**
     * Отклоняет запрос навсегда вместе со всем поддеревом потомков и запоминает отказ.
     *
     * Запись об отказе нужна не только для истории: дочерние запросы часто создаются
     * уже после того, как родитель ответил ошибкой, и без неё они осели бы в очереди
     * в ожидании данных, которых никогда не будет.
     */
    private suspend fun reject(
        id: UUID,
        request: Any?,
        response: Response?,
        reason: String,
    ): RequestStatus {
        logger.warn("Запрос $id (${request?.let(::titleOf)}) отклонён: $reason")
        rejectTree(id, reason, response?.code, mutableSetOf())
        return emit(
            RequestStatus(
                id, Outcome.Rejected(reason), response?.code,
                response?.errorBody ?: response?.body?.takeIf { it.isNotEmpty() } ?: reason,
                request,
            )
        )
    }

    private suspend fun rejectTree(id: UUID, reason: String, code: Int?, visited: MutableSet<UUID>) {
        if (!visited.add(id)) return

        store.markRejected(id, reason, code, clock())

        val children = LinkedHashSet<UUID>()
        entries.values.filter { it.parentId == id }.mapTo(children) { it.id }
        children.addAll(store.childrenOf(id))

        removeFromQueue(id)
        answers.remove(id)
        store.deleteAnswer(id)

        for (child in children) {
            val childReason = "родительский запрос $id отклонён: $reason"
            val childRequest = entries[child]?.request
            rejectTree(child, childReason, null, visited)
            if (childRequest != null) {
                logger.warn("Запрос $child (${titleOf(childRequest)}) отклонён вслед за родителем $id")
                emit(RequestStatus(child, Outcome.Rejected(childReason), null, childReason, childRequest))
            }
        }
    }

    private suspend fun pruneLocked() {
        val now = clock()
        if (now - lastPruneAt < config.pruneInterval) return
        lastPruneAt = now
        val alive = store.pruneAnswers(config.maxAnswers)
        store.pruneRejected(now - config.rejectedTtl)
        answers.keys.retainAll(alive)
    }

    private fun publish() {
        _pending.value = entries.values.sortedBy { it.seq }.map {
            PendingRequest(
                id = it.id,
                parentId = it.parentId,
                request = it.request,
                title = titleOf(it.request),
                description = descriptionOf(it.request),
                createdAt = it.createdAt,
                attempts = it.attempts,
            )
        }
    }

    private fun emit(status: RequestStatus): RequestStatus {
        _statuses.tryEmit(status)
        return status
    }

    // endregion
}
