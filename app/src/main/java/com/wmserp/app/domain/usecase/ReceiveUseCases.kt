package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.ReceiptCount
import com.wmserp.app.domain.model.ReceiptDifference
import com.wmserp.app.domain.model.ReceiveResult
import com.wmserp.app.domain.repository.ReceiptRepository
import com.wmserp.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetReceivableReceiptsUseCase @Inject constructor(private val repository: ReceiptRepository) {
    suspend operator fun invoke(query: String = ""): AppResult<List<PurchaseReceipt>> = repository.getReceivableReceipts(query.trim())
}

class GetPurchaseReceiptUseCase @Inject constructor(private val repository: ReceiptRepository) {
    suspend operator fun invoke(name: String): AppResult<PurchaseReceipt> =
        repository.getPurchaseReceipt(name.trim()).flatMap { receipt ->
            if (receipt == null) {
                AppResult.Failure(AppError.NotFound("Purchase Receipt $name not found", ErrorCode.PURCHASE_RECEIPT_NOT_FOUND, listOf(name)))
            } else {
                AppResult.Success(receipt)
            }
        }
}

/** Quantity the warehouse counted for one receipt row (row UOM), with its optional overrides. */
data class ReceiveLine(val rowName: String, val qty: Double, val warehouse: String? = null, val batchNo: String? = null)

/**
 * Confirms a draft Purchase Receipt with the counted quantities: rows counted 0 are dropped (the
 * Purchase Order stays open for them), the rest take the counted quantity, then the receipt is
 * saved and, when [submit], submitted through the site's workflow. [fieldValues] answers required
 * fields the site added; answers remembered from earlier documents are merged in automatically.
 */
class ReceivePurchaseReceiptUseCase @Inject constructor(
    private val repository: ReceiptRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(
        receipt: PurchaseReceipt,
        lines: List<ReceiveLine>,
        defaultWarehouse: String? = null,
        submit: Boolean = true,
        fieldValues: Map<String, String> = emptyMap(),
    ): AppResult<ReceiveResult> {
        val byRow = receipt.items.associateBy { it.rowName }
        val counts = mutableListOf<ReceiptCount>()
        for (line in lines) {
            val row = byRow[line.rowName]
                ?: return AppResult.Failure(AppError.Validation("Unknown receipt row ${line.rowName}", ErrorCode.UNKNOWN_ORDER_ROW, listOf(line.rowName)))
            if (line.qty < 0.0) {
                return AppResult.Failure(AppError.Validation("${row.itemCode}: quantity must be positive", ErrorCode.QTY_MUST_BE_POSITIVE))
            }
            if (line.qty <= QTY_TOLERANCE) continue
            val warehouse = line.warehouse?.ifBlank { null } ?: row.warehouse ?: receipt.setWarehouse ?: defaultWarehouse?.ifBlank { null }
                ?: return AppResult.Failure(AppError.Validation("${row.itemCode}: select a warehouse", ErrorCode.SELECT_WAREHOUSE_FOR_ITEM, listOf(row.itemCode)))
            val batch = line.batchNo?.trim()?.ifBlank { null } ?: row.batchNo
            if (row.needsBatch && batch == null) {
                return AppResult.Failure(AppError.Validation("${row.itemCode}: scan or enter the batch", ErrorCode.BATCH_REQUIRED_FOR_ITEM, listOf(row.itemCode)))
            }
            counts += ReceiptCount(rowName = row.rowName, qty = line.qty, warehouse = warehouse, batchNo = batch)
        }
        if (counts.isEmpty()) return AppResult.Failure(AppError.Validation("Count at least one row to receive", ErrorCode.NOTHING_TO_RECEIVE))
        return repository.receive(
            name = receipt.name,
            counts = counts,
            fieldValues = settingsRepository.documentFieldDefaults.first() + fieldValues,
            submit = submit,
        )
    }

    companion object {
        const val QTY_TOLERANCE = 1e-9

        /** Rows whose count differs from the draft (0 = not received), for the confirmation before submitting. */
        fun differences(receipt: PurchaseReceipt, lines: List<ReceiveLine>): List<ReceiptDifference> {
            val counted = lines.associate { it.rowName to it.qty }
            return receipt.items.mapNotNull { row ->
                val qty = counted[row.rowName] ?: 0.0
                if (kotlin.math.abs(qty - row.qty) <= QTY_TOLERANCE) null else ReceiptDifference(row.rowName, row.itemCode, row.qty, qty)
            }
        }
    }
}

/** Possible values of a required Link field, offered in the "more details" dialog. */
class SearchLinkValuesUseCase @Inject constructor(private val repository: ReceiptRepository) {
    suspend operator fun invoke(doctype: String, query: String = "", company: String? = null): AppResult<List<String>> =
        repository.searchLinkValues(doctype, query.trim(), company)
}

/** Remembers the answers to required fields so the next documents do not ask again. */
class SaveDocumentFieldDefaultsUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(values: Map<String, String>) =
        settingsRepository.setDocumentFieldDefaults(values.filterValues { it.isNotBlank() })
}

/** Remembered answers to required fields (`Doctype.fieldname` → value). */
class GetDocumentFieldDefaultsUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(): Map<String, String> = settingsRepository.documentFieldDefaults.first()
}

internal fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString() else this.toString()
