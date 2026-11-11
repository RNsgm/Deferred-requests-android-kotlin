package io.deferredrequests

import java.io.FileNotFoundException
import java.io.IOException

/**
 * Чем закончилась попытка выполнить отложенный запрос.
 *
 * Различать причины отказа важнее, чем сам факт отказа: обрыв сети, отсутствующий
 * ответ родителя и запрос, который сервер не примет никогда, требуют разной реакции.
 * Если свести их к «ответа нет», безнадёжный запрос навсегда застрянет в очереди.
 */
public sealed class Outcome {

    /** Сервер принял запрос. */
    public object Success : Outcome() {
        override fun toString(): String = "Success"
    }

    /**
     * Временная неудача, запрос остаётся в очереди.
     *
     * @param networkUnavailable Запрос не дошёл до сервера. Остальную очередь в этот
     * проход трогать бессмысленно — она упрётся в то же самое.
     */
    public data class Retry(val networkUnavailable: Boolean) : Outcome()

    /** Ответ родительского запроса ещё не получен, запрос ждёт своей очереди. */
    public object WaitingForParent : Outcome() {
        override fun toString(): String = "WaitingForParent"
    }

    /** Запрос не будет выполнен никогда: он и все его потомки удаляются из очереди. */
    public data class Rejected(val reason: String) : Outcome()

    /**
     * Сессия истекла. Запрос остаётся в очереди, а очередь встаёт на паузу до смены
     * токена (или явного [DeferredQueue.resume]): повторять с мёртвым токеном
     * бессмысленно, а терять данные нельзя.
     */
    public object Unauthorized : Outcome() {
        override fun toString(): String = "Unauthorized"
    }

    /** Запрос сохранён и будет отправлен позже. */
    public val isPending: Boolean
        get() = this is Retry || this is WaitingForParent || this is Unauthorized
}

/** Переводит ответ сервера в [Outcome]. */
public fun interface ResponseClassifier {
    public fun classify(response: Response): Outcome
}

/**
 * Классификатор по умолчанию для HTTP.
 *
 * Повторяется то, что может пройти позже: таймаут, перегрузка, авария на сервере.
 * Остальные 4xx означают, что запрос неверен сам по себе, — повторять его бессмысленно.
 * Код `0` трактуется как «запрос не дошёл».
 */
public object HttpResponseClassifier : ResponseClassifier {
    override fun classify(response: Response): Outcome = forCode(response.code)

    public fun forCode(code: Int): Outcome = when {
        code in 200..299 -> Outcome.Success
        code == 0 -> Outcome.Retry(networkUnavailable = true)
        code == 401 -> Outcome.Unauthorized
        code == 408 || code == 429 -> Outcome.Retry(networkUnavailable = false)
        code in 500..599 -> Outcome.Retry(networkUnavailable = false)
        else -> Outcome.Rejected("сервер отклонил запрос: $code")
    }
}

/**
 * Переводит исключение исполнителя в [Outcome].
 * `CancellationException` сюда не попадает никогда: отмена корутины — не ошибка запроса.
 */
public fun interface ExceptionClassifier {
    public fun classify(error: Throwable): Outcome
}

/**
 * Классификатор исключений по умолчанию.
 *
 * - [FileNotFoundException] — отправлять нечего (например, фото удалено), повтор не поможет;
 * - прочие [IOException] — сеть недоступна;
 * - всё остальное — ошибка исполнителя, повтор её не исправит.
 */
public object DefaultExceptionClassifier : ExceptionClassifier {
    override fun classify(error: Throwable): Outcome = when (error) {
        is FileNotFoundException -> Outcome.Rejected("файл для отправки не найден: ${error.message}")
        is IOException -> Outcome.Retry(networkUnavailable = true)
        else -> Outcome.Rejected(
            "исполнитель завершился с ошибкой ${error::class.java.simpleName}: ${error.message}"
        )
    }
}
