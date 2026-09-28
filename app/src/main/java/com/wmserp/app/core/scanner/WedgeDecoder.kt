package com.wmserp.app.core.scanner

/**
 * Reassembles barcodes delivered as a burst of key presses by a hardware scanner running in
 * keyboard-wedge mode. Pure Kotlin so the timing logic is unit-testable.
 *
 * Usage: feed printable characters via [onCharacter]; call [onTerminator] when Enter/Tab arrives.
 * For scanners without a suffix, periodically call [flushIfIdle].
 */
class WedgeDecoder(
    private val minLength: Int = 2,
    private val maxLength: Int = 512,
    /** Gap after which a pending buffer is considered stale (new burst starts). */
    private val interKeyTimeoutMillis: Long = 300,
    /** Idle time after the last character before a suffix-less scan is emitted. */
    private val idleFlushMillis: Long = 120,
) {
    private val buffer = StringBuilder()
    private var lastEventMillis: Long = 0L

    val hasPending: Boolean get() = buffer.isNotEmpty()

    fun onCharacter(char: Char, nowMillis: Long) {
        if (buffer.isNotEmpty() && nowMillis - lastEventMillis > interKeyTimeoutMillis) {
            buffer.setLength(0)
        }
        if (!char.isISOControl() && buffer.length < maxLength) buffer.append(char)
        lastEventMillis = nowMillis
    }

    fun onCharacters(chars: CharSequence, nowMillis: Long) {
        chars.forEach { onCharacter(it, nowMillis) }
    }

    /** Called on Enter/Tab. Returns the completed code or null if nothing useful was buffered. */
    fun onTerminator(nowMillis: Long): String? {
        lastEventMillis = nowMillis
        return take()
    }

    /** Emits the buffered code if no key arrived for [idleFlushMillis]. */
    fun flushIfIdle(nowMillis: Long): String? {
        if (buffer.isEmpty()) return null
        if (nowMillis - lastEventMillis < idleFlushMillis) return null
        return take()
    }

    fun reset() {
        buffer.setLength(0)
    }

    private fun take(): String? {
        val code = buffer.toString().trim()
        buffer.setLength(0)
        return code.takeIf { it.length >= minLength }
    }
}
