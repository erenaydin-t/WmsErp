package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickScanMatchType
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.PICK_QTY_TOLERANCE
import com.wmserp.app.domain.model.isPickComplete
import com.wmserp.app.domain.repository.PickListRepository
import javax.inject.Inject

class GetMyPickListsUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(): AppResult<List<PickList>> = repository.getMyPickLists()
}

class GetPickListUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(name: String): AppResult<PickList> {
        val trimmed = name.trim()
        val result = repository.getPickList(trimmed)
        return if (result is AppResult.Failure && result.error is AppError.NotFound) {
            AppResult.Failure(AppError.NotFound("Pick List $trimmed not found", ErrorCode.PICK_LIST_NOT_FOUND, listOf(trimmed)))
        } else {
            result
        }
    }
}

class StartPickingUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList): AppResult<PickList> {
        if (pickList.pickingStatus == PickingStatus.PICKED) {
            return AppResult.Failure(AppError.Validation("Pick List ${pickList.name} has already been picked", ErrorCode.PICK_LIST_ALREADY_PICKED))
        }
        return repository.startPicking(pickList.name)
    }
}

/** Validates picked quantities against the pick list rows before they are sent to the server. */
internal fun validatePickLines(pickList: PickList, lines: List<PickProgressLine>): AppError? {
    val rows = pickList.items.associateBy { it.rowName }
    for (line in lines) {
        val row = rows[line.rowName]
            ?: return AppError.Validation("Unknown pick list row ${line.rowName}", ErrorCode.UNKNOWN_ORDER_ROW, listOf(line.rowName))
        if (line.pickedQty < 0.0) {
            return AppError.Validation("${row.itemCode}: quantity must be positive", ErrorCode.QTY_MUST_BE_POSITIVE)
        }
        if (line.pickedQty > row.requiredQty + PICK_QTY_TOLERANCE) {
            return AppError.Validation(
                "${row.itemCode}: cannot pick ${line.pickedQty.trimZeros()} (required ${row.requiredQty.trimZeros()})",
                ErrorCode.OVER_PICK,
                listOf(row.itemCode, line.pickedQty.trimZeros(), row.requiredQty.trimZeros()),
            )
        }
    }
    return null
}

class SavePickProgressUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList, lines: List<PickProgressLine>): AppResult<PickList> {
        if (pickList.pickingStatus != PickingStatus.PICKING) {
            return AppResult.Failure(AppError.Validation("Start picking before saving progress", ErrorCode.PICKING_NOT_STARTED))
        }
        validatePickLines(pickList, lines)?.let { return AppResult.Failure(it) }
        return repository.saveProgress(pickList.name, lines)
    }
}

class CompletePickingUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList, lines: List<PickProgressLine>): AppResult<PickList> {
        if (pickList.pickingStatus != PickingStatus.PICKING) {
            return AppResult.Failure(AppError.Validation("Start picking before completing it", ErrorCode.PICKING_NOT_STARTED))
        }
        validatePickLines(pickList, lines)?.let { return AppResult.Failure(it) }
        val quantities = lines.associate { it.rowName to it.pickedQty }
        val incomplete = pickList.items.count { row ->
            !isPickComplete(quantities[row.rowName] ?: row.pickedQty, row.requiredQty, row.optional)
        }
        if (incomplete > 0) {
            return AppResult.Failure(
                AppError.Validation("$incomplete line(s) still need to be picked in full", ErrorCode.PICKING_INCOMPLETE, listOf(incomplete.toString()))
            )
        }
        return repository.completePicking(pickList.name, lines)
    }
}

class GeneratePickDocumentUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList): AppResult<GeneratedDocument> {
        if (pickList.pickingStatus != PickingStatus.PICKED) {
            return AppResult.Failure(AppError.Validation("Complete picking before creating a document", ErrorCode.PICKING_NOT_COMPLETED))
        }
        if (pickList.purpose.targetDocument == null) {
            return AppResult.Failure(
                AppError.Validation("No document can be generated for purpose ${pickList.purposeLabel}", ErrorCode.UNSUPPORTED_PICK_PURPOSE, listOf(pickList.purposeLabel))
            )
        }
        return repository.generateDocument(pickList.name)
    }
}

/** Outcome of matching a scanned code against the rows of a pick list. */
sealed class PickScanResult {
    abstract val code: String

    data class Matched(
        override val code: String,
        val row: PickListItem,
        val matchType: PickScanMatchType,
        val batchNo: String? = null,
    ) : PickScanResult()

    data class NotOnList(override val code: String, val itemCode: String?) : PickScanResult()

    data class Unknown(override val code: String) : PickScanResult()
}

/**
 * Resolves a scan first against the rows already on the device (item code, known barcodes, batch)
 * and only then asks ERPNext (`resolve_scan`) for barcodes/batches the app has not seen.
 */
class ResolvePickScanUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(
        pickList: PickList,
        rawCode: String,
        currentQty: (PickListItem) -> Double = { it.pickedQty },
    ): AppResult<PickScanResult> {
        val code = ScanCodeSanitizer.sanitize(rawCode)
        if (code.isEmpty()) return AppResult.Failure(AppError.Validation("Empty barcode", ErrorCode.EMPTY_BARCODE))

        matchLocally(pickList, code, currentQty)?.let { return AppResult.Success(it) }

        return repository.resolveScan(pickList.name, code).map { match ->
            val row = match.rowName?.let { name -> pickList.items.firstOrNull { it.rowName == name } }
            when {
                match.matchType == PickScanMatchType.NONE -> PickScanResult.Unknown(code)
                match.matchType == PickScanMatchType.NOT_ON_LIST || row == null -> PickScanResult.NotOnList(code, match.itemCode)
                else -> PickScanResult.Matched(code, row, match.matchType, match.batchNo ?: row.batchNo)
            }
        }
    }

    private fun matchLocally(pickList: PickList, code: String, currentQty: (PickListItem) -> Double): PickScanResult.Matched? {
        val byItemCode = pickList.items.filter { it.itemCode.equals(code, ignoreCase = true) }
        pickRow(byItemCode, currentQty)?.let { return PickScanResult.Matched(code, it, PickScanMatchType.ITEM_CODE, it.batchNo) }

        val byBarcode = pickList.items.filter { row -> row.barcodes.any { it.equals(code, ignoreCase = true) } }
        pickRow(byBarcode, currentQty)?.let { return PickScanResult.Matched(code, it, PickScanMatchType.BARCODE, it.batchNo) }

        val byBatch = pickList.items.filter { it.batchNo?.equals(code, ignoreCase = true) == true }
        pickRow(byBatch, currentQty)?.let { return PickScanResult.Matched(code, it, PickScanMatchType.BATCH, it.batchNo) }
        return null
    }

    /** Prefers a row that still needs picking so repeated scans fill rows in order. */
    private fun pickRow(candidates: List<PickListItem>, currentQty: (PickListItem) -> Double): PickListItem? =
        candidates.firstOrNull { !isPickComplete(currentQty(it), it.requiredQty, it.optional) } ?: candidates.firstOrNull()
}
