package com.wmserp.app.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersianCalendarTest {

    @Test
    fun `nowruz and well known dates convert both ways`() {
        val cases = listOf(
            LocalDate.of(2026, 3, 21) to PersianCalendar.JalaliDate(1405, 1, 1),
            LocalDate.of(2026, 10, 16) to PersianCalendar.JalaliDate(1405, 7, 24),
            LocalDate.of(2024, 3, 20) to PersianCalendar.JalaliDate(1403, 1, 1),
            LocalDate.of(2024, 3, 19) to PersianCalendar.JalaliDate(1402, 12, 29),
            LocalDate.of(2025, 3, 20) to PersianCalendar.JalaliDate(1403, 12, 30), // 1403 was a leap year
            LocalDate.of(2025, 3, 21) to PersianCalendar.JalaliDate(1404, 1, 1),
            LocalDate.of(2026, 3, 20) to PersianCalendar.JalaliDate(1404, 12, 29),
            LocalDate.of(2000, 1, 1) to PersianCalendar.JalaliDate(1378, 10, 11),
            LocalDate.of(1979, 2, 11) to PersianCalendar.JalaliDate(1357, 11, 22),
        )
        cases.forEach { (gregorian, jalali) ->
            assertEquals("$gregorian", jalali, PersianCalendar.fromGregorian(gregorian))
            assertEquals("$jalali", gregorian, PersianCalendar.toGregorian(jalali.year, jalali.month, jalali.day))
        }
    }

    @Test
    fun `every day of several years round trips`() {
        var date = LocalDate.of(1990, 1, 1)
        val end = LocalDate.of(2060, 12, 31)
        while (!date.isAfter(end)) {
            val j = PersianCalendar.fromGregorian(date)
            assertTrue("$date -> $j", j.month in 1..12 && j.day in 1..PersianCalendar.monthLength(j.year, j.month))
            assertEquals(date, PersianCalendar.toGregorian(j.year, j.month, j.day))
            date = date.plusDays(1)
        }
    }

    @Test
    fun `leap years and month lengths`() {
        assertTrue(PersianCalendar.isLeapYear(1403))
        assertTrue(PersianCalendar.isLeapYear(1399))
        assertFalse(PersianCalendar.isLeapYear(1404))
        assertFalse(PersianCalendar.isLeapYear(1405))
        assertEquals(31, PersianCalendar.monthLength(1405, 6))
        assertEquals(30, PersianCalendar.monthLength(1405, 7))
        assertEquals(29, PersianCalendar.monthLength(1405, 12))
        assertEquals(30, PersianCalendar.monthLength(1403, 12))
        assertEquals("مهر", PersianCalendar.monthName(7))
    }
}
