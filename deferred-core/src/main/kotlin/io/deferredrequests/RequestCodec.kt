package io.deferredrequests

/**
 * Превращает модель запроса в строку для хранилища и обратно.
 *
 * Тип модели хранится отдельно (ключ из [ExecutorRegistry]), поэтому кодеку достаточно
 * уметь работать с конкретным классом.
 */
public interface RequestCodec {
    public fun encode(request: Any): String
    public fun <T : Any> decode(payload: String, type: Class<T>): T
}
