package com.wmserp.app.data.util

import java.time.LocalDate
import java.time.LocalDateTime

/** Abstraction over the clock so analytics date maths can be unit tested deterministically. */
interface DateProvider {
    fun today(): LocalDate
    fun now(): LocalDateTime = today().atStartOfDay()
}

class SystemDateProvider : DateProvider {
    override fun today(): LocalDate = LocalDate.now()
    override fun now(): LocalDateTime = LocalDateTime.now()
}
