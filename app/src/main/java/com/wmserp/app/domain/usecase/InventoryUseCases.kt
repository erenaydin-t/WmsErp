package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.CreatedTransfer
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.TransferForm
import com.wmserp.app.domain.model.TransferRequest
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.repository.InventoryRepository
import com.wmserp.app.domain.repository.SettingsRepository
import com.wmserp.app.domain.repository.StockTransferRepository
import kotlinx.coroutines.flow.first
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

/** What the scan-menu transfer sheet must ask on this site (Department, Received By, batch handling). */
class GetTransferFormUseCase @Inject constructor(private val repository: StockTransferRepository) {
    suspend operator fun invoke(itemCode: String): AppResult<TransferForm> = repository.getTransferForm(itemCode.trim())
}

/**
 * Creates the scan-menu Material Transfer as a **draft** on the server (department, receiving
 * employee, batches and the site's required fields handled there). Remembered answers to other
 * required fields are sent along; the user's own choices win.
 */
class CreateStockTransferUseCase @Inject constructor(
    private val repository: StockTransferRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(request: TransferRequest, fieldValues: Map<String, String> = emptyMap()): AppResult<CreatedTransfer> {
        if (request.itemCode.isBlank()) return AppResult.Failure(AppError.Validation("Item is required", ErrorCode.ITEM_REQUIRED))
        if (request.qty <= 0.0) return AppResult.Failure(AppError.Validation("Quantity must be greater than zero", ErrorCode.QTY_MUST_BE_POSITIVE))
        val source = request.sourceWarehouse.trim()
        val target = request.targetWarehouse.trim()
        if (source.isEmpty() || target.isEmpty()) {
            return AppResult.Failure(AppError.Validation("Source and target warehouses are required", ErrorCode.WAREHOUSES_REQUIRED))
        }
        if (source == target) return AppResult.Failure(AppError.Validation("Source and target must differ", ErrorCode.WAREHOUSES_MUST_DIFFER))
        val values = settingsRepository.documentFieldDefaults.first() + fieldValues
        return repository.createTransfer(request.copy(itemCode = request.itemCode.trim(), sourceWarehouse = source, targetWarehouse = target), values)
    }
}
