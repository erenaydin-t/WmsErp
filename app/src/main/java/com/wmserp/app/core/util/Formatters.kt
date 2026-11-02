package com.wmserp.app.core.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

object Formatters {
    private val qtyFormat = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US))
    private val intFormat = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    // Month names follow the app language (see LocaleDefaults); digits stay ASCII so they match ERPNext.
    private val dateOut get() = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
    private val dateTimeOut get() = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.getDefault())
    private val timeOut = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** Persian UI shows the Jalali (Shamsi) calendar; everything else the Gregorian one. */
    val usesJalali: Boolean get() = Locale.getDefault().language == PERSIAN

    fun qty(value: Double): String = qtyFormat.format(value)

    fun int(value: Int): String = intFormat.format(value)

    /** 13_400_000 -> "12.8 MB", 512 -> "512 B". */
    fun fileSize(bytes: Long): String = when {
        bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    /** 1234 -> "1.2K", 1_234_567 -> "1.23M". */
    fun compact(value: Double): String {
        val magnitude = abs(value)
        return when {
            magnitude >= 1_000_000_000 -> trimmed(value / 1_000_000_000) + "B"
            magnitude >= 1_000_000 -> trimmed(value / 1_000_000) + "M"
            magnitude >= 10_000 -> trimmed(value / 1_000) + "K"
            magnitude == Math.floor(magnitude) -> intFormat.format(value)
            else -> qtyFormat.format(value)
        }
    }

    /** `2026-10-16` -> "16 Oct 2026", or "24 مهر 1405" in the Persian UI. */
    fun date(iso: String?): String {
        if (iso.isNullOrBlank()) return "-"
        return runCatching { date(LocalDate.parse(iso.take(10))) }.getOrDefault(iso)
    }

    fun date(value: LocalDate): String =
        if (usesJalali) PersianCalendar.fromGregorian(value).let { "${it.day} ${PersianCalendar.monthName(it.month)} ${it.year}" } else value.format(dateOut)

    /** "16 Oct, 14:05" or "24 مهر، 14:05". */
    fun dateTime(isoDate: String?, time: String?): String {
        if (isoDate.isNullOrBlank()) return "-"
        return runCatching {
            val d = LocalDate.parse(isoDate.take(10))
            val t = time?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it.take(8).padEnd(8, '0').let { s -> if (s.length == 5) "$s:00" else s }) } ?: LocalTime.MIDNIGHT
            if (usesJalali) {
                val j = PersianCalendar.fromGregorian(d)
                "${j.day} ${PersianCalendar.monthName(j.month)}، ${t.format(timeOut)}"
            } else {
                LocalDateTime.of(d, t).format(dateTimeOut)
            }
        }.getOrDefault(isoDate)
    }

    /** "Oct 2026" or "مهر 1405": the month a dashboard figure covers. */
    fun monthYear(value: LocalDate): String =
        if (usesJalali) PersianCalendar.fromGregorian(value).let { "${PersianCalendar.monthName(it.month)} ${it.year}" }
        else value.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + value.year

    /** The first day of the month [value] belongs to in the calendar the UI shows. */
    fun startOfMonth(value: LocalDate): LocalDate =
        if (usesJalali) PersianCalendar.fromGregorian(value).let { PersianCalendar.toGregorian(it.year, it.month, 1) } else value.withDayOfMonth(1)

    fun percent(value: Double): String = "${value.coerceIn(0.0, 100.0).toInt()}%"

    /** 42 -> "42s", 125 -> "2m 05s", 3725 -> "1h 02m". */
    fun duration(seconds: Double): String {
        val total = seconds.coerceAtLeast(0.0).toLong()
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val secs = total % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, secs)
            else -> "${secs}s"
        }
    }

    private fun trimmed(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return s
    }

    private const val PERSIAN = "fa"
}
