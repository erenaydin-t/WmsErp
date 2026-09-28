package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PICK_QTY_TOLERANCE
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.model.QrLabel
import com.wmserp.app.domain.model.RowUpdate
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.model.isPickComplete
import com.wmserp.app.domain.repository.PickListRepository
import javax.inject.Inject

class GetWmsQrKeysUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(forceRefresh: Boolean = false): AppResult<WmsQrKeys> = repository.getQrKeys(forceRefresh)
}

class GetMyPickListsUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(): AppResult<List<PickList>> = repository.getMyPickLists()
}

class GetPickerKpisUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(): AppResult<PickerKpis> = repository.getPickerKpis()
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

internal fun rowNotAssigned(row: PickListItem): AppError =
    AppError.Validation("Row ${row.idx} (${row.itemCode}) is not assigned to you", ErrorCode.ROW_NOT_ASSIGNED, listOf(row.itemCode))

/** 0 <= qty <= required; over-picking is refused before anything is sent. */
internal fun validateRowQty(row: PickListItem, qty: Double): AppError? {
    if (qty < 0.0) return AppError.Validation("${row.itemCode}: quantity must be positive", ErrorCode.QTY_MUST_BE_POSITIVE)
    if (qty > row.requiredQty + PICK_QTY_TOLERANCE) {
        return AppError.Validation(
            "${row.itemCode}: cannot pick ${qty.trimZeros()} (required ${row.requiredQty.trimZeros()})",
            ErrorCode.OVER_PICK,
            listOf(row.itemCode, qty.trimZeros(), row.requiredQty.trimZeros()),
        )
    }
    return null
}

class StartPickRowUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList, row: PickListItem): AppResult<RowUpdate> {
        if (!row.isMine) return AppResult.Failure(rowNotAssigned(row))
        if (row.rowStatus == PickRowStatus.PICKED) {
            return AppResult.Failure(AppError.Validation("Row ${row.idx} (${row.itemCode}) is already picked", ErrorCode.ROW_ALREADY_PICKED, listOf(row.itemCode)))
        }
        return repository.startRow(pickList.name, row.rowName)
    }
}

/** Syncs a partial quantity; the scanned label (item + batch) is forwarded for server-side validation. */
class SavePickRowProgressUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(
        pickList: PickList,
        row: PickListItem,
        pickedQty: Double,
        label: QrLabel? = null,
        elapsedSeconds: Double? = null,
    ): AppResult<RowUpdate> {
        if (!row.isMine) return AppResult.Failure(rowNotAssigned(row))
        validateRowQty(row, pickedQty)?.let { return AppResult.Failure(it) }
        return repository.saveRowProgress(pickList.name, row.rowName, pickedQty, label?.itemCode, label?.batchNo, elapsedSeconds)
    }
}

/** Marks a row as picked; the quantity must equal the required quantity. */
class CompletePickRowUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(
        pickList: PickList,
        row: PickListItem,
        pickedQty: Double,
        elapsedSeconds: Double? = null,
    ): AppResult<RowUpdate> {
        if (!row.isMine) return AppResult.Failure(rowNotAssigned(row))
        validateRowQty(row, pickedQty)?.let { return AppResult.Failure(it) }
        if (!isPickComplete(pickedQty, row.requiredQty)) {
            return AppResult.Failure(
                AppError.Validation(
                    "${row.itemCode}: ${pickedQty.trimZeros()} of ${row.requiredQty.trimZeros()} picked",
                    ErrorCode.ROW_INCOMPLETE,
                    listOf(row.itemCode, pickedQty.trimZeros(), row.requiredQty.trimZeros()),
                )
            )
        }
        return repository.completeRow(pickList.name, row.rowName, pickedQty, null, row.batchNo, elapsedSeconds)
    }
}

class GeneratePickDocumentUseCase @Inject constructor(private val repository: PickListRepository) {
    suspend operator fun invoke(pickList: PickList): AppResult<GeneratedDocument> {
        if (!pickList.isCardPicked) {
            return AppResult.Failure(AppError.Validation("All rows must be picked before creating a document", ErrorCode.PICKING_NOT_COMPLETED))
        }
        if (pickList.purpose.targetDocument == null) {
            return AppResult.Failure(
                AppError.Validation("No document can be generated for purpose ${pickList.purposeLabel}", ErrorCode.UNSUPPORTED_PICK_PURPOSE, listOf(pickList.purposeLabel))
            )
        }
        return repository.generateDocument(pickList.name)
    }
}

/** Outcome of validating a scan against the rows assigned to the picker. */
sealed class PickScanOutcome {
    /** The scan is not a JSON QR label with the configured keys. */
    data class InvalidQr(val error: QrError, val raw: String) : PickScanOutcome()

    /** Item and batch match a row that still needs picking. */
    data class Match(val row: PickListItem, val label: QrLabel) : PickScanOutcome()

    /** Item matches but the batch differs from the one ERPNext allocated to the row. */
    data class WrongBatch(val row: PickListItem, val expected: String, val scanned: String?, val label: QrLabel) : PickScanOutcome()

    /** No row assigned to the picker carries this item. */
    data class NotAssigned(val label: QrLabel) : PickScanOutcome()

    /** Every matching row already has its required quantity (over-picking is refused). */
    data class AlreadyComplete(val row: PickListItem, val label: QrLabel) : PickScanOutcome()
}

/**
 * Strict scan validation: parses the QR label with the cached keys and matches it against the
 * picker's own rows. Item and batch must both match; there is no fallback to plain barcodes.
 */
class ValidatePickScanUseCase @Inject constructor() {
    operator fun invoke(
        rawCode: String,
        keys: WmsQrKeys,
        rows: List<PickListItem>,
        currentQty: (PickListItem) -> Double = { it.pickedQty },
        activeRowName: String? = null,
    ): PickScanOutcome {
        val label = when (val parsed = QrLabelParser.parse(rawCode, keys)) {
            is QrParseResult.Valid -> parsed.label
            is QrParseResult.Invalid -> return PickScanOutcome.InvalidQr(parsed.error, parsed.raw)
        }
        fun complete(row: PickListItem) = isPickComplete(currentQty(row), row.requiredQty)

        val byItem = rows.filter { it.isMine && it.itemCode.equals(label.itemCode, ignoreCase = true) }
        if (byItem.isEmpty()) return PickScanOutcome.NotAssigned(label)

        val exact = byItem.filter { batchMatches(it, label) }
        if (exact.isNotEmpty()) {
            val row = exact.firstOrNull { it.rowName == activeRowName && !complete(it) }
                ?: exact.firstOrNull { !complete(it) }
                ?: return PickScanOutcome.AlreadyComplete(exact.first(), label)
            return PickScanOutcome.Match(row, label)
        }
        val row = byItem.firstOrNull { it.rowName == activeRowName } ?: byItem.firstOrNull { !complete(it) } ?: byItem.first()
        return PickScanOutcome.WrongBatch(row, row.batchNo.orEmpty(), label.batchNo, label)
    }

    /** Rows without an allocated batch accept any label of the item; batch rows need the exact batch. */
    private fun batchMatches(row: PickListItem, label: QrLabel): Boolean =
        row.batchNo == null || label.batchNo?.equals(row.batchNo, ignoreCase = true) == true
}
