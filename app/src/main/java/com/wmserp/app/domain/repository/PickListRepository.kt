package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.model.RowUpdate
import com.wmserp.app.domain.model.WmsQrKeys

/** Backed by the `wmserp_picking.api.pick_list` whitelisted methods of the ERPNext custom app. */
interface PickListRepository {
    /** QR label keys from WMS Settings; cached after the first successful call. */
    suspend fun getQrKeys(forceRefresh: Boolean = false): AppResult<WmsQrKeys>
    suspend fun getMyPickLists(): AppResult<List<PickList>>
    suspend fun getPickList(name: String): AppResult<PickList>
    suspend fun startRow(name: String, rowName: String): AppResult<RowUpdate>
    suspend fun saveRowProgress(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String? = null,
        batchNo: String? = null,
        elapsedSeconds: Double? = null,
    ): AppResult<RowUpdate>
    suspend fun completeRow(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String? = null,
        batchNo: String? = null,
        elapsedSeconds: Double? = null,
    ): AppResult<RowUpdate>
    suspend fun generateDocument(name: String): AppResult<GeneratedDocument>
    suspend fun getPickerKpis(): AppResult<PickerKpis>
}
