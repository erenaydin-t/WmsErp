package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.Warehouse

interface InventoryRepository {
    /** Resolves an item by barcode (Item Barcode child table) or falling back to the item code. */
    suspend fun findItemByBarcode(code: String): AppResult<Item?>
    suspend fun getItem(itemCode: String): AppResult<Item>
    suspend fun searchItems(query: String, limit: Int = 20): AppResult<List<Item>>
    suspend fun getStockLevels(itemCode: String): AppResult<List<StockLevel>>

    suspend fun getWarehouse(name: String): AppResult<Warehouse?>
    suspend fun searchWarehouses(query: String, limit: Int = 30): AppResult<List<Warehouse>>
    suspend fun getWarehouseStock(warehouse: String, limit: Int = 50): AppResult<List<StockLevel>>

    suspend fun createStockEntry(entry: StockEntry, submit: Boolean): AppResult<StockEntry>
    suspend fun getRecentActivity(limit: Int = 10): AppResult<List<ActivityEntry>>
}
