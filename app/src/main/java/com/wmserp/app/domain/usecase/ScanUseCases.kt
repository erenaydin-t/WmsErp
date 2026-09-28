package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ScanLookup
import com.wmserp.app.domain.model.ScanTarget
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.OrderRepository
import javax.inject.Inject

/** Cleans raw scanner output (control chars, GS1 separators, whitespace). */
object ScanCodeSanitizer {
    fun sanitize(raw: String): String = raw
        .filter { it.code >= 32 || it == '\t' }
        .replace("\u001D", "")
        .trim()
}

/** Resolves a scanned barcode against ERPNext according to the selected [ScanTarget]. */
class LookupScanUseCase @Inject constructor(
    private val inventoryRepository: InventoryRepository,
    private val orderRepository: OrderRepository,
) {
    suspend operator fun invoke(rawCode: String, target: ScanTarget): AppResult<ScanLookup> {
        val code = ScanCodeSanitizer.sanitize(rawCode)
        if (code.isEmpty()) return AppResult.Failure(AppError.Validation("Empty barcode"))

        return when (target) {
            ScanTarget.ITEM -> inventoryRepository.findItemByBarcode(code).flatMap { item ->
                if (item == null) {
                    AppResult.Success(ScanLookup.NotFound(code, target))
                } else {
                    val stock = inventoryRepository.getStockLevels(item.code).getOrNull().orEmpty()
                    AppResult.Success(ScanLookup.ItemFound(code, item, stock))
                }
            }

            ScanTarget.WAREHOUSE -> inventoryRepository.getWarehouse(code).flatMap { warehouse ->
                if (warehouse == null) {
                    AppResult.Success(ScanLookup.NotFound(code, target))
                } else {
                    val stock = inventoryRepository.getWarehouseStock(warehouse.name).getOrNull().orEmpty()
                    AppResult.Success(ScanLookup.WarehouseFound(code, warehouse, stock))
                }
            }

            ScanTarget.PURCHASE_ORDER -> orderRepository.getPurchaseOrder(code).map { po ->
                if (po == null) ScanLookup.NotFound(code, target) else ScanLookup.PurchaseOrderFound(code, po)
            }
        }
    }
}
