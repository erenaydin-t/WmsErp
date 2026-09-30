package com.wmserp.app.data.mapper

import com.wmserp.app.domain.model.RequiredField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reads the DocType meta bundle returned by `frappe.desk.form.load.getdoctype` (`docs`: the DocType
 * followed by its child tables, with Custom Fields and Property Setters already applied).
 */
object DocTypeMeta {

    /** Field types a person can answer in a dialog; tables, breaks, attachments and the like are not. */
    private val ANSWERABLE = setOf(
        "Link", "Dynamic Link", "Select", "Data", "Small Text", "Text", "Long Text",
        "Int", "Float", "Currency", "Percent", "Date", "Datetime", "Time",
    )

    /**
     * Required fields of every DocType in [bundle] that ERPNext will not fill by itself: fields with
     * a default are skipped because `Document.insert` applies meta defaults to missing values.
     */
    fun requiredFields(bundle: JsonObject): List<RequiredField> {
        val docs = (bundle["docs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        return docs.flatMap { meta ->
            val doctype = meta.string("name") ?: return@flatMap emptyList()
            (meta["fields"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { df ->
                if (!df.flag("reqd")) return@mapNotNull null
                val fieldtype = df.string("fieldtype") ?: return@mapNotNull null
                if (fieldtype !in ANSWERABLE) return@mapNotNull null
                if (!df.string("default").isNullOrBlank()) return@mapNotNull null
                val fieldname = df.string("fieldname") ?: return@mapNotNull null
                RequiredField(
                    doctype = doctype,
                    fieldname = fieldname,
                    label = df.string("label")?.takeIf { it.isNotBlank() } ?: fieldname,
                    fieldtype = fieldtype,
                    options = df.string("options")?.takeIf { it.isNotBlank() },
                    default = null,
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content

    private fun JsonObject.flag(key: String): Boolean = string(key)?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
}
