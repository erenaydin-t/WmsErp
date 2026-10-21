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
    /**
     * Saves a partial quantity. With [hold] the server keeps the row *Picking* even at the required
     * quantity, so it stays editable until [completeRow] (the picker's final confirmation).
     */
    suspend fun saveRowProgress(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String? = null,
        batchNo: String? = null,
        elapsedSeconds: Double? = null,
        hold: Boolean = false,
    ): AppResult<RowUpdate>

    /** Clears the row's quantity, timers and a batch assigned by scan; refused once the document exists. */
    suspend fun resetRow(name: String, rowName: String): AppResult<RowUpdate>
    suspend fun completeRow(
        name: String,
        rowName: String,
        pickedQty: Double,
        itemCode: String? = null,
        batchNo: String? = null,
        elapsedSeconds: Double? = null,
    ): AppResult<RowUpdate>
    /**
     * Creates the Delivery Note / Stock Entry of a picked card. [values] answers required fields the
     * site added (`Doctype.fieldname` → value); when ERPNext still misses some, the result is a
     * [com.wmserp.app.domain.common.AppError.MissingRequiredFields] failure listing them.
     */
    suspend fun generateDocument(name: String, values: Map<String, String> = emptyMap()): AppResult<GeneratedDocument>
    suspend fun getPickerKpis(): AppResult<PickerKpis>
}
