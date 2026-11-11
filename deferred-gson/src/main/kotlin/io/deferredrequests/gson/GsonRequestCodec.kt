package io.deferredrequests.gson

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import io.deferredrequests.RequestCodec

/**
 * Кодек на Gson.
 *
 * Gson по умолчанию пропускает `@Transient`-поля — поэтому служебные данные очереди
 * (идентификаторы, родитель, попытки) хранятся вне модели, в [io.deferredrequests.StoredRequest].
 * Если модель прячет от сервера поля, которые нужны после перезапуска, передайте
 * сюда отдельный экземпляр Gson для хранилища.
 */
public class GsonRequestCodec(
    private val gson: Gson = GsonBuilder().serializeNulls().create(),
) : RequestCodec {
    override fun encode(request: Any): String = gson.toJson(request)

    override fun <T : Any> decode(payload: String, type: Class<T>): T =
        gson.fromJson(payload, type) ?: throw IllegalArgumentException("Пустая запись для ${type.name}")
}
