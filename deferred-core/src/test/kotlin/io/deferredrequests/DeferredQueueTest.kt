package io.deferredrequests

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class DeferredQueueTest {

    // region Отправка и очередь

    @Test
    fun `пустая очередь отправляет запрос сразу и запоминает ответ`() = runTest {
        val notes = ScriptedExecutor<Note>(ok("A"))
        val q = queue { register(notes) }

        val status = q.submit(Note("1"))

        assertTrue(status.isSuccess)
        assertEquals("A", q.answerOf(status.requestId))
        assertTrue(q.pending.value.isEmpty())
    }

    @Test
    fun `без сети запрос сохраняется и уходит, когда сеть появится`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val store = InMemoryDeferredStore()
        val q = queue(store) { register(notes) }

        val status = q.submit(Note("1"))

        assertEquals(Outcome.Retry(networkUnavailable = true), status.outcome)
        assertTrue(status.outcome.isPending)
        assertEquals(listOf("note:1"), q.pending.value.map { it.title })
        assertEquals(1, store.loadRequests().size)

        notes.then(ok)
        assertTrue(q.retryNow())
        assertTrue(store.loadRequests().isEmpty())
    }

    @Test
    fun `сразу после обрыва новые запросы не ждут таймаут`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue { register(notes) }

        q.submit(Note("1"))
        q.submit(Note("2"))

        assertEquals("второй запрос не должен был отправляться", 1, notes.calls.size)
        assertEquals(2, q.pending.value.size)
    }

    @Test
    fun `новый запрос не обгоняет старые`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue(config = DeferredConfig(retryInterval = 1_000)) { register(notes) }
        q.submit(Note("1"))
        advanceTimeBy(5_000)

        notes.then(ok)
        q.submit(Note("2"))

        assertEquals(listOf("1", "1", "2"), notes.calls.map { it.first.text })
    }

    @Test
    fun `обрыв сети прерывает проход`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue { register(notes) }
        q.submit(Note("1"))
        advanceTimeBy(60_000)
        q.submit(Note("2"))
        q.submit(Note("3"))
        val before = notes.calls.size

        assertFalse(q.retryNow())

        assertEquals("после первого отказа сети остальные не трогаются", before + 1, notes.calls.size)
    }

    // endregion

    // region Родители и потомки

    @Test
    fun `потомок получает ответ родителя`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val photos = ScriptedExecutor<Photo>(ok)
        val q = queue { register(notes); register(photos) }

        val parent = q.submit(Note("meter"))
        val child = q.submit(Photo("a.jpg"), parentId = parent.requestId)
        assertTrue(child.outcome.isPending)

        notes.then(ok("{\"meterId\":7}"))
        assertTrue(q.retryNow())

        val ctx = photos.calls.single().second
        assertEquals(parent.requestId, ctx.parentId)
        assertEquals("{\"meterId\":7}", ctx.parentAnswer)
    }

    @Test
    fun `потомок уже выполненного родителя уходит сразу`() = runTest {
        val q = queue {
            register(ScriptedExecutor<Note>(ok("P")))
            register(ScriptedExecutor<Photo>(ok))
        }
        val parent = q.submit(Note("1"))

        assertTrue(q.submit(Photo("a"), parentId = parent.requestId).isSuccess)
    }

    @Test
    fun `потомок ждёт родителя в выдержке, а остальные идут мимо`() = runTest {
        val notes = ScriptedExecutor<Note>(serverDown)
        val photos = ScriptedExecutor<Photo>(ok)
        val q = queue { register(notes); register(photos) }

        val parent = q.submit(Note("1"))
        val child = q.submit(Photo("child"), parentId = parent.requestId)
        val other = q.submit(Photo("other"))

        assertEquals(Outcome.WaitingForParent, child.outcome)
        assertTrue(other.isSuccess)
        assertEquals(listOf("other"), photos.calls.map { it.first.path })
    }

    @Test
    fun `отказ родителя отклоняет всё поддерево`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val photos = ScriptedExecutor<Photo>(ok)
        val store = InMemoryDeferredStore()
        val q = queue(store) { register(notes); register(photos) }
        val statuses = mutableListOf<RequestStatus>()
        val collector = backgroundScope.launch { q.statuses.collect { statuses += it } }
        runCurrent()

        val parent = q.submit(Note("1"))
        val child = q.submit(Photo("c"), parentId = parent.requestId)
        val grandChild = q.submit(Photo("g"), parentId = child.requestId)
        advanceTimeBy(60_000)

        notes.then(Response(422, "", "bad barcode"))
        assertTrue(q.retryNow())
        runCurrent()

        assertTrue(photos.calls.isEmpty())
        assertTrue(store.loadRequests().isEmpty())
        val rejected = statuses.filter { it.outcome is Outcome.Rejected }.map { it.requestId }
        assertEquals(listOf(parent.requestId, child.requestId, grandChild.requestId).toSet(), rejected.toSet())
        assertEquals("bad barcode", statuses.first { it.requestId == parent.requestId && !it.outcome.isPending }.body)
        collector.cancel()
    }

    @Test
    fun `поздний потомок отклонённого родителя отбрасывается сразу`() = runTest {
        val photos = ScriptedExecutor<Photo>(ok)
        val q = queue {
            register(ScriptedExecutor<Note>(Response(400)))
            register(photos)
        }
        val parent = q.submit(Note("1"))

        val child = q.submit(Photo("c"), parentId = parent.requestId)

        assertTrue(child.outcome is Outcome.Rejected)
        assertTrue(photos.calls.isEmpty())
    }

    @Test
    fun `потомок неизвестного родителя отклоняется, а не висит вечно`() = runTest {
        val q = queue { register(ScriptedExecutor<Photo>(ok)) }

        val child = q.submit(Photo("c"), parentId = UUID.randomUUID())

        assertTrue(child.outcome is Outcome.Rejected)
        assertTrue(q.pending.value.isEmpty())
    }

    @Test
    fun `отмена убирает запрос вместе с потомками`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue { register(notes); register(ScriptedExecutor<Photo>(ok)) }
        val parent = q.submit(Note("1"))
        q.submit(Photo("c"), parentId = parent.requestId)

        assertTrue(q.cancel(parent.requestId))

        assertTrue(q.pending.value.isEmpty())
        assertFalse(q.cancel(parent.requestId))
    }

    // endregion

    // region Повторы

    @Test
    fun `временная ошибка сервера повторяется с выдержкой`() = runTest {
        val notes = ScriptedExecutor<Note>(serverDown)
        val config = DeferredConfig(retryInterval = 1_000, maxBackoff = 60_000)
        val q = queue(config = config) { register(notes) }

        q.submit(Note("1"))
        q.retryNow()
        assertEquals("в выдержке запрос не отправляется", 1, notes.calls.size)

        advanceTimeBy(2_001) // 1000 * 2^1
        q.retryNow()
        assertEquals(2, notes.calls.size)
        assertEquals(1, notes.calls[1].second.attempt)
        assertEquals(2, q.pending.value.single().attempts)
    }

    @Test
    fun `исчерпав попытки, запрос отклоняется`() = runTest {
        val notes = ScriptedExecutor<Note>(serverDown)
        val q = queue(config = DeferredConfig(retryInterval = 1, maxBackoff = 0, maxAttempts = 3)) {
            register(notes)
        }
        q.submit(Note("1"))
        repeat(5) { advanceTimeBy(10); q.retryNow() }

        assertEquals(4, notes.calls.size)
        assertTrue(q.pending.value.isEmpty())
    }

    @Test
    fun `обрыв сети не тратит попытки, но запрос стареет`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue(config = DeferredConfig(retryInterval = 1_000, requestTtl = 10_000)) {
            register(notes)
        }
        q.submit(Note("1"))
        advanceTimeBy(5_000); q.retryNow()
        assertEquals(0, q.pending.value.single().attempts)

        advanceTimeBy(6_000); q.retryNow()
        assertTrue(q.pending.value.isEmpty())
    }

    @Test
    fun `исключения исполнителя классифицируются`() = runTest {
        val photos = ScriptedExecutor<Photo>(FileNotFoundException("a.jpg"))
        val notes = ScriptedExecutor<Note>(IllegalStateException("bug"))
        val q = queue { register(photos); register(notes) }

        assertTrue(q.submit(Photo("a.jpg")).outcome is Outcome.Rejected)
        assertTrue(q.submit(Note("1")).outcome is Outcome.Rejected)
    }

    @Test
    fun `таймаут внутри исполнителя считается обрывом сети`() = runTest {
        val notes = DeferredExecutor<Note> { _, _ -> withTimeout(10) { CompletableDeferred<Response>().await() } }
        val q = queue { register(notes) }

        assertEquals(Outcome.Retry(networkUnavailable = true), q.submit(Note("1")).outcome)
    }

    @Test
    fun `исполнитель может сам задать исход`() = runTest {
        val q = queue { register(ScriptedExecutor<Note>(Response.reject("дубликат", code = 200))) }

        val status = q.submit(Note("1"))

        assertEquals(Outcome.Rejected("дубликат"), status.outcome)
    }

    @Test
    fun `незарегистрированный тип отклоняется`() = runTest {
        val q = queue { }

        assertTrue(q.submit(Note("1")).outcome is Outcome.Rejected)
    }

    // endregion

    // region Сессия

    @Test
    fun `после 401 очередь ждёт новый токен`() = runTest {
        val notes = ScriptedExecutor<Note>(Response(401))
        val session = MutableSession(token = "old")
        val q = queue(session = session) { register(notes) }

        assertEquals(Outcome.Unauthorized, q.submit(Note("1")).outcome)
        assertTrue(q.isPausedForAuthorization)

        notes.then(ok)
        assertEquals(Outcome.Unauthorized, q.submit(Note("2")).outcome)
        assertFalse(q.retryNow())
        session.token = ""
        assertFalse("пустой токен — ещё не вошли", q.retryNow())
        assertEquals(1, notes.calls.size)

        session.token = "new"
        assertTrue(q.retryNow())
        assertEquals(listOf("1", "1", "2"), notes.calls.map { it.first.text })
    }

    @Test
    fun `без провайдера сессии пауза снимается вручную`() = runTest {
        val notes = ScriptedExecutor<Note>(Response(401))
        val q = queue { register(notes) }
        q.submit(Note("1"))
        notes.then(ok)

        assertFalse(q.retryNow())
        assertTrue(q.resume())
    }

    @Test
    fun `запросы другого пользователя при восстановлении удаляются`() = runTest {
        val store = InMemoryDeferredStore()
        val alice = MutableSession(owner = "alice")
        queue(store, session = alice) { register(ScriptedExecutor<Note>(offline)) }.submit(Note("a"))

        val notes = ScriptedExecutor<Note>(ok)
        val bob = queue(store, session = MutableSession(owner = "bob")) { register(notes) }

        assertTrue(bob.retryNow())
        assertTrue(notes.calls.isEmpty())
    }

    // endregion

    // region Хранилище и жизненный цикл

    @Test
    fun `очередь переживает перезапуск`() = runTest {
        val store = InMemoryDeferredStore()
        val first = queue(store) {
            register(ScriptedExecutor<Note>(offline))
            register(ScriptedExecutor<Photo>(offline))
        }
        val parent = first.submit(Note("1"))
        first.submit(Photo("p"), parentId = parent.requestId)

        val notes = ScriptedExecutor<Note>(ok("P"))
        val photos = ScriptedExecutor<Photo>(ok)
        val second = queue(store) { register(notes); register(photos) }

        assertTrue(second.retryNow())
        assertEquals("P", photos.calls.single().second.parentAnswer)
        assertEquals(Photo("p"), photos.calls.single().first)
    }

    @Test
    fun `нечитаемый запрос удаляется, его потомки отклоняются`() = runTest {
        val store = InMemoryDeferredStore()
        val parentId = UUID.randomUUID()
        store.saveRequest(StoredRequest(parentId, "gone.Type", "x", null, 1, 0, 1, null))
        store.saveRequest(StoredRequest(UUID.randomUUID(), Photo::class.java.name, "p", parentId, 2, 0, 1, null))
        val photos = ScriptedExecutor<Photo>(ok)
        val q = queue(store) { register(photos) }

        assertTrue(q.retryNow())
        assertTrue(photos.calls.isEmpty())
        assertTrue(store.loadRequests().isEmpty())
    }

    @Test
    fun `цикл сам отправляет очередь`() = runTest {
        val notes = ScriptedExecutor<Note>(offline)
        val q = queue(config = DeferredConfig(retryInterval = 1_000)) { register(notes) }
        q.submit(Note("1"))
        notes.then(ok)

        q.start()
        advanceTimeBy(1_500)

        assertTrue(q.pending.value.isEmpty())
        q.stop()
    }

    @Test
    fun `остановка посреди отправки не считается ошибкой запроса`() = runTest {
        val store = InMemoryDeferredStore()
        queue(store) { register(ScriptedExecutor<Note>(offline)) }.submit(Note("1"))
        // Исполнитель, который висит до отмены.
        val hanging = DeferredExecutor<Note> { _, _ -> CompletableDeferred<Response>().await() }
        val q = queue(store, config = DeferredConfig(retryInterval = 1_000)) { register(hanging) }

        q.start()
        advanceTimeBy(100)
        q.stop()
        runCurrent()

        val stored = store.loadRequests().single()
        assertEquals(0, stored.attempts)
        assertEquals(1, q.pending.value.size)
        assertNull(q.answerOf(stored.id))
    }

    @Test
    fun `ответы чистятся, но не те, которых ждут`() = runTest {
        val store = InMemoryDeferredStore()
        val config = DeferredConfig(retryInterval = 1_000, maxAnswers = 1, pruneInterval = 1)
        val photos = ScriptedExecutor<Photo>(serverDown)
        val q = queue(store, config) {
            register(ScriptedExecutor<Note>(ok("A")))
            register(photos)
        }
        val awaited = q.submit(Note("1"))
        q.submit(Photo("p"), parentId = awaited.requestId)
        advanceTimeBy(5_000)
        val fresh = q.submit(Note("2"))
        advanceTimeBy(5_000)
        val newest = q.submit(Note("3"))
        advanceTimeBy(10)

        q.retryNow()

        assertEquals("A", q.answerOf(awaited.requestId))
        assertNull(q.answerOf(fresh.requestId))
        assertEquals("A", q.answerOf(newest.requestId))
    }

    // endregion
}
