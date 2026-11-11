package io.deferredrequests

/**
 * Настройки очереди. Все интервалы — в миллисекундах.
 *
 * @param retryInterval Период прохода по очереди. Также окно, в течение которого после
 * обрыва сети новые запросы сразу ставятся в очередь, не дожидаясь таймаута.
 * @param maxBackoff Предел выдержки между повторами одного запроса после временной
 * ошибки сервера. Выдержка растёт как `retryInterval * 2^attempts`.
 * @param maxAttempts Сколько раз повторять запрос, на который сервер отвечает временной
 * ошибкой. Порог высокий намеренно: авария на сервере — не повод терять данные,
 * от вечного повтора защищает [requestTtl].
 * @param requestTtl Через сколько запрос считается безнадёжным, даже если сеть так и
 * не появилась.
 * @param maxAnswers Сколько ответов сервера хранить для дочерних запросов. Ответ,
 * которого ждёт запрос из очереди, не вытесняется.
 * @param rejectedTtl Сколько помнить отклонённые запросы, чтобы сразу отбрасывать их
 * поздних потомков.
 * @param pruneInterval Как часто чистить ответы и отказы.
 */
public data class DeferredConfig(
    val retryInterval: Long = 10 * SECOND,
    val maxBackoff: Long = 10 * MINUTE,
    val maxAttempts: Int = 500,
    val requestTtl: Long = 14 * DAY,
    val maxAnswers: Int = 1000,
    val rejectedTtl: Long = 30 * DAY,
    val pruneInterval: Long = 5 * MINUTE,
) {
    init {
        require(retryInterval > 0) { "retryInterval должен быть положительным" }
        require(maxBackoff >= 0) { "maxBackoff не может быть отрицательным" }
        require(maxAttempts > 0) { "maxAttempts должен быть положительным" }
        require(maxAnswers >= 0) { "maxAnswers не может быть отрицательным" }
    }

    internal fun backoffFor(attempts: Int): Long =
        if (attempts <= 0) 0 else minOf(retryInterval shl minOf(attempts, 6), maxBackoff)

    public companion object {
        public const val SECOND: Long = 1000L
        public const val MINUTE: Long = 60 * SECOND
        public const val DAY: Long = 24 * 60 * MINUTE
    }
}
