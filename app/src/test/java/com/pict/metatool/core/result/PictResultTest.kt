package com.pict.metatool.core.result

import com.pict.metatool.core.error.PictError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PictResultTest {

    @Test
    fun `success carries value and reports success`() {
        val result: PictResult<Int> = successOf(42)

        assertTrue(result.isSuccess)
        assertEquals(42, result.getOrNull())
        assertNull(result.failureOrNull())
    }

    @Test
    fun `failure carries code and reports failure`() {
        val result: PictResult<Int> = failureOf(PictError.STORAGE_READONLY, detail = "uri=x")

        assertEquals(false, result.isSuccess)
        val failure = result.failureOrNull()
        assertEquals("E-STORAGE-READONLY", failure?.code)
        assertSame(PictError.STORAGE_READONLY, failure?.error)
        assertNull(result.getOrNull())
        assertEquals("uri=x", result.failureOrNull()?.detail)
    }

    @Test
    fun `map transforms success and passes failure through unchanged`() {
        val mapped = successOf(2).map { it * 21 }
        assertEquals(42, mapped.getOrNull())

        val failed: PictResult<Int> = failureOf(PictError.DECODE)
        val mappedFailure = failed.map { it * 21 }
        assertEquals("E-DECODE", mappedFailure.failureOrNull()?.code)
    }

    @Test
    fun `onSuccess and onFailure fire on the right branch only`() {
        var successCalls = 0
        var failureCalls = 0

        successOf("ok")
            .onSuccess { successCalls++ }
            .onFailure { failureCalls++ }

        failureOf<String>(PictError.IO_WRITE)
            .onSuccess { successCalls++ }
            .onFailure { failureCalls++ }

        assertEquals(1, successCalls)
        assertEquals(1, failureCalls)
    }

    @Test
    fun `getOrElse falls back with the failure`() {
        val value = failureOf<Int>(PictError.OOM).getOrElse { -1 }
        assertEquals(-1, value)
    }

    @Test
    fun `unknown code maps to UNKNOWN`() {
        assertEquals(PictError.UNKNOWN, PictError.fromCode("E-NOT-A-REAL-CODE"))
        assertEquals(PictError.META_VERIFY, PictError.fromCode("E-META-VERIFY"))
    }

    @Test(expected = PictException::class)
    fun `getOrThrow throws PictException on failure`() {
        failureOf<Int>(PictError.META_WRITE).getOrThrow()
    }

    @Test
    fun `every error code is unique and prefixed`() {
        val codes = PictError.entries.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
        assertTrue(codes.all { it.startsWith("E-") })
    }
}
