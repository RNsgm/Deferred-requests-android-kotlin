package io.deferredrequests

/**
 * Необязательное описание запроса для экрана очереди.
 *
 * Модель запроса может быть любым классом; если она реализует этот интерфейс,
 * заголовок и описание попадут в [PendingRequest] и в логи.
 */
public interface DescribedRequest {
    public val title: String
    public val description: String get() = ""
}

internal fun titleOf(request: Any): String =
    (request as? DescribedRequest)?.title ?: request::class.java.simpleName

internal fun descriptionOf(request: Any): String =
    (request as? DescribedRequest)?.description ?: ""
