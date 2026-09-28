package com.wmserp.app.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale

class FormattersTest {

    private lateinit var originalLocale: Locale

    @Before
    fun pinLocale() {
        originalLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    @Test
    fun `compact numbers`() {
        assertEquals("950", Formatters.compact(950.0))
        assertEquals("1,234", Formatters.compact(1234.0))
        assertEquals("12.5K", Formatters.compact(12_500.0))
        assertEquals("1.23M", Formatters.compact(1_234_567.0))
        assertEquals("2B", Formatters.compact(2_000_000_000.0))
    }

    @Test
    fun `quantities and dates`() {
        assertEquals("12", Formatters.qty(12.0))
        assertEquals("12.5", Formatters.qty(12.5))
        assertEquals("1,000.25", Formatters.qty(1000.25))
        assertEquals("28 Sep 2026", Formatters.date("2026-09-28"))
        assertEquals("-", Formatters.date(null))
        assertEquals("28 Sep, 14:05", Formatters.dateTime("2026-09-28", "14:05:33.123456"))
    }

    @Test
    fun `month names follow the app language while digits stay ascii`() {
        val persian = Locale.forLanguageTag("fa")
        Locale.setDefault(persian)

        val september = Month.SEPTEMBER.getDisplayName(TextStyle.SHORT, persian)
        assertEquals("28 $september 2026", Formatters.date("2026-09-28"))
        assertEquals("1,000.25", Formatters.qty(1000.25))
    }
}
