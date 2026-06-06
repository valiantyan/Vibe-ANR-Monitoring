package com.valiantyan.anrmonitor.reporter.encoder

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonEscaperTest {
    @Test
    fun escapeEncodesControlCharactersForJsonValidity(): Unit {
        val escaped: String = JsonEscaper.escape(value = "a\u0001b\n\"")

        assertEquals("a\\u0001b\\n\\\"", escaped)
    }
}
