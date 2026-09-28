package com.wmserp.app.data.repository

import com.wmserp.app.data.local.SessionStore
import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.mapper.toDto
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.BinDto
import com.wmserp.app.data.remote.dto.ItemDto
import com.wmserp.app.data.remote.dto.StockEntryDto
import com.wmserp.app.data.remote.dto.StockLedgerEntryDto
import com.wmserp.app.data.remote.dto.WarehouseDto
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ActivityEntry
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.Warehouse
import com.wmserp.app.domain.repository.InventoryRepository

class InventoryRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val sessionStore: SessionStore,
    private val apiCaller: ApiCaller,
) : InventoryRepository {

    private val baseUrl: String? get() = sessionStore.current.baseUrl

    override suspend fun findItemByBarcode(code: String): AppResult<Item?> = apiCaller.call {
        val byBarcode = dataSource.getList<ItemDto>(
            doctype = ITEM,
            fields = ITEM_LIST_FIELDS,
            filters = listOf(Filter("barcode", "=", code, doctype = ITEM_BARCODE)),
            limit = 1,
        ).firstOrNull()
        val name = byBarcode?.name ?: code
        // Fetch the full document so child barcodes are included; also covers "barcode == item code".
        dataSource.getDocOrNull<ItemDto>(ITEM, name)?.toDomain(baseUrl)
    }

    override suspend fun getItem(itemCode: String): AppResult<Item> = apiCaller.call {
        dataSource.getDoc<ItemDto>(ITEM, itemCode).toDomain(baseUrl)
    }

    override suspend fun searchItems(query: String, limit: Int): AppResult<List<Item>> = apiCaller.call {
        val orFilters = if (query.isBlank()) emptyList() else listOf(
            Filter.like("item_code", query),
            Filter.like("item_name", query),
            Filter("barcode", "like", "%$query%", doctype = ITEM_BARCODE),
        )
        dataSource.getList<ItemDto>(
            doctype = ITEM,
            fields = ITEM_LIST_FIELDS,
            filters = listOf(Filter.eq("disabled", 0)),
            orFilters = orFilters,
            orderBy = if (query.isBlank()) "modified desc" else "item_name asc",
            limit = limit,
        ).map { it.toDomain(baseUrl) }
    }

    override suspend fun getStockLevels(itemCode: String): AppResult<List<StockLevel>> = apiCaller.call {
        dataSource.getList<BinDto>(
            doctype = BIN,
            fields = BIN_FIELDS,
            filters = listOf(Filter.eq("item_code", itemCode)),
            orderBy = "actual_qty desc",
            limit = 100,
        ).map { it.toDomain() }
    }

    override suspend fun getWarehouse(name: String): AppResult<Warehouse?> = apiCaller.call {
        dataSource.getDocOrNull<WarehouseDto>(WAREHOUSE, name)?.toDomain()
            ?: dataSource.getList<WarehouseDto>(
                doctype = WAREHOUSE,
                fields = WAREHOUSE_FIELDS,
                filters = listOf(Filter.eq("warehouse_name", name)),
                limit = 1,
            ).firstOrNull()?.toDomain()
    }

    override suspend fun searchWarehouses(query: String, limit: Int): AppResult<List<Warehouse>> = apiCaller.call {
        val orFilters = if (query.isBlank()) emptyList() else listOf(
            Filter.like("name", query),
            Filter.like("warehouse_name", query),
        )
        dataSource.getList<WarehouseDto>(
            doctype = WAREHOUSE,
            fields = WAREHOUSE_FIELDS,
            filters = listOf(Filter.eq("is_group", 0), Filter.eq("disabled", 0)),
            orFilters = orFilters,
            orderBy = "name asc",
            limit = limit,
        ).map { it.toDomain() }
    }

    override suspend fun getWarehouseStock(warehouse: String, limit: Int): AppResult<List<StockLevel>> = apiCaller.call {
        val bins = dataSource.getList<BinDto>(
            doctype = BIN,
            fields = BIN_FIELDS,
            filters = listOf(Filter.eq("warehouse", warehouse), Filter.gt("actual_qty", 0)),
            orderBy = "actual_qty desc",
            limit = limit,
        )
        val names = itemNames(bins.map { it.itemCode })
        bins.map { it.toDomain(itemName = names[it.itemCode]) }
    }

    override suspend fun createStockEntry(entry: StockEntry, submit: Boolean): AppResult<StockEntry> = apiCaller.call {
        val inserted = dataSource.insert<StockEntryDto, StockEntryDto>(STOCK_ENTRY, entry.toDto())
        if (submit && inserted.name != null) {
            val submitted = dataSource.submit(STOCK_ENTRY, inserted.name)
            dataSource.json.decodeFromJsonElement(StockEntryDto.serializer(), submitted).toDomain()
        } else {
            inserted.toDomain()
        }
    }

    override suspend fun getRecentActivity(limit: Int): AppResult<List<ActivityEntry>> = apiCaller.call {
        val entries = dataSource.getList<StockLedgerEntryDto>(
            doctype = STOCK_LEDGER_ENTRY,
            fields = SLE_FIELDS,
            filters = listOf(Filter.eq("is_cancelled", 0)),
            orderBy = "posting_date desc, posting_time desc, creation desc",
            limit = limit,
        )
        val names = itemNames(entries.map { it.itemCode })
        entries.map { it.toDomain(names[it.itemCode]) }
    }

    /** Batch-resolves item names for a set of item codes (Bin / SLE rows do not carry them). */
    private suspend fun itemNames(codes: List<String>): Map<String, String> {
        val distinct = codes.distinct().take(MAX_NAME_LOOKUP)
        if (distinct.isEmpty()) return emptyMap()
        return try {
            dataSource.getList<ItemDto>(
                doctype = ITEM,
                fields = listOf("name", "item_name"),
                filters = listOf(Filter.inList("name", distinct)),
                limit = distinct.size,
            ).associate { it.name to (it.itemName ?: it.name) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            emptyMap()
        }
    }

    companion object {
        const val ITEM = "Item"
        const val ITEM_BARCODE = "Item Barcode"
        const val BIN = "Bin"
        const val WAREHOUSE = "Warehouse"
        const val STOCK_ENTRY = "Stock Entry"
        const val STOCK_LEDGER_ENTRY = "Stock Ledger Entry"
        private const val MAX_NAME_LOOKUP = 100

        val ITEM_LIST_FIELDS = listOf(
            "name", "item_code", "item_name", "item_group", "stock_uom", "description", "image",
            "disabled", "is_stock_item", "valuation_rate", "standard_rate", "brand",
        )
        val BIN_FIELDS = listOf("name", "item_code", "warehouse", "actual_qty", "reserved_qty", "ordered_qty", "projected_qty", "stock_uom")
        val WAREHOUSE_FIELDS = listOf("name", "warehouse_name", "company", "is_group", "parent_warehouse", "disabled", "warehouse_type", "city")
        val SLE_FIELDS = listOf("name", "item_code", "warehouse", "actual_qty", "voucher_type", "voucher_no", "posting_date", "posting_time")
    }
}
