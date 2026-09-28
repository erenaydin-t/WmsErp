package com.wmserp.app.data.remote

import com.wmserp.app.domain.common.AppError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Translates Frappe / ERPNext error payloads into [AppError]s.
 *
 * Frappe reports errors in several shapes, e.g.
 * ```
 * {"exception": "frappe.exceptions.ValidationError: Qty is mandatory", "exc_type": "ValidationError",
 *  "_server_messages": "[\"{\\\"message\\\": \\\"Qty is mandatory\\\"}\"]"}
 * ```
 */
object ErpNextErrorParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val htmlTag = Regex("<[^>]+>")

    fun parse(httpCode: Int, body: String?): AppError {
        val parsed = body?.takeIf { it.isNotBlank() }?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
        val obj = parsed as? JsonObject
        val excType = obj?.get("exc_type")?.asStringOrNull()
        val message = obj?.let { extractMessage(it) } ?: defaultMessage(httpCode)

        return when {
            excType == "AuthenticationError" || excType == "SessionExpired" || excType == "SessionStopped" ->
                AppError.Unauthorized(message)
            excType == "PermissionError" -> AppError.Forbidden(message)
            excType == "DoesNotExistError" -> AppError.NotFound(message)
            excType == "CSRFTokenError" -> AppError.Unauthorized(message)
            excType != null && excType in VALIDATION_TYPES -> AppError.Validation(message)
            httpCode == 401 -> AppError.Unauthorized(message)
            httpCode == 403 -> if (looksLikeAuthFailure(message)) AppError.Unauthorized(message) else AppError.Forbidden(message)
            httpCode == 404 -> AppError.NotFound(message)
            httpCode == 417 || httpCode == 400 || httpCode == 409 || httpCode == 422 -> AppError.Validation(message)
            httpCode >= 500 -> AppError.Server(message, httpCode)
            else -> AppError.Server(message, httpCode)
        }
    }

    /** Extracts the most helpful human readable message from a Frappe error object. */
    fun extractMessage(obj: JsonObject): String? {
        serverMessages(obj)?.let { return it }
        obj["message"]?.let { element ->
            when (element) {
                is JsonPrimitive -> element.contentOrNull()?.takeIf { it.isNotBlank() }?.let { return clean(it) }
                is JsonObject -> element["message"]?.asStringOrNull()?.let { return clean(it) }
                else -> Unit
            }
        }
        obj["_error_message"]?.asStringOrNull()?.let { return clean(it) }
        obj["exception"]?.asStringOrNull()?.let { exc ->
            // "frappe.exceptions.ValidationError: Qty is mandatory" -> "Qty is mandatory"
            val text = exc.substringAfter(": ", exc).trim()
            if (text.isNotBlank()) return clean(text)
        }
        return null
    }

    private fun serverMessages(obj: JsonObject): String? {
        val raw = obj["_server_messages"]?.asStringOrNull() ?: return null
        val list = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonArray ?: return null
        val messages = list.mapNotNull { entry ->
            val inner = when (entry) {
                is JsonPrimitive -> entry.contentOrNull()?.let { runCatching { json.parseToJsonElement(it) }.getOrNull() }
                else -> entry
            }
            when (inner) {
                is JsonObject -> inner["message"]?.asStringOrNull()
                is JsonPrimitive -> inner.contentOrNull()
                else -> null
            }?.let { clean(it) }?.takeIf { it.isNotBlank() }
        }
        return messages.distinct().joinToString("\n").takeIf { it.isNotBlank() }
    }

    private fun looksLikeAuthFailure(message: String): Boolean {
        val lower = message.lowercase()
        return "not permitted" !in lower && ("log in" in lower || "login" in lower || "session" in lower || "authentication" in lower)
    }

    private fun defaultMessage(code: Int): String = when (code) {
        400 -> "Bad request"
        401 -> "Invalid credentials or session expired"
        403 -> "You do not have permission for this action"
        404 -> "Not found"
        417 -> "The server rejected the request"
        500 -> "ERPNext server error"
        502, 503, 504 -> "ERPNext server is unavailable"
        else -> "Request failed (HTTP $code)"
    }

    fun clean(text: String): String = text
        .replace("<br>", "\n", ignoreCase = true)
        .replace("<br/>", "\n", ignoreCase = true)
        .replace(htmlTag, "")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .trim()

    private fun JsonElement.asStringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull()
    private fun JsonPrimitive.contentOrNull(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

    private val VALIDATION_TYPES = setOf(
        "ValidationError", "MandatoryError", "LinkValidationError", "InvalidNameError",
        "DuplicateEntryError", "NameError", "CharacterLengthExceededError", "TimestampMismatchError",
        "NegativeStockError", "SerialNoQtyError", "OverAllowanceError", "InvalidStatusError",
        "DocstatusTransitionError", "ImplicitCommitError", "UniqueValidationError", "OutgoingEmailError",
        "TypeError", "KeyError", "AttributeError", "ValueError",
    )

    /** Convenience for tests / callers that only have a raw JSON body. */
    fun exceptionTypeOf(body: String?): String? =
        body?.let { runCatching { json.parseToJsonElement(it).jsonObject["exc_type"]?.jsonPrimitive?.content }.getOrNull() }

    fun serverMessagesOf(body: String?): List<String> =
        body?.let { runCatching { json.parseToJsonElement(it).jsonObject["_server_messages"]?.jsonPrimitive?.content }.getOrNull() }
            ?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonArray }.getOrNull() }
            ?.mapNotNull { e -> (e as? JsonPrimitive)?.content?.let { runCatching { json.parseToJsonElement(it).jsonObject["message"]?.jsonPrimitive?.content }.getOrNull() } }
            .orEmpty()
}
