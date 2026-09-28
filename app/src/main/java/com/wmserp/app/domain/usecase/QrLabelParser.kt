package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.model.QrLabel
import com.wmserp.app.domain.model.WmsQrKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a scan was rejected as a QR label. */
enum class QrError { EMPTY, NOT_JSON, NOT_OBJECT, MISSING_ITEM }

sealed class QrParseResult {
    data class Valid(val label: QrLabel) : QrParseResult()
    data class Invalid(val error: QrError, val raw: String) : QrParseResult()
}

/**
 * Strict parser for the JSON QR labels printed by ERPNext ("WMS Batch QR Label"). The scan must be
 * a JSON object carrying the item key configured in WMS Settings; the batch key is optional.
 * Plain barcodes, item codes or batch numbers are rejected: picking is locked to QR labels.
 */
object QrLabelParser {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    fun parse(raw: String, keys: WmsQrKeys = WmsQrKeys.DEFAULT): QrParseResult {
        val text = ScanCodeSanitizer.sanitize(raw)
        if (text.isEmpty()) return QrParseResult.Invalid(QrError.EMPTY, raw)
        if (!text.startsWith("{")) return QrParseResult.Invalid(QrError.NOT_JSON, text)
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: Exception) {
            return QrParseResult.Invalid(QrError.NOT_JSON, text)
        }
        val obj = element as? JsonObject ?: return QrParseResult.Invalid(QrError.NOT_OBJECT, text)
        val itemCode = obj.stringValue(keys.itemKey) ?: return QrParseResult.Invalid(QrError.MISSING_ITEM, text)
        val batchNo = obj.stringValue(keys.batchKey)
        return QrParseResult.Valid(QrLabel(itemCode = itemCode, batchNo = batchNo, raw = text))
    }

    private fun JsonObject.stringValue(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.content.trim().ifEmpty { null }
    }
}
