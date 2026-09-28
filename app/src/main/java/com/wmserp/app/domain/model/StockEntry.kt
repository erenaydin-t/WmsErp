package com.wmserp.app.domain.model

/** Maps to the ERPNext `Stock Entry` DocType. */
enum class StockEntryType(val erpName: String) {
    MATERIAL_RECEIPT("Material Receipt"),
    MATERIAL_ISSUE("Material Issue"),
    MATERIAL_TRANSFER("Material Transfer");

    companion object {
        fun fromErpName(value: String?): StockEntryType? = entries.firstOrNull { it.erpName == value }
    }
}

data class StockEntryItem(
    val itemCode: String,
    val qty: Double,
    val sourceWarehouse: String? = null,
    val targetWarehouse: String? = null,
    val uom: String? = null,
)

data class StockEntry(
    val name: String? = null,
    val type: StockEntryType,
    val items: List<StockEntryItem>,
    val postingDate: String? = null,
    val docStatus: Int = 0,
    val remarks: String? = null,
)
