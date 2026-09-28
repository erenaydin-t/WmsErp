package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockEntryItem
import com.wmserp.app.domain.model.StockEntryType
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.repository.InventoryRepository
import javax.inject.Inject

class SearchItemsUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(query: String, limit: Int = 20): AppResult<List<Item>> =
        inventoryRepository.searchItems(query.trim(), limit)
}

class GetItemStockUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(itemCode: String): AppResult<List<StockLevel>> =
        inventoryRepository.getStockLevels(itemCode)
}

class SearchWarehousesUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(query: String = "", limit: Int = 30): AppResult<List<Warehouse>> =
        inventoryRepository.searchWarehouses(query.trim(), limit)
}

/** Creates a Material Transfer / Receipt / Issue stock entry after validating the input. */
class CreateStockEntryUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(
        type: StockEntryType,
        itemCode: String,
        qty: Double,
        sourceWarehouse: String?,
        targetWarehouse: String?,
        submit: Boolean = true,
        remarks: String? = null,
    ): AppResult<StockEntry> {
        if (itemCode.isBlank()) return AppResult.Failure(AppError.Validation("Item is required"))
        if (qty <= 0.0) return AppResult.Failure(AppError.Validation("Quantity must be greater than zero"))
        val source = sourceWarehouse?.trim()?.ifBlank { null }
        val target = targetWarehouse?.trim()?.ifBlank { null }
        when (type) {
            StockEntryType.MATERIAL_TRANSFER -> {
                if (source == null || target == null) {
                    return AppResult.Failure(AppError.Validation("Source and target warehouses are required"))
                }
                if (source == target) return AppResult.Failure(AppError.Validation("Source and target must differ"))
            }
            StockEntryType.MATERIAL_RECEIPT -> if (target == null) {
                return AppResult.Failure(AppError.Validation("Target warehouse is required"))
            }
            StockEntryType.MATERIAL_ISSUE -> if (source == null) {
                return AppResult.Failure(AppError.Validation("Source warehouse is required"))
            }
        }
        val entry = StockEntry(
            type = type,
            items = listOf(
                StockEntryItem(
                    itemCode = itemCode.trim(),
                    qty = qty,
                    sourceWarehouse = if (type == StockEntryType.MATERIAL_RECEIPT) null else source,
                    targetWarehouse = if (type == StockEntryType.MATERIAL_ISSUE) null else target,
                )
            ),
            remarks = remarks,
        )
        return inventoryRepository.createStockEntry(entry, submit)
    }
}
