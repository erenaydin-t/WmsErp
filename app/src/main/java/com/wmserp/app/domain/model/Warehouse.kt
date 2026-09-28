package com.wmserp.app.domain.model

/** Maps to the ERPNext `Warehouse` DocType. */
data class Warehouse(
    val name: String,
    val warehouseName: String,
    val company: String? = null,
    val isGroup: Boolean = false,
    val parentWarehouse: String? = null,
    val disabled: Boolean = false,
    val warehouseType: String? = null,
    val city: String? = null,
)
