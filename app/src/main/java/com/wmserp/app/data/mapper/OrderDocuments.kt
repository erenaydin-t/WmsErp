package com.wmserp.app.data.mapper

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.RequiredField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Turns the unsaved documents produced by ERPNext's own mappers (`make_purchase_receipt`,
 * `make_delivery_note`) into the document the app inserts.
 *
 * Starting from the mapped document keeps everything ERPNext copies from the order: rates and taxes,
 * accounting dimensions and any custom mandatory field on the item rows (a *Department*, a *Project*...)
 * that a document built from scratch would lack. The app only overrides the counted quantity, the
 * warehouse and, for batch-tracked delivery rows, the batch split.
 */
object OrderDocuments {

    fun purchaseReceipt(mapped: JsonObject, draft: PurchaseReceiptDraft): JsonObject {
        val byRow = draft.lines.associateBy { it.purchaseOrderRow }
        val offered = mapped.items().mapNotNull { it.string("purchase_order_item") }.toSet()
        draft.lines.firstOrNull { it.purchaseOrderRow !in offered }?.let { missing ->
            throw AppException(rowUnavailable(missing.itemCode, draft.purchaseOrderName))
        }
        val items = mapped.items().mapNotNull { row ->
            val line = byRow[row.string("purchase_order_item")] ?: return@mapNotNull null
            val conversionFactor = row.double("conversion_factor")?.takeIf { it > 0.0 } ?: 1.0
            row.with(
                "qty" to line.qty,
                "received_qty" to line.qty,
                "rejected_qty" to 0.0,
                "stock_qty" to line.qty * conversionFactor,
                "warehouse" to line.warehouse,
            )
        }
        return header(mapped, items, draft.lines.map { it.warehouse })
    }

    fun deliveryNote(mapped: JsonObject, draft: DeliveryNoteDraft): JsonObject {
        val byRow = draft.lines.associateBy { it.salesOrderRow }
        val offered = mapped.items().mapNotNull { it.string("so_detail") }.toSet()
        draft.lines.firstOrNull { it.salesOrderRow !in offered }?.let { missing ->
            throw AppException(rowUnavailable(missing.itemCode, draft.salesOrderName))
        }
        val items = mapped.items().flatMap { row ->
            val line = byRow[row.string("so_detail")] ?: return@flatMap emptyList()
            val conversionFactor = row.double("conversion_factor")?.takeIf { it > 0.0 } ?: line.conversionFactor
            if (line.batches.isEmpty()) {
                listOf(row.with("qty" to line.qty, "stock_qty" to line.qty * conversionFactor, "warehouse" to line.warehouse))
            } else {
                line.batches.map { allocation ->
                    row.with(
                        "qty" to allocation.qty / conversionFactor,
                        "stock_qty" to allocation.qty,
                        "warehouse" to line.warehouse,
                        "batch_no" to allocation.batchNo,
                        // ERPNext v15+ builds the Serial and Batch Bundle from these classic fields.
                        "use_serial_batch_fields" to 1,
                        "serial_and_batch_bundle" to null,
                    )
                }
            }
        }
        return header(mapped, items, draft.lines.map { it.warehouse })
    }

    /**
     * Fills required fields the site added (a mandatory *Department*, *Project*...) on the header and
     * every row: remembered or freshly given [answers] first, then the company's default accounting
     * dimension. Fields still empty are reported through [AppError.MissingRequiredFields] so the UI
     * can ask for them instead of ERPNext rejecting the document.
     */
    fun completeRequired(
        doc: JsonObject,
        required: List<RequiredField>,
        answers: Map<String, String>,
        dimensionDefaults: Map<String, String> = emptyMap(),
    ): JsonObject {
        if (required.isEmpty()) return doc
        val missing = linkedMapOf<String, RequiredField>()
        fun complete(obj: JsonObject, doctype: String): JsonObject {
            val fields = required.filter { it.doctype == doctype }
            if (fields.isEmpty()) return obj
            val values = obj.toMutableMap()
            for (field in fields) {
                if (!obj.string(field.fieldname).isNullOrBlank()) continue
                val value = answers[field.key]?.takeIf { it.isNotBlank() } ?: dimensionDefaults[field.fieldname]
                if (value != null) values[field.fieldname] = JsonPrimitive(value) else missing.putIfAbsent(field.key, field)
            }
            return JsonObject(values)
        }
        val headerDoctype = doc.string("doctype") ?: return doc
        val header = complete(doc, headerDoctype)
        val items = header.items().map { row -> complete(row, row.string("doctype") ?: "$headerDoctype Item") }
        if (missing.isNotEmpty()) throw AppException(AppError.MissingRequiredFields(missing.values.toList()))
        return JsonObject(header.toMutableMap().apply { this["items"] = JsonArray(items) })
    }

    private fun header(mapped: JsonObject, items: List<JsonObject>, warehouses: List<String>): JsonObject {
        val numbered = items.mapIndexed { index, row -> row.with("idx" to index + 1) }
        val commonWarehouse = warehouses.distinct().singleOrNull()
        return JsonObject(
            mapped.filterKeys { !it.startsWith("__") }.toMutableMap().apply {
                this["items"] = JsonArray(numbered)
                this["set_warehouse"] = commonWarehouse?.let { JsonPrimitive(it) } ?: JsonNull
            }
        )
    }

    private fun rowUnavailable(itemCode: String, orderName: String) = AppError.Validation(
        "$itemCode is no longer open on $orderName. Reload the order.",
        ErrorCode.ORDER_ROW_UNAVAILABLE,
        listOf(itemCode, orderName),
    )

    private fun JsonObject.items(): List<JsonObject> = (this["items"] as? JsonArray)?.map { it.jsonObject }.orEmpty()

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.with(vararg values: Pair<String, Any?>): JsonObject = JsonObject(
        toMutableMap().apply { values.forEach { (key, value) -> this[key] = value.toJson() } }
    )

    private fun Any?.toJson(): JsonElement = when (this) {
        null -> JsonNull
        is JsonElement -> this
        is String -> JsonPrimitive(this)
        is Int -> JsonPrimitive(this)
        is Double -> JsonPrimitive(this)
        is Boolean -> JsonPrimitive(if (this) 1 else 0)
        else -> JsonPrimitive(toString())
    }
}
