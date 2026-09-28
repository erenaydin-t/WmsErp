package com.wmserp.app.core.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WedgeDecoderTest {

    @Test
    fun `assembles a burst terminated by enter`() {
        val decoder = WedgeDecoder()
        var t = 1000L
        "8690000000017".forEach { decoder.onCharacter(it, t); t += 5 }
        assertTrue(decoder.hasPending)
        assertEquals("8690000000017", decoder.onTerminator(t))
        assertFalse(decoder.hasPending)
    }

    @Test
    fun `flushes suffix-less scans after idle timeout only`() {
        val decoder = WedgeDecoder(idleFlushMillis = 120)
        decoder.onCharacters("ABC123", 1000)
        assertNull(decoder.flushIfIdle(1050))
        assertEquals("ABC123", decoder.flushIfIdle(1200))
    }

    @Test
    fun `stale buffer is discarded when a new burst starts`() {
        val decoder = WedgeDecoder(interKeyTimeoutMillis = 300)
        decoder.onCharacters("OLD", 1000)
        decoder.onCharacters("NEW", 2000)
        assertEquals("NEW", decoder.onTerminator(2010))
    }

    @Test
    fun `ignores control characters and too short codes`() {
        val decoder = WedgeDecoder(minLength = 3)
        decoder.onCharacter('\u0000', 1)
        decoder.onCharacter('A', 2)
        assertNull(decoder.onTerminator(3))
        decoder.onCharacters("AB\u0007C", 10)
        assertEquals("ABC", decoder.onTerminator(11))
    }
}
