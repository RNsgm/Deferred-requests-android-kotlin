package io.deferredrequests.gson

import org.junit.Assert.assertEquals
import org.junit.Test

class GsonRequestCodecTest {

    data class MeterBody(val barcode: String, val parentId: Long? = null)

    @Test
    fun `модель переживает запись и чтение`() {
        val codec = GsonRequestCodec()
        val body = MeterBody("123", null)

        assertEquals(body, codec.decode(codec.encode(body), MeterBody::class.java))
    }
}
