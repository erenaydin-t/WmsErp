package com.wmserp.app.data.remote

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray

/**
 * A single Frappe list filter, serialised as `[doctype?, field, operator, value]`.
 * Passing [doctype] allows filtering on child tables (e.g. `Item Barcode`).
 */
data class Filter(
    val field: String,
    val operator: String,
    val value: Any?,
    val doctype: String? = null,
) {
    companion object {
        fun eq(field: String, value: Any?) = Filter(field, "=", value)
        fun ne(field: String, value: Any?) = Filter(field, "!=", value)
        fun like(field: String, value: String) = Filter(field, "like", "%$value%")
        fun inList(field: String, values: List<String>) = Filter(field, "in", values)
        fun gte(field: String, value: Any?) = Filter(field, ">=", value)
        fun lte(field: String, value: Any?) = Filter(field, "<=", value)
        fun lt(field: String, value: Any?) = Filter(field, "<", value)
        fun gt(field: String, value: Any?) = Filter(field, ">", value)
    }
}

/** Helpers to render Frappe query parameters (`fields`, `filters`) as JSON strings. */
object FrappeQuery {
    fun fields(vararg names: String): String = fields(names.toList())

    fun fields(names: List<String>): String = buildJsonArray { names.forEach { add(JsonPrimitive(it)) } }.toString()

    fun filters(filters: List<Filter>): String? {
        if (filters.isEmpty()) return null
        return buildJsonArray {
            filters.forEach { f ->
                add(
                    buildJsonArray {
                        f.doctype?.let { add(JsonPrimitive(it)) }
                        add(JsonPrimitive(f.field))
                        add(JsonPrimitive(f.operator))
                        add(toJson(f.value))
                    }
                )
            }
        }.toString()
    }

    fun filters(vararg filters: Filter): String? = filters(filters.toList())

    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(if (value) 1 else 0)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toDouble())
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }
}
