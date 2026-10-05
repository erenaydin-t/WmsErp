package com.wmserp.app.domain.model

/** Maps to the ERPNext `Item` DocType (no prices or valuation: the app shows no monetary data). */
data class Item(
    val code: String,
    val name: String,
    val group: String? = null,
    val stockUom: String? = null,
    val description: String? = null,
    val barcodes: List<String> = emptyList(),
    val imageUrl: String? = null,
    val disabled: Boolean = false,
    val isStockItem: Boolean = true,
    val brand: String? = null,
    val hasBatchNo: Boolean = false,
    val hasSerialNo: Boolean = false,
)

/** Maps to the ERPNext `Bin` DocType (stock per item per warehouse). */
data class StockLevel(
    val itemCode: String,
    val warehouse: String,
    val actualQty: Double,
    val reservedQty: Double = 0.0,
    val orderedQty: Double = 0.0,
    val projectedQty: Double = actualQty,
    val itemName: String? = null,
    val stockUom: String? = null,
) {
    val availableQty: Double get() = actualQty - reservedQty
}

/** Maps to the ERPNext `Batch` DocType. */
data class Batch(
    val name: String,
    val itemCode: String,
    val itemName: String? = null,
    /** ISO date (`yyyy-MM-dd`) or null when the batch does not expire. */
    val expiryDate: String? = null,
    val manufacturingDate: String? = null,
    val disabled: Boolean = false,
    val stockUom: String? = null,
    val supplier: String? = null,
    val description: String? = null,
) {
    /** Expired strictly before [today] (ISO date). */
    fun isExpiredOn(today: String): Boolean = expiryDate != null && expiryDate < today
}

/** Stock of one batch in one warehouse (`get_batch_qty(batch_no)`), in the item's stock UOM. */
data class BatchWarehouseStock(
    val warehouse: String,
    val qty: Double,
)
