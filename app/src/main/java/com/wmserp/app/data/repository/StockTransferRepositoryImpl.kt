package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.missingRequiredFieldsOrNull
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.dto.CreatedTransferDto
import com.wmserp.app.data.remote.dto.TransferChoiceFieldDto
import com.wmserp.app.data.remote.dto.TransferFormDto
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CreatedTransfer
import com.wmserp.app.domain.model.TransferChoiceField
import com.wmserp.app.domain.model.TransferForm
import com.wmserp.app.domain.model.TransferLine
import com.wmserp.app.domain.model.TransferRequest
import com.wmserp.app.domain.repository.StockTransferRepository
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Talks to `/api/method/wmserp_picking.api.stock_transfer.*` (replaces the app's own REST insert + submit). */
class StockTransferRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : StockTransferRepository {

    override suspend fun getTransferForm(itemCode: String): AppResult<TransferForm> = apiCaller.call {
        val dto = dataSource.getMethod<TransferFormDto>(method("get_transfer_form"), mapOf("item_code" to itemCode))
        TransferForm(department = dto.department?.toDomain("Department"), receivedBy = dto.receivedBy?.toDomain("Employee"), hasBatchNo = dto.hasBatchNo)
    }

    override suspend fun createTransfer(request: TransferRequest, values: Map<String, String>): AppResult<CreatedTransfer> = apiCaller.call {
        val body = buildJsonObject {
            put("item_code", request.itemCode)
            put("qty", request.qty)
            put("s_warehouse", request.sourceWarehouse)
            put("t_warehouse", request.targetWarehouse)
            request.batchNo?.takeIf { it.isNotBlank() }?.let { put("batch_no", it) }
            request.department?.takeIf { it.isNotBlank() }?.let { put("department", it) }
            request.receivedBy?.takeIf { it.isNotBlank() }?.let { put("received_by", it) }
            put("values", buildJsonObject { values.filterValues { it.isNotBlank() }.forEach { (key, value) -> put(key, value) } })
        }
        val message = dataSource.postRaw(method("create_transfer"), body)
        // HTTP 200 with `missing_fields`: the site requires values the app must ask for; nothing was created.
        message.missingRequiredFieldsOrNull(dataSource.json)?.let { throw AppException(it) }
        val dto = dataSource.decode(CreatedTransferDto.serializer(), message, method("create_transfer"))
        CreatedTransfer(dto.name, dto.docstatus, dto.items.map { TransferLine(it.batchNo?.takeIf { b -> b.isNotBlank() }, it.qty) })
    }

    private fun TransferChoiceFieldDto.toDomain(defaultOptions: String) = TransferChoiceField(
        fieldname = fieldname,
        label = label?.takeIf { it.isNotBlank() } ?: fieldname,
        options = options?.takeIf { it.isNotBlank() } ?: defaultOptions,
    )

    companion object {
        const val METHOD_PREFIX = "wmserp_picking.api.stock_transfer."
        fun method(name: String): String = METHOD_PREFIX + name
    }
}
