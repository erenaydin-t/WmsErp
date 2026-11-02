package com.wmserp.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionNumberTest {

    @Test
    fun `parses tags and plain versions`() {
        assertEquals(VersionNumber(listOf(1, 1, 57)), VersionNumber.parse("v1.1.57"))
        assertEquals(VersionNumber(listOf(1, 1, 57)), VersionNumber.parse("1.1.57"))
        assertEquals(VersionNumber(listOf(1, 1, 0), preRelease = true), VersionNumber.parse("1.1.0-dev"))
        assertEquals(VersionNumber.ZERO, VersionNumber.parse(""))
        assertEquals(VersionNumber.ZERO, VersionNumber.parse(null))
        assertEquals(VersionNumber.ZERO, VersionNumber.parse("garbage"))
    }

    @Test
    fun `compares numerically part by part`() {
        assertTrue(VersionNumber.parse("1.1.57") > VersionNumber.parse("1.1.42"))
        assertTrue(VersionNumber.parse("1.2") > VersionNumber.parse("1.1.99"))
        assertTrue(VersionNumber.parse("1.10.0") > VersionNumber.parse("1.9.0"))
        assertEquals(0, VersionNumber.parse("1.1").compareTo(VersionNumber.parse("1.1.0")))
        assertFalse(VersionNumber.parse("1.0.0") > VersionNumber.parse("1.0.0"))
    }

    @Test
    fun `a pre-release sorts below the same plain version`() {
        assertTrue(VersionNumber.parse("1.1.0-dev") < VersionNumber.parse("1.1.0"))
        assertTrue(VersionNumber.parse("1.1.0-dev") < VersionNumber.parse("1.1.5"))
        assertTrue(VersionNumber.parse("1.1.1-rc1") > VersionNumber.parse("1.1.0"))
    }
}
