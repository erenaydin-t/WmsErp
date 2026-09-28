package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickScanMatch

/** Backed by the `wmserp_picking.api.pick_list` whitelisted methods of the ERPNext custom app. */
interface PickListRepository {
    suspend fun getMyPickLists(): AppResult<List<PickList>>
    suspend fun getPickList(name: String): AppResult<PickList>
    suspend fun startPicking(name: String): AppResult<PickList>
    suspend fun saveProgress(name: String, lines: List<PickProgressLine>): AppResult<PickList>
    suspend fun completePicking(name: String, lines: List<PickProgressLine>): AppResult<PickList>
    suspend fun generateDocument(name: String): AppResult<GeneratedDocument>
    suspend fun resolveScan(name: String, code: String): AppResult<PickScanMatch>
}
