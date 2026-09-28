package com.wmserp.app.core.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import kotlin.math.abs

object Formatters {
    private val qtyFormat = DecimalFormat("#,##0.###", DecimalFormatSymbols(Locale.US))
    private val moneyFormat = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))
    private val intFormat = DecimalFormat("#,##0", DecimalFormatSymbols(Locale.US))
    // Month names follow the app language (see LocaleDefaults); digits stay ASCII so they match ERPNext.
    private val dateOut get() = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.getDefault())
    private val dateTimeOut get() = DateTimeFormatter.ofPattern("dd MMM, HH:mm", Locale.getDefault())

    fun qty(value: Double): String = qtyFormat.format(value)

    fun int(value: Int): String = intFormat.format(value)

    fun money(value: Double, currency: String?): String {
        val symbol = currency?.takeIf { it.isNotBlank() }?.let { code ->
            runCatching { Currency.getInstance(code).getSymbol(Locale.getDefault()) }.getOrDefault(code)
        }
        val number = moneyFormat.format(value)
        return if (symbol == null) number else "$symbol $number"
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

    fun compactMoney(value: Double, currency: String?): String {
        val symbol = currency?.takeIf { it.isNotBlank() }?.let { code ->
            runCatching { Currency.getInstance(code).getSymbol(Locale.getDefault()) }.getOrDefault(code)
        }
        return if (symbol == null) compact(value) else "$symbol${compact(value)}"
    }

    fun date(iso: String?): String {
        if (iso.isNullOrBlank()) return "-"
        return runCatching { LocalDate.parse(iso.take(10)).format(dateOut) }.getOrDefault(iso)
    }

    fun dateTime(isoDate: String?, time: String?): String {
        if (isoDate.isNullOrBlank()) return "-"
        return runCatching {
            val d = LocalDate.parse(isoDate.take(10))
            val t = time?.takeIf { it.isNotBlank() }?.let { LocalTime.parse(it.take(8).padEnd(8, '0').let { s -> if (s.length == 5) "$s:00" else s }) } ?: LocalTime.MIDNIGHT
            LocalDateTime.of(d, t).format(dateTimeOut)
        }.getOrDefault(isoDate)
    }

    fun percent(value: Double): String = "${value.coerceIn(0.0, 100.0).toInt()}%"

    private fun trimmed(v: Double): String {
        val s = String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')
        return s
    }
}
