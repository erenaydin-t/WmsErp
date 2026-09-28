package com.wmserp.app.data.util

import kotlinx.coroutines.CancellationException

/** Runs [block] and swallows every failure except coroutine cancellation. */
suspend inline fun <T> ignoringErrors(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
    null
}
