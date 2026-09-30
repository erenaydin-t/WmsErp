package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.dto.CountResultDto
import com.wmserp.app.data.remote.dto.StocktakingItemsPageDto
import com.wmserp.app.data.remote.dto.StocktakingLookupDto
import com.wmserp.app.data.remote.dto.StocktakingSessionDto
import com.wmserp.app.data.remote.dto.SyncCountsDto
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.StocktakingItemsPage
import com.wmserp.app.domain.model.StocktakingLookup
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.SyncOutcome
import com.wmserp.app.domain.repository.StocktakingRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Talks to `/api/method/wmserp_picking.api.stocktaking.*`: reads with GET, counts with POST JSON bodies. */
class StocktakingRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : StocktakingRepository {

    override suspend fun getMySessions(): AppResult<List<StocktakingSession>> = apiCaller.call {
        dataSource.getMethod<List<StocktakingSessionDto>>(method("get_my_sessions")).map { it.toDomain() }
    }

    override suspend fun getSession(name: String): AppResult<StocktakingSession> = apiCaller.call {
        dataSource.getMethod<StocktakingSessionDto>(method("get_session"), mapOf("name" to name)).toDomain()
    }

    override suspend fun getItems(name: String, start: Int, limit: Int, mineOnly: Boolean): AppResult<StocktakingItemsPage> = apiCaller.call {
        dataSource.getMethod<StocktakingItemsPageDto>(
            method("get_items"),
            mapOf("name" to name, "start" to start.toString(), "limit" to limit.toString(), "mine" to if (mineOnly) "1" else "0"),
        ).toDomain()
    }

    override suspend fun lookup(name: String, code: String): AppResult<StocktakingLookup> = apiCaller.call {
        dataSource.getMethod<StocktakingLookupDto>(method("lookup"), mapOf("name" to name, "code" to code)).toDomain()
    }

    override suspend fun submitCount(submission: CountSubmission): AppResult<CountResult> = apiCaller.call {
        dataSource.postMethod<CountResultDto>(method("submit_count"), submission.toBody(includeSession = true)).toDomain()
    }

    override suspend fun syncCounts(name: String, submissions: List<CountSubmission>): AppResult<SyncOutcome> = apiCaller.call {
        dataSource.postMethod<SyncCountsDto>(
            method("sync_counts"),
            buildJsonObject {
                put("name", name)
                put("counts", buildJsonArray { submissions.forEach { add(it.toBody(includeSession = false)) } })
            },
        ).toDomain()
    }

    private fun CountSubmission.toBody(includeSession: Boolean): JsonObject = buildJsonObject {
        if (includeSession) put("name", sessionName)
        put("qty", qty)
        itemName?.takeIf { it.isNotBlank() }?.let { put("item", it) }
        put("item_code", itemCode)
        batchNo?.takeIf { it.isNotBlank() }?.let { put("batch_no", it) }
        warehouse?.takeIf { it.isNotBlank() }?.let { put("warehouse", it) }
        put("client_ref", clientRef)
        put("device_time", deviceTime)
        note?.takeIf { it.isNotBlank() }?.let { put("note", it) }
        put("source", "App")
    }

    companion object {
        const val METHOD_PREFIX = "wmserp_picking.api.stocktaking."
        fun method(name: String): String = METHOD_PREFIX + name
    }
}
