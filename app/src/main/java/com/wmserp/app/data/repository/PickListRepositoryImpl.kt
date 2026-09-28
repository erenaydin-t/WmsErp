package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.mapper.toRequest
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.dto.GeneratedDocumentDto
import com.wmserp.app.data.remote.dto.PickListDto
import com.wmserp.app.data.remote.dto.PickProgressItemRequest
import com.wmserp.app.data.remote.dto.PickScanMatchDto
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickScanMatch
import com.wmserp.app.domain.repository.PickListRepository
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Talks to the `wmserp_picking` custom app (`/api/method/wmserp_picking.api.pick_list.*`).
 * Reads use GET, transitions use POST with a JSON body.
 */
class PickListRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : PickListRepository {

    override suspend fun getMyPickLists(): AppResult<List<PickList>> = apiCaller.call {
        dataSource.getMethod<List<PickListDto>>(method("get_my_pick_lists")).map { it.toDomain() }
    }

    override suspend fun getPickList(name: String): AppResult<PickList> = apiCaller.call {
        dataSource.getMethod<PickListDto>(method("get_pick_list"), mapOf("name" to name)).toDomain()
    }

    override suspend fun startPicking(name: String): AppResult<PickList> = apiCaller.call {
        dataSource.postMethod<PickListDto>(method("start_picking"), buildJsonObject { put("name", name) }).toDomain()
    }

    override suspend fun saveProgress(name: String, lines: List<PickProgressLine>): AppResult<PickList> = apiCaller.call {
        dataSource.postMethod<PickListDto>(method("save_progress"), progressBody(name, lines)).toDomain()
    }

    override suspend fun completePicking(name: String, lines: List<PickProgressLine>): AppResult<PickList> = apiCaller.call {
        dataSource.postMethod<PickListDto>(method("complete_picking"), progressBody(name, lines)).toDomain()
    }

    override suspend fun generateDocument(name: String): AppResult<GeneratedDocument> = apiCaller.call {
        dataSource.postMethod<GeneratedDocumentDto>(method("generate_document"), buildJsonObject { put("name", name) }).toDomain()
    }

    override suspend fun resolveScan(name: String, code: String): AppResult<PickScanMatch> = apiCaller.call {
        dataSource.postMethod<PickScanMatchDto>(
            method("resolve_scan"),
            buildJsonObject {
                put("name", name)
                put("code", code)
            },
        ).toDomain()
    }

    private fun progressBody(name: String, lines: List<PickProgressLine>): JsonObject = buildJsonObject {
        put("name", name)
        put("items", dataSource.json.encodeToJsonElement(ListSerializer(PickProgressItemRequest.serializer()), lines.map { it.toRequest() }))
    }

    companion object {
        const val METHOD_PREFIX = "wmserp_picking.api.pick_list."
        fun method(name: String): String = METHOD_PREFIX + name
    }
}
