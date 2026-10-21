package com.wmserp.app.core.util

import java.time.LocalDate

/**
 * Gregorian ⇄ Jalali (Shamsi / Persian) calendar conversion, used by [Formatters] when the app
 * language is Persian. A port of the algorithm of Kazimierz M. Borkowski as implemented in jalaali-js
 * (MIT): exact for 1178 – 3177 AP (1799 – 3798 AD), which covers every date an ERPNext site holds.
 *
 * Only the display side of the app converts: dates travel to and from ERPNext as ISO `yyyy-MM-dd`.
 */
object PersianCalendar {

    /** A Jalali date; [month] and [day] are 1-based. */
    data class JalaliDate(val year: Int, val month: Int, val day: Int)

    /** Month names as written in Iran. */
    val MONTH_NAMES = listOf(
        "فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور",
        "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند",
    )

    fun fromGregorian(date: LocalDate): JalaliDate = fromJulianDay(julianDay(date.year, date.monthValue, date.dayOfMonth))

    fun toGregorian(year: Int, month: Int, day: Int): LocalDate {
        val jdn = julianDayOfJalali(year, month, day)
        return gregorianFromJulianDay(jdn)
    }

    fun monthName(month: Int): String = MONTH_NAMES[(month - 1).coerceIn(0, 11)]

    /** Days in a Jalali month (Esfand has 30 in a leap year). */
    fun monthLength(year: Int, month: Int): Int = when {
        month <= 6 -> 31
        month <= 11 -> 30
        isLeapYear(year) -> 30
        else -> 29
    }

    fun isLeapYear(year: Int): Boolean = jalCal(year).leap == 0

    // ---- Borkowski's algorithm ----------------------------------------------------------------
    // Integer arithmetic truncates toward zero, exactly as jalaali-js's `~~(a / b)` does.

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    private data class JalCal(val leap: Int, val gregorianYear: Int, val march: Int)

    private fun div(a: Int, b: Int): Int = a / b
    private fun mod(a: Int, b: Int): Int = a - div(a, b) * b

    /**
     * For a Jalali year: the leap remainder (0 means leap), the Gregorian year of its first day and
     * the March day on which it begins.
     */
    private fun jalCal(jy: Int): JalCal {
        require(jy in BREAKS.first() until BREAKS.last()) { "Jalali year $jy is outside 1178..3177" }
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += div(jump, 33) * 8 + div(mod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += div(n, 33) * 8 + div(mod(n, 33) + 3, 4)
        if (mod(jump, 33) == 4 && jump - n == 4) leapJ += 1
        val leapG = div(gy, 4) - div((div(gy, 100) + 1) * 3, 4) - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + div(jump + 4, 33) * 33
        var leap = mod(mod(n + 1, 33) - 1, 4)
        if (leap == -1) leap = 4
        return JalCal(leap, gy, march)
    }

    private fun julianDayOfJalali(jy: Int, jm: Int, jd: Int): Int {
        val r = jalCal(jy)
        return julianDay(r.gregorianYear, 3, r.march) + (jm - 1) * 31 - div(jm, 7) * (jm - 7) + jd - 1
    }

    private fun fromJulianDay(jdn: Int): JalaliDate {
        val gy = gregorianFromJulianDay(jdn).year
        var jy = gy - 621
        val r = jalCal(jy)
        val jdn1f = julianDay(gy, 3, r.march)
        var k = jdn - jdn1f
        if (k >= 0) {
            if (k <= 185) return JalaliDate(jy, 1 + div(k, 31), mod(k, 31) + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return JalaliDate(jy, 7 + div(k, 30), mod(k, 30) + 1)
    }

    /** Julian day number of a proleptic Gregorian date. */
    private fun julianDay(gy: Int, gm: Int, gd: Int): Int {
        var d = div((gy + div(gm - 8, 6) + 100100) * 1461, 4) + div(153 * mod(gm + 9, 12) + 2, 5) + gd - 34840408
        d -= div(div(gy + 100100 + div(gm - 8, 6), 100) * 3, 4)
        return d + 752
    }

    private fun gregorianFromJulianDay(jdn: Int): LocalDate {
        var j = 4 * jdn + 139361631
        j += div(div(4 * jdn + 183187720, 146097) * 3, 4) * 4 - 3908
        val i = div(mod(j, 1461), 4) * 5 + 308
        val gd = div(mod(i, 153), 5) + 1
        val gm = mod(div(i, 153), 12) + 1
        val gy = div(j, 1461) - 100100 + div(8 - gm, 6)
        return LocalDate.of(gy, gm, gd)
    }
}
