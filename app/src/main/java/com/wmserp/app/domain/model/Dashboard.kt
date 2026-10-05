package com.wmserp.app.domain.model

/** KPI values shown on the dashboard. Counts only: warehouse users see no monetary data. */
data class DashboardKpis(
    val totalItems: Int,
    val pendingOrders: Int,
    /** Purchase Receipts submitted this month. */
    val receipts: Int,
    /** Delivery Notes submitted this month. */
    val dispatched: Int,
    val periodLabel: String,
)

/** One row of the recent stock activity feed (ERPNext `Stock Ledger Entry`). */
data class ActivityEntry(
    val id: String,
    val itemCode: String,
    val itemName: String?,
    val warehouse: String,
    val qtyChange: Double,
    val voucherType: String,
    val voucherNo: String,
    val postingDate: String,
    val postingTime: String,
)

/** KPI values shown on the inventory / analytics screen. */
data class InventoryKpis(
    val pendingDeliveries: Int,
    val receipts: Int,
    val pickLists: Int,
)

data class DeliveryDelay(
    val orderName: String,
    val customerName: String,
    val deliveryDate: String,
    val daysLate: Int,
    val status: String,
)

data class DelayBucket(val label: String, val count: Int)

data class DeliveryDelayReport(
    val buckets: List<DelayBucket>,
    val orders: List<DeliveryDelay>,
) {
    val totalOverdue: Int get() = orders.size
    val averageDaysLate: Double get() = if (orders.isEmpty()) 0.0 else orders.sumOf { it.daysLate }.toDouble() / orders.size
}

/** Stock movement intensity per day (rows) and hour block (columns). */
data class ActivityHeatmap(
    val dayLabels: List<String>,
    val blockLabels: List<String>,
    val cells: List<List<Int>>,
) {
    val maxValue: Int get() = cells.maxOfOrNull { row -> row.maxOrNull() ?: 0 } ?: 0
    val total: Int get() = cells.sumOf { it.sum() }
}

data class StockAgingRow(
    val itemCode: String,
    val itemName: String,
    val warehouse: String?,
    val averageAge: Double,
    val qtyByRange: List<Double>,
) {
    val totalQty: Double get() = qtyByRange.sum()
}

data class StockAgingReport(
    val rangeLabels: List<String>,
    val rows: List<StockAgingRow>,
) {
    val totalsByRange: List<Double>
        get() = rangeLabels.indices.map { i -> rows.sumOf { it.qtyByRange.getOrElse(i) { 0.0 } } }
}

enum class AnalyticsSection { KPIS, DELIVERY_DELAYS, ACTIVITY_HEATMAP, STOCK_AGING }

data class AnalyticsError(val section: AnalyticsSection, val error: com.wmserp.app.domain.common.AppError)

data class InventoryAnalytics(
    val kpis: InventoryKpis,
    val delays: DeliveryDelayReport?,
    val heatmap: ActivityHeatmap?,
    val aging: StockAgingReport?,
    val errors: List<AnalyticsError> = emptyList(),
)
