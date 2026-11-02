package com.wmserp.app.core.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
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
        val german = Locale.GERMAN
        Locale.setDefault(german)

        val september = Month.SEPTEMBER.getDisplayName(TextStyle.SHORT, german)
        assertEquals("28 $september 2026", Formatters.date("2026-09-28"))
        assertEquals("1,000.25", Formatters.qty(1000.25))
    }

    @Test
    fun `the Persian UI shows Jalali dates with Persian month names and ascii digits`() {
        Locale.setDefault(Locale.forLanguageTag("fa"))

        assertTrue(Formatters.usesJalali)
        assertEquals("24 مهر 1405", Formatters.date("2026-10-16"))
        assertEquals("1 فروردین 1405", Formatters.date("2026-03-21"))
        assertEquals("24 مهر، 14:05", Formatters.dateTime("2026-10-16", "14:05:33.123456"))
        assertEquals("مهر 1405", Formatters.monthYear(LocalDate.of(2026, 10, 16)))
        // "This month" on the dashboard starts on the first of Mehr, not on 1 October.
        assertEquals(LocalDate.of(2026, 9, 23), Formatters.startOfMonth(LocalDate.of(2026, 10, 16)))
        assertEquals("1,000.25", Formatters.qty(1000.25))
        assertEquals("-", Formatters.date(null))
        assertEquals("not-a-date", Formatters.date("not-a-date"))
    }

    @Test
    fun `other languages keep the Gregorian calendar`() {
        assertFalse(Formatters.usesJalali)
        assertEquals("Oct 2026", Formatters.monthYear(LocalDate.of(2026, 10, 16)))
        assertEquals(LocalDate.of(2026, 10, 1), Formatters.startOfMonth(LocalDate.of(2026, 10, 16)))
    }
}
