package io.deferredrequests

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Таблица решений «что делать с ответом сервера». Ошибка здесь либо теряет данные,
 * либо оставляет в очереди запрос, который никогда не выполнится.
 */
class HttpResponseClassifierTest {

    private fun classify(code: Int) = HttpResponseClassifier.forCode(code)

    @Test
    fun `успешные коды считаются выполненными`() {
        listOf(200, 201, 204, 299).forEach { assertEquals("код $it", Outcome.Success, classify(it)) }
    }

    @Test
    fun `временные отказы сервера повторяются`() {
        listOf(408, 429, 500, 502, 503, 599).forEach {
            assertEquals("код $it", Outcome.Retry(networkUnavailable = false), classify(it))
        }
    }

    @Test
    fun `отсутствие кода означает, что запрос не дошёл`() {
        assertEquals(Outcome.Retry(networkUnavailable = true), classify(0))
    }

    @Test
    fun `постоянные ошибки клиента отклоняются`() {
        listOf(400, 403, 404, 409, 422).forEach { assertTrue("код $it", classify(it) is Outcome.Rejected) }
    }

    @Test
    fun `истёкшая сессия ставит очередь на паузу`() {
        assertEquals(Outcome.Unauthorized, classify(401))
    }
}
