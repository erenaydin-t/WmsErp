package com.wmserp.app.domain.model

/** Maps to the ERPNext `Item` DocType. */
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
    val valuationRate: Double? = null,
    val standardRate: Double? = null,
    val brand: String? = null,
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
