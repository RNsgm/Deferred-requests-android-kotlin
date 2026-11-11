package io.deferredrequests

/**
 * Ответ сервера, который вернул исполнитель.
 *
 * @param code Код ответа (для HTTP — статус).
 * @param body Тело ответа. При успехе сохраняется и передаётся дочерним запросам.
 * @param errorBody Текст ошибки для показа пользователю.
 * @param outcome Явный исход. Если задан, классификатор кодов не вызывается — так
 * исполнитель может сам сказать «отклонить» или «повторить», не подбирая код.
 */
public data class Response(
    val code: Int,
    val body: String = "",
    val errorBody: String? = null,
    val outcome: Outcome? = null,
) {
    public companion object {
        /** Успешный ответ. */
        public fun success(body: String = "", code: Int = 200): Response =
            Response(code, body, outcome = Outcome.Success)

        /** Запрос неверен и не будет выполнен никогда. */
        public fun reject(reason: String, code: Int = 0): Response =
            Response(code, "", reason, Outcome.Rejected(reason))

        /** Временная ошибка сервера: повторить позже. */
        public fun retry(code: Int = 0, errorBody: String? = null): Response =
            Response(code, "", errorBody, Outcome.Retry(networkUnavailable = false))
    }
}
