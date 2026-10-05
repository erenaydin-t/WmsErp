package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.missingRequiredFieldsOrNull
import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.dto.GeneratedDocumentDto
import com.wmserp.app.data.remote.dto.PickListDto
import com.wmserp.app.data.remote.dto.PickerKpisDto
import com.wmserp.app.data.remote.dto.RowUpdateDto
import com.wmserp.app.data.remote.dto.WmsSettingsDto
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.model.RowUpdate
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.PickListRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Talks to the `wmserp_picking` custom app (`/api/method/wmserp_picking.api.pick_list.*`).
 * Reads use GET, row operations use POST with a JSON body.
 */
class PickListRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : PickListRepository {

    @Volatile
    private var cachedKeys: WmsQrKeys? = null

    override suspend fun getQrKeys(forceRefresh: Boolean): AppResult<WmsQrKeys> {
        cachedKeys?.takeIf { !forceRefresh }?.let { return AppResult.Success(it) }
        return apiCaller.call {
            dataSource.getMethod<WmsSettingsDto>(method("get_settings")).toDomain().also { cachedKeys = it }
        }
    }

    override suspend fun getMyPickLists(): AppResult<List<PickList>> = apiCaller.call {
        dataSource.getMethod<List<PickListDto>>(method("get_my_pick_lists")).map { it.toDomain() }
    }

    override suspend fun getPickList(name: String): AppResult<PickList> = apiCaller.call {
        dataSource.getMethod<PickListDto>(method("get_pick_list"), mapOf("name" to name)).toDomain()
    }

    override suspend fun startRow(name: String, rowName: String): AppResult<RowUpdate> = apiCaller.call {
        dataSource.postMethod<RowUpdateDto>(
            method("start_row"),
            buildJsonObject {
                put("name", name)
                put("row", rowName)
            },
        ).toDomain()
    }

    override suspend fun saveRowProgress(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String?,
        batchNo: String?,
        elapsedSeconds: Double?,
    ): AppResult<RowUpdate> = apiCaller.call {
        dataSource.postMethod<RowUpdateDto>(method("save_row_progress"), rowBody(name, rowName, pickedQty, itemCode, batchNo, elapsedSeconds)).toDomain()
    }

    override suspend fun completeRow(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String?,
        batchNo: String?,
        elapsedSeconds: Double?,
    ): AppResult<RowUpdate> = apiCaller.call {
        dataSource.postMethod<RowUpdateDto>(method("complete_row"), rowBody(name, rowName, pickedQty, itemCode, batchNo, elapsedSeconds)).toDomain()
    }

    override suspend fun generateDocument(name: String, values: Map<String, String>): AppResult<GeneratedDocument> = apiCaller.call {
        val body = buildJsonObject {
            put("name", name)
            put("values", buildJsonObject { values.filterValues { it.isNotBlank() }.forEach { (key, value) -> put(key, value) } })
        }
        val message = dataSource.postRaw(method("generate_document"), body)
        // HTTP 200 with `missing_fields`: the site requires values the app must ask the picker for.
        message.missingRequiredFieldsOrNull(dataSource.json)?.let { throw AppException(it) }
        dataSource.decode(GeneratedDocumentDto.serializer(), message, method("generate_document")).toDomain()
    }

    override suspend fun getPickerKpis(): AppResult<PickerKpis> = apiCaller.call {
        dataSource.getMethod<PickerKpisDto>(method("get_picker_kpis")).toDomain()
    }

    private fun rowBody(name: String, rowName: String, pickedQty: Double, itemCode: String?, batchNo: String?, elapsedSeconds: Double?): JsonObject =
        buildJsonObject {
            put("name", name)
            put("row", rowName)
            put("picked_qty", pickedQty)
            itemCode?.takeIf { it.isNotBlank() }?.let { put("item_code", it) }
            batchNo?.takeIf { it.isNotBlank() }?.let { put("batch_no", it) }
            elapsedSeconds?.let { put("elapsed_seconds", it) }
        }

    companion object {
        const val METHOD_PREFIX = "wmserp_picking.api.pick_list."
        fun method(name: String): String = METHOD_PREFIX + name
    }
}
