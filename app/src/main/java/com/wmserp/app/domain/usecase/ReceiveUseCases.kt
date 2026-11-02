package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.BatchCount
import com.wmserp.app.domain.model.BatchLookup
import com.wmserp.app.domain.model.CreatedBatch
import com.wmserp.app.domain.model.NewBatch
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

/**
 * Quantity the warehouse counted for one receipt row (row UOM), with its optional overrides. A batch
 * tracked row received in one or more batches sends [batches] (each with its own quantity); [qty]
 * is then their sum.
 */
data class ReceiveLine(
    val rowName: String,
    val qty: Double,
    val warehouse: String? = null,
    val batchNo: String? = null,
    val batches: List<BatchCount> = emptyList(),
)

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
            // A fixed asset or service row holds no stock and takes no warehouse.
            val warehouse = if (!row.isStockItem) null else line.warehouse?.ifBlank { null } ?: row.warehouse ?: receipt.setWarehouse ?: defaultWarehouse?.ifBlank { null }
                ?: return AppResult.Failure(AppError.Validation("${row.itemCode}: select a warehouse", ErrorCode.SELECT_WAREHOUSE_FOR_ITEM, listOf(row.itemCode)))
            val batches = line.batches.filter { it.batchNo.isNotBlank() && it.qty > QTY_TOLERANCE }
            if (batches.any { it.qty < 0.0 }) {
                return AppResult.Failure(AppError.Validation("${row.itemCode}: quantity must be positive", ErrorCode.QTY_MUST_BE_POSITIVE))
            }
            val batch = batches.firstOrNull()?.batchNo ?: line.batchNo?.trim()?.ifBlank { null } ?: row.batchNo
            if (row.needsBatch && batch == null) {
                return AppResult.Failure(AppError.Validation("${row.itemCode}: scan or enter the batch", ErrorCode.BATCH_REQUIRED_FOR_ITEM, listOf(row.itemCode)))
            }
            val qty = if (batches.isNotEmpty()) batches.sumOf { it.qty } else line.qty
            counts += ReceiptCount(rowName = row.rowName, qty = qty, warehouse = warehouse, batchNo = batch, batches = batches)
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

/**
 * The batches a scanned or typed batch number stands for: by ERPNext name or by the number the
 * supplier printed (spaces and case ignored), among the item's batches or those of the receipt's items.
 */
class FindBatchUseCase @Inject constructor(private val repository: ReceiptRepository) {
    suspend operator fun invoke(code: String, itemCode: String? = null, receipt: String? = null): AppResult<BatchLookup> {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return AppResult.Failure(AppError.Validation("Scan or enter a batch number", ErrorCode.BATCH_REQUIRED_FOR_ITEM, listOf(itemCode.orEmpty())))
        return repository.findBatch(trimmed, itemCode?.trim()?.ifBlank { null }, receipt?.trim()?.ifBlank { null })
    }
}

/**
 * Creates the batch of a supplier number for an item whose batches ERPNext does not create itself.
 * Dates are ISO `yyyy-MM-dd`; an expiry left empty is taken from the item's shelf life by ERPNext.
 */
class CreateBatchUseCase @Inject constructor(private val repository: ReceiptRepository) {
    suspend operator fun invoke(batch: NewBatch): AppResult<CreatedBatch> {
        val batchNo = batch.batchNo.trim()
        if (batchNo.isEmpty()) return AppResult.Failure(AppError.Validation("Scan or enter a batch number", ErrorCode.BATCH_REQUIRED_FOR_ITEM, listOf(batch.itemCode)))
        for (date in listOfNotNull(batch.manufacturingDate, batch.expiryDate).filter { it.isNotBlank() }) {
            if (!ISO_DATE.matches(date.trim())) return AppResult.Failure(AppError.Validation("$date is not a date (yyyy-MM-dd)", ErrorCode.INVALID_DATE, listOf(date)))
        }
        val mfg = batch.manufacturingDate?.trim()?.ifBlank { null }
        val expiry = batch.expiryDate?.trim()?.ifBlank { null }
        if (mfg != null && expiry != null && expiry < mfg) {
            return AppResult.Failure(AppError.Validation("Expiry $expiry is before manufacturing date $mfg", ErrorCode.EXPIRY_BEFORE_MANUFACTURING, listOf(expiry, mfg)))
        }
        return repository.createBatch(batch.copy(batchNo = batchNo, qcNo = batch.qcNo?.trim()?.ifBlank { null }, manufacturingDate = mfg, expiryDate = expiry))
    }

    companion object {
        private val ISO_DATE = Regex("""\d{4}-\d{2}-\d{2}""")
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
