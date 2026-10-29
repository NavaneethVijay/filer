package com.navaneeth.filer.data.inference

import com.navaneeth.filer.data.inference.EngineTag
import com.navaneeth.filer.data.inference.RemoteEmbedder
import com.navaneeth.filer.data.inference.RemoteHealth
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class RemoteEmbedderTest {

    private fun encode(values: FloatArray): String {
        val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        values.forEach { buffer.putFloat(it) }
        return Base64.getEncoder().encodeToString(buffer.array())
    }

    @Test
    fun decodesLittleEndianFloat32() {
        val values = floatArrayOf(0f, 1f, -1f, 0.5f, 1e-7f, -123.456f, Float.MIN_VALUE)
        assertArrayEquals(values, RemoteEmbedder.decodeVector(encode(values)), 0f)
    }

    @Test
    fun decodesKnownBytes() {
        // 1.0f little-endian is 00 00 80 3F; Python's struct.pack("<f", 1.0) gives the same.
        val base64 = Base64.getEncoder().encodeToString(byteArrayOf(0, 0, 0x80.toByte(), 0x3F))
        assertArrayEquals(floatArrayOf(1f), RemoteEmbedder.decodeVector(base64), 0f)
    }

    @Test
    fun decodesFullVector() {
        val values = FloatArray(768) { it / 768f }
        assertEquals(768, RemoteEmbedder.decodeVector(encode(values)).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTruncatedVector() {
        RemoteEmbedder.decodeVector(Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)))
    }

    private val expected = 484_622_336L
    private val good = RemoteHealth(model = "embeddinggemma-2-740m.litertlm", modelBytes = expected, engine = EngineTag.MAC_GPU, dim = 768)

    @Test
    fun acceptsSameModelFile() {
        assertTrue(RemoteEmbedder.isAcceptable(good, expected))
        assertTrue(RemoteEmbedder.isAcceptable(good.copy(engine = EngineTag.MAC_CPU), expected))
    }

    @Test
    fun rejectsDifferentModelOrShape() {
        assertFalse(RemoteEmbedder.isAcceptable(good.copy(modelBytes = expected - 1), expected))
        assertFalse(RemoteEmbedder.isAcceptable(good.copy(dim = 512), expected))
        assertFalse(RemoteEmbedder.isAcceptable(good.copy(engine = "phone-gpu"), expected))
        assertFalse(RemoteEmbedder.isAcceptable(RemoteHealth(), expected))
    }
}
