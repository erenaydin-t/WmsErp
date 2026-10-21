package com.wmserp.app.testutil

import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.RowUpdate

/** Simulates what the backend returns after a row update: recomputed totals and card status. */
fun PickList.withRow(rowName: String, qty: Double, status: PickRowStatus): PickList {
    val rows = items.map { if (it.rowName == rowName) it.copy(pickedQty = qty, rowStatus = status) else it }
    val picked = rows.count { it.rowStatus == PickRowStatus.PICKED }
    val mine = rows.filter { it.isMine }
    val all = rows.isNotEmpty() && picked == rows.size
    return copy(
        items = rows,
        pickedRows = picked,
        pickedQty = rows.sumOf { it.pickedQty },
        myPickedRows = mine.count { it.rowStatus == PickRowStatus.PICKED },
        myOpenRows = mine.count { it.rowStatus != PickRowStatus.PICKED },
        allRowsPicked = all,
        pickingStatus = if (all) PickingStatus.PICKED else PickingStatus.PICKING,
    )
}

fun rowUpdate(pickList: PickList, rowName: String, rowCompleted: Boolean, lastPicker: Boolean = false): RowUpdate =
    RowUpdate(
        pickList = pickList,
        row = pickList.items.first { it.rowName == rowName },
        rowCompleted = rowCompleted,
        cardCompleted = lastPicker,
        isLastPicker = lastPicker,
    )

fun qrLabel(itemCode: String, batchNo: String? = null): String =
    if (batchNo == null) """{"item_code":"$itemCode"}""" else """{"item_code":"$itemCode","batch_no":"$batchNo"}"""
