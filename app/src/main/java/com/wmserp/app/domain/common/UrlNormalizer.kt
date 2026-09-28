package com.wmserp.app.domain.common

/**
 * Normalises whatever the user types into the "ERPNext URL" field into a canonical base URL:
 *  - adds https:// when no scheme is given
 *  - lower-cases the host
 *  - strips trailing slashes, "/app", "/desk", "/api" suffixes and query/fragment parts
 */
object UrlNormalizer {
    private val schemeRegex = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")

    /** Path segments that mark the start of the Frappe desk / API rather than a site prefix. */
    private val deskSegments = setOf("app", "desk", "api", "login")

    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty()) return null
        if (!schemeRegex.containsMatchIn(value)) value = "https://$value"

        // Drop query & fragment
        value = value.substringBefore('?').substringBefore('#')

        val schemeEnd = value.indexOf("://") + 3
        val scheme = value.substring(0, schemeEnd).lowercase()
        if (scheme != "http://" && scheme != "https://") return null

        val rest = value.substring(schemeEnd)
        val slash = rest.indexOf('/')
        val hostPort = (if (slash >= 0) rest.substring(0, slash) else rest).lowercase()
        var path = if (slash >= 0) rest.substring(slash) else ""

        if (hostPort.isBlank()) return null
        if (!isValidHostPort(hostPort)) return null

        val segments = path.split('/').filter { it.isNotBlank() }
        val cut = segments.indexOfFirst { it.lowercase() in deskSegments }
        val kept = if (cut >= 0) segments.take(cut) else segments
        path = if (kept.isEmpty()) "" else "/" + kept.joinToString("/")
        return scheme + hostPort + path
    }

    fun isSecure(url: String): Boolean = url.startsWith("https://", ignoreCase = true)

    private fun isValidHostPort(hostPort: String): Boolean {
        val host: String
        val port: String?
        if (hostPort.startsWith("[")) { // IPv6 literal
            val end = hostPort.indexOf(']')
            if (end < 0) return false
            host = hostPort.substring(1, end)
            port = hostPort.substring(end + 1).removePrefix(":").ifEmpty { null }
            if (hostPort.length > end + 1 && !hostPort.substring(end + 1).startsWith(":")) return false
        } else {
            val parts = hostPort.split(":")
            if (parts.size > 2) return false
            host = parts[0]
            port = parts.getOrNull(1)
        }
        if (host.isBlank() || host.any { it.isWhitespace() }) return false
        if (port != null && (port.toIntOrNull() == null || port.toInt() !in 1..65535)) return false
        return true
    }
}
