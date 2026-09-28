package com.wmserp.app.domain.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `adds https scheme when missing`() {
        assertEquals("https://erp.example.com", UrlNormalizer.normalize("erp.example.com"))
    }

    @Test
    fun `keeps explicit http scheme and port`() {
        assertEquals("http://192.168.1.10:8000", UrlNormalizer.normalize("http://192.168.1.10:8000/"))
    }

    @Test
    fun `strips desk paths, trailing slashes, query and fragment`() {
        assertEquals("https://erp.example.com", UrlNormalizer.normalize("  https://ERP.example.com/app/home?x=1#frag "))
        assertEquals("https://erp.example.com", UrlNormalizer.normalize("https://erp.example.com/api/"))
        assertEquals("https://erp.example.com/erp", UrlNormalizer.normalize("https://erp.example.com/erp/desk"))
    }

    @Test
    fun `rejects blank, unsupported schemes and bad ports`() {
        assertNull(UrlNormalizer.normalize(""))
        assertNull(UrlNormalizer.normalize("ftp://erp.example.com"))
        assertNull(UrlNormalizer.normalize("https://erp.example.com:99999"))
        assertNull(UrlNormalizer.normalize("https://"))
    }

    @Test
    fun `detects secure urls`() {
        assertTrue(UrlNormalizer.isSecure("https://erp.example.com"))
        assertFalse(UrlNormalizer.isSecure("http://erp.example.com"))
    }
}
