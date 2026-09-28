package com.wmserp.app.data.remote

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import retrofit2.HttpException

/**
 * Typed convenience layer on top of [ErpNextApi]. Every method throws ([HttpException], IO errors,
 * [AppException]) and is expected to be wrapped by [ApiCaller.call] in repositories.
 */
class ErpNextDataSource(
    private val api: ErpNextApi,
    val json: Json,
) {
    suspend fun <T> getList(
        doctype: String,
        serializer: KSerializer<T>,
        fields: List<String>,
        filters: List<Filter> = emptyList(),
        orFilters: List<Filter> = emptyList(),
        orderBy: String? = null,
        limit: Int? = 20,
        start: Int? = null,
    ): List<T> {
        val response = api.getList(
            doctype = doctype,
            fields = FrappeQuery.fields(fields),
            filters = FrappeQuery.filters(filters),
            orFilters = FrappeQuery.filters(orFilters),
            orderBy = orderBy,
            limit = limit,
            start = start,
        )
        return response.data.map { json.decodeFromJsonElement(serializer, it) }
    }

    suspend inline fun <reified T> getList(
        doctype: String,
        fields: List<String>,
        filters: List<Filter> = emptyList(),
        orFilters: List<Filter> = emptyList(),
        orderBy: String? = null,
        limit: Int? = 20,
        start: Int? = null,
    ): List<T> = getList(doctype, serializer<T>(), fields, filters, orFilters, orderBy, limit, start)

    /** Returns null when the document does not exist. */
    suspend fun <T> getDocOrNull(doctype: String, name: String, serializer: KSerializer<T>): T? = try {
        json.decodeFromJsonElement(serializer, api.getDoc(doctype, name).data)
    } catch (e: HttpException) {
        if (e.code() == 404) null else throw e
    }

    suspend inline fun <reified T> getDocOrNull(doctype: String, name: String): T? = getDocOrNull(doctype, name, serializer<T>())

    suspend fun <T> getDoc(doctype: String, name: String, serializer: KSerializer<T>): T =
        getDocOrNull(doctype, name, serializer) ?: throw AppException(AppError.NotFound("$doctype $name not found"))

    suspend inline fun <reified T> getDoc(doctype: String, name: String): T = getDoc(doctype, name, serializer<T>())

    suspend fun <Req, Res> insert(doctype: String, body: Req, reqSerializer: KSerializer<Req>, resSerializer: KSerializer<Res>): Res {
        val element = json.encodeToJsonElement(reqSerializer, body) as JsonObject
        return json.decodeFromJsonElement(resSerializer, api.insertDoc(doctype, element).data)
    }

    suspend inline fun <reified Req, reified Res> insert(doctype: String, body: Req): Res =
        insert(doctype, body, serializer<Req>(), serializer<Res>())

    suspend fun <Req, Res> update(doctype: String, name: String, body: Req, reqSerializer: KSerializer<Req>, resSerializer: KSerializer<Res>): Res {
        val element = json.encodeToJsonElement(reqSerializer, body) as JsonObject
        return json.decodeFromJsonElement(resSerializer, api.updateDoc(doctype, name, element).data)
    }

    suspend inline fun <reified Req, reified Res> update(doctype: String, name: String, body: Req): Res =
        update(doctype, name, body, serializer<Req>(), serializer<Res>())

    /** Submits a draft document (`docstatus` 0 -> 1) via `frappe.client.submit`. */
    suspend fun submit(doctype: String, name: String): JsonObject {
        val doc = api.getDoc(doctype, name).data
        val response = api.postMethod("frappe.client.submit", buildJsonObject { put("doc", doc) })
        return response.message as? JsonObject ?: doc
    }

    suspend fun getCount(doctype: String, filters: List<Filter> = emptyList()): Int {
        val params = buildMap {
            put("doctype", doctype)
            FrappeQuery.filters(filters)?.let { put("filters", it) }
        }
        val message = api.callMethod("frappe.client.get_count", params).message
        return message?.let { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() } ?: 0
    }

    suspend fun getSingleValue(doctype: String, field: String): String? {
        val message = api.callMethod("frappe.client.get_single_value", mapOf("doctype" to doctype, "field" to field)).message
        return (message as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotBlank() }
    }

    /** Runs a script/query report through `frappe.desk.query_report.run`. */
    suspend fun runReport(reportName: String, filters: JsonObject): JsonElement? {
        val params = mapOf("report_name" to reportName, "filters" to filters.toString(), "ignore_prepared_report" to "1")
        return api.callMethod("frappe.desk.query_report.run", params).message
    }

    suspend fun loggedUser(): String? = api.getLoggedUser().message?.let { (it as? JsonPrimitive)?.content }

    fun JsonElement.contentOrNull(): String? = (this as? JsonPrimitive)?.let { if (it is kotlinx.serialization.json.JsonNull) null else it.jsonPrimitive.content }
}
