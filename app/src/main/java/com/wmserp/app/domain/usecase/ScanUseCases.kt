package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.model.WmsQrKeys
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.repository.ReceiptRepository
import javax.inject.Inject

/** Cleans raw scanner output (control chars, GS1 separators, whitespace). */
object ScanCodeSanitizer {
    fun sanitize(raw: String): String = raw
        .filter { it.code >= 32 || it == '\t' }
        .replace("\u001D", "")
        .trim()
}

/**
 * Resolves a scanned code against ERPNext according to the selected [ScanTarget].
 *
 * The *Item / Batch* target understands every label the warehouse prints: a WMS JSON QR label
 * (item, or item + batch), an item barcode, an item code, or a plain batch number. A label that
 * names a batch answers with the batch (expiry, item, stock per warehouse), so scanning the label
 * of a specific batch finds that batch and not only its item.
 */
class LookupScanUseCase @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val receiptRepository: ReceiptRepository,
    private val pickListRepository: PickListRepository,
) {
    suspend operator fun invoke(rawCode: String, target: ScanTarget): AppResult<ScanLookup> {
        val code = ScanCodeSanitizer.sanitize(rawCode)
        if (code.isEmpty()) return AppResult.Failure(AppError.Validation("Empty barcode", ErrorCode.EMPTY_BARCODE))

        return when (target) {
            ScanTarget.ITEM -> lookupItemOrBatch(code)

            ScanTarget.WAREHOUSE -> inventoryRepository.getWarehouse(code).flatMap { warehouse ->
                if (warehouse == null) {
                    AppResult.Success(ScanLookup.NotFound(code, target))
                } else {
                    val stock = inventoryRepository.getWarehouseStock(warehouse.name).getOrNull().orEmpty()
                    AppResult.Success(ScanLookup.WarehouseFound(code, warehouse, stock))
                }
            }

            ScanTarget.PURCHASE_RECEIPT -> receiptRepository.getPurchaseReceipt(code).map { receipt ->
                if (receipt == null) ScanLookup.NotFound(code, target) else ScanLookup.PurchaseReceiptFound(code, receipt)
            }
        }
    }

    private suspend fun lookupItemOrBatch(code: String): AppResult<ScanLookup> {
        // 1. A WMS JSON label: the item is known, the batch (when printed) is looked up as such.
        val keys = pickListRepository.getQrKeys().getOrNull() ?: WmsQrKeys.DEFAULT
        val label = (QrLabelParser.parse(code, keys) as? QrParseResult.Valid)?.label
        if (label != null) {
            if (label.batchNo != null) {
                val batch = inventoryRepository.getBatch(label.batchNo).getOrNull()
                if (batch != null) return batchFound(code, batch)
            }
            return inventoryRepository.findItemByBarcode(label.itemCode).flatMap { item ->
                if (item == null) AppResult.Success(ScanLookup.NotFound(code, ScanTarget.ITEM)) else itemFound(code, item)
            }
        }
        // 2. An item barcode or item code.
        val item = when (val result = inventoryRepository.findItemByBarcode(code)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }
        if (item != null) return itemFound(code, item)
        // 3. A plain batch number (what a supplier's or an older label carries).
        return inventoryRepository.getBatch(code).flatMap { batch ->
            if (batch == null) AppResult.Success(ScanLookup.NotFound(code, ScanTarget.ITEM)) else batchFound(code, batch)
        }
    }

    private suspend fun itemFound(code: String, item: Item): AppResult<ScanLookup> {
        val stock = inventoryRepository.getStockLevels(item.code).getOrNull().orEmpty()
        return AppResult.Success(ScanLookup.ItemFound(code, item, stock))
    }

    private suspend fun batchFound(code: String, batch: Batch): AppResult<ScanLookup> {
        val item = if (batch.itemCode.isBlank()) null else inventoryRepository.getItem(batch.itemCode).getOrNull()
        val stock = inventoryRepository.getBatchStock(batch.name).getOrNull().orEmpty()
        return AppResult.Success(ScanLookup.BatchFound(code, batch, item, stock))
    }
}
