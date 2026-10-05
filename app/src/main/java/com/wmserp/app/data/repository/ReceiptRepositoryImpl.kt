package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.missingRequiredFieldsOrNull
import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.mapper.toReceiveResult
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.LinkNameDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.ReceiptCount
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.repository.ReceiptRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import retrofit2.HttpException
import java.io.IOException

/**
 * Talks to `/api/method/wmserp_picking.api.purchase_receipt.*`: reads with GET, `receive` with a
 * JSON POST body. A `missing_fields` answer (HTTP 200) becomes [AppError.MissingRequiredFields].
 */
class ReceiptRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : ReceiptRepository {

    override suspend fun getReceivableReceipts(query: String, limit: Int): AppResult<List<PurchaseReceipt>> = apiCaller.call {
        val params = buildMap {
            put("limit", limit.toString())
            if (query.isNotBlank()) put("query", query)
        }
        dataSource.getMethod<List<PurchaseReceiptDto>>(method("get_receivable"), params).map { it.toDomain() }
    }

    override suspend fun getPurchaseReceipt(name: String): AppResult<PurchaseReceipt?> = apiCaller.call {
        try {
            dataSource.getMethod<PurchaseReceiptDto>(method("get_receipt"), mapOf("name" to name)).toDomain()
        } catch (e: HttpException) {
            if (e.code() == 404) null else throw e
        } catch (e: AppException) {
            if (e.error is AppError.NotFound) null else throw e
        }
    }

    override suspend fun receive(name: String, counts: List<ReceiptCount>, fieldValues: Map<String, String>, submit: Boolean): AppResult<ReceiveResult> = apiCaller.call {
        val body = buildJsonObject {
            put("name", name)
            put("submit", if (submit) 1 else 0)
            // Saving progress must not drop the rows that were not counted yet.
            put("remove_unreceived", if (submit) 1 else 0)
            put("rows", buildJsonArray {
                counts.forEach { count ->
                    add(
                        buildJsonObject {
                            put("row", count.rowName)
                            put("qty", count.qty)
                            count.warehouse?.takeIf { it.isNotBlank() }?.let { put("warehouse", it) }
                            count.batchNo?.takeIf { it.isNotBlank() }?.let { put("batch_no", it) }
                        }
                    )
                }
            })
            put("values", buildJsonObject { fieldValues.filterValues { it.isNotBlank() }.forEach { (key, value) -> put(key, value) } })
        }
        val message = dataSource.postRaw(method("receive"), body)
        message.missingRequiredFieldsOrNull(dataSource.json)?.let { throw AppException(it) }
        dataSource.decode(PurchaseReceiptDto.serializer(), message, method("receive")).toReceiveResult()
    }

    override suspend fun searchLinkValues(doctype: String, query: String, company: String?, limit: Int): AppResult<List<String>> = apiCaller.call {
        val filters = if (query.isBlank()) emptyList() else listOf(Filter.like("name", query))
        val scoped = if (company.isNullOrBlank()) null else optional { linkNames(doctype, filters + Filter.eq("company", company), limit) }
        scoped ?: linkNames(doctype, filters, limit)
    }

    private suspend fun linkNames(doctype: String, filters: List<Filter>, limit: Int): List<String> =
        dataSource.getList<LinkNameDto>(doctype = doctype, fields = listOf("name"), filters = filters, orderBy = "name asc", limit = limit).map { it.name }

    /** Runs a non-essential lookup; null when the server cannot answer it (never swallows cancellation). */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: AppException) {
        null
    } catch (e: HttpException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    companion object {
        const val METHOD_PREFIX = "wmserp_picking.api.purchase_receipt."
        fun method(name: String): String = METHOD_PREFIX + name
    }
}
