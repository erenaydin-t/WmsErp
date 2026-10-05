package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.data.remote.dto.StockLedgerEntryDto
import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.ActivityHeatmap
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.model.DelayBucket
import com.wmserp.app.domain.model.DeliveryDelay
import com.wmserp.app.domain.model.DeliveryDelayReport
import com.wmserp.app.domain.model.InventoryKpis
import com.wmserp.app.domain.model.StockAgingReport
import com.wmserp.app.domain.model.StockAgingRow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Operational figures only: the dashboard and the analytics screen count documents and
 * quantities and never read amounts, rates or valuations (warehouse users see no monetary data).
 */
class AnalyticsRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
    private val dateProvider: DateProvider,
) : com.wmserp.app.domain.repository.AnalyticsRepository {

    override suspend fun getDashboardKpis(): AppResult<DashboardKpis> = coroutineScope {
        val today = dateProvider.today()
        val monthStart = today.withDayOfMonth(1)

        val items = async { apiCaller.call { dataSource.getCount("Item", listOf(Filter.eq("disabled", 0))) } }
        val pendingPo = async {
            apiCaller.call { dataSource.getCount("Purchase Order", listOf(Filter.eq("docstatus", 1), Filter.inList("status", PO_OPEN_STATUSES))) }
        }
        val pendingSo = async {
            apiCaller.call { dataSource.getCount("Sales Order", listOf(Filter.eq("docstatus", 1), Filter.inList("status", SO_OPEN_STATUSES))) }
        }
        val receipts = async {
            apiCaller.call {
                dataSource.getCount("Purchase Receipt", listOf(Filter.eq("docstatus", 1), Filter.gte("posting_date", monthStart.toString())))
            }
        }
        val dispatched = async {
            apiCaller.call {
                dataSource.getCount("Delivery Note", listOf(Filter.eq("docstatus", 1), Filter.gte("posting_date", monthStart.toString())))
            }
        }

        val results = listOf(items.await(), pendingPo.await(), pendingSo.await(), receipts.await(), dispatched.await())
        results.firstOrNull { it is AppResult.Failure && it.error is AppError.Unauthorized }?.let {
            return@coroutineScope AppResult.Failure((it as AppResult.Failure).error)
        }
        if (results.all { it is AppResult.Failure }) {
            return@coroutineScope AppResult.Failure((results.first() as AppResult.Failure).error)
        }

        AppResult.Success(
            DashboardKpis(
                totalItems = items.await().getOrNull() ?: 0,
                pendingOrders = (pendingPo.await().getOrNull() ?: 0) + (pendingSo.await().getOrNull() ?: 0),
                receipts = receipts.await().getOrNull() ?: 0,
                dispatched = dispatched.await().getOrNull() ?: 0,
                periodLabel = today.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + " " + today.year,
            )
        )
    }

    override suspend fun getInventoryKpis(): AppResult<InventoryKpis> = coroutineScope {
        val today = dateProvider.today()
        val weekAgo = today.minusDays(7)
        val deliveries = async {
            apiCaller.call { dataSource.getCount("Sales Order", listOf(Filter.eq("docstatus", 1), Filter.inList("status", SO_OPEN_STATUSES))) }
        }
        val receipts = async {
            apiCaller.call { dataSource.getCount("Purchase Receipt", listOf(Filter.eq("docstatus", 1), Filter.gte("posting_date", weekAgo.toString()))) }
        }
        val pickLists = async {
            apiCaller.call { dataSource.getCount("Pick List", listOf(Filter.lt("docstatus", 2), Filter.inList("status", listOf("Draft", "Open")))) }
        }
        val results = listOf(deliveries.await(), receipts.await(), pickLists.await())
        results.firstOrNull { it is AppResult.Failure && it.error is AppError.Unauthorized }?.let {
            return@coroutineScope AppResult.Failure((it as AppResult.Failure).error)
        }
        if (results.all { it is AppResult.Failure }) {
            return@coroutineScope AppResult.Failure((results.first() as AppResult.Failure).error)
        }
        AppResult.Success(
            InventoryKpis(
                pendingDeliveries = deliveries.await().getOrNull() ?: 0,
                receipts = receipts.await().getOrNull() ?: 0,
                pickLists = pickLists.await().getOrNull() ?: 0,
            )
        )
    }

    override suspend fun getDeliveryDelays(limit: Int): AppResult<DeliveryDelayReport> = apiCaller.call {
        val today = dateProvider.today()
        val orders = dataSource.getList<SalesOrderDto>(
            doctype = "Sales Order",
            fields = SO_LIST_FIELDS,
            filters = listOf(
                Filter.eq("docstatus", 1),
                Filter.inList("status", SO_OPEN_STATUSES),
                Filter.lt("delivery_date", today.toString()),
            ),
            orderBy = "delivery_date asc",
            limit = limit,
        ).map { it.toDomain() }

        val delays = orders.mapNotNull { so ->
            val date = so.deliveryDate?.let { parseDate(it) } ?: return@mapNotNull null
            DeliveryDelay(
                orderName = so.name,
                customerName = so.customerName,
                deliveryDate = so.deliveryDate,
                daysLate = ChronoUnit.DAYS.between(date, today).toInt().coerceAtLeast(1),
                status = so.status,
            )
        }.sortedByDescending { it.daysLate }
        DeliveryDelayReport(buckets = bucketize(delays), orders = delays)
    }

    override suspend fun getActivityHeatmap(days: Int): AppResult<ActivityHeatmap> = apiCaller.call {
        val today = dateProvider.today()
        val start = today.minusDays((days - 1).toLong())
        val entries = dataSource.getList<StockLedgerEntryDto>(
            doctype = "Stock Ledger Entry",
            fields = listOf("name", "item_code", "warehouse", "actual_qty", "voucher_type", "voucher_no", "posting_date", "posting_time"),
            filters = listOf(Filter.eq("is_cancelled", 0), Filter.gte("posting_date", start.toString())),
            orderBy = "posting_date asc",
            limit = HEATMAP_MAX_ROWS,
        )
        buildHeatmap(entries, start, days)
    }

    /**
     * ERPNext's *Stock Ageing* script report. Its filters changed across versions: v14 takes
     * `range1` / `range2` / `range3`, v15 and v16 a single `range` ("30, 60, 90") and crash on the
     * old names, so both shapes are sent (each version ignores the other's).
     */
    override suspend fun getStockAging(limit: Int): AppResult<StockAgingReport> = apiCaller.call {
        val today = dateProvider.today()
        val company = dataSource.getSingleValue("Global Defaults", "default_company")
            ?: dataSource.getList<CompanyName>("Company", listOf("name"), limit = 1).firstOrNull()?.name
            ?: throw AppException(AppError.Validation("No company configured in ERPNext", ErrorCode.NO_COMPANY_CONFIGURED))
        val message = dataSource.runReport(STOCK_AGEING_REPORT, stockAgingFilters(company, today))
        parseStockAging(message, limit)
    }

    // ---- helpers -------------------------------------------------------------------------

    @kotlinx.serialization.Serializable
    private data class CompanyName(val name: String)

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()

    companion object {
        const val STOCK_AGEING_REPORT = "Stock Ageing"
        private const val HEATMAP_MAX_ROWS = 2000
        private const val HOURS_PER_BLOCK = 3
        private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        val AGEING_RANGES = listOf(30, 60, 90)

        val PO_OPEN_STATUSES = listOf("To Receive and Bill", "To Receive")
        val SO_OPEN_STATUSES = listOf("To Deliver and Bill", "To Deliver")
        val SO_LIST_FIELDS = listOf("name", "customer", "customer_name", "status", "transaction_date", "delivery_date", "per_delivered", "company", "docstatus")

        /** Filters accepted by every ERPNext version (see [getStockAging]). */
        fun stockAgingFilters(company: String, today: LocalDate): JsonObject = buildJsonObject {
            put("company", company)
            put("to_date", today.toString())
            put("range", AGEING_RANGES.joinToString(", "))
            AGEING_RANGES.forEachIndexed { index, days -> put("range${index + 1}", days) }
            put("show_warehouse_wise_stock", 0)
        }

        fun bucketize(delays: List<DeliveryDelay>): List<DelayBucket> {
            val ranges = listOf("1-3 days" to 1..3, "4-7 days" to 4..7, "8-14 days" to 8..14)
            val buckets = ranges.map { (label, range) -> DelayBucket(label, delays.count { it.daysLate in range }) }
            return buckets + DelayBucket("15+ days", delays.count { it.daysLate >= 15 })
        }

        fun buildHeatmap(entries: List<StockLedgerEntryDto>, start: LocalDate, days: Int): ActivityHeatmap {
            val blocks = 24 / HOURS_PER_BLOCK
            val cells = MutableList(days) { MutableList(blocks) { 0 } }
            entries.forEach { e ->
                val date = e.postingDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return@forEach
                val dayIndex = ChronoUnit.DAYS.between(start, date).toInt()
                if (dayIndex !in 0 until days) return@forEach
                val hour = e.postingTime?.let { parseHour(it) } ?: 0
                val block = (hour / HOURS_PER_BLOCK).coerceIn(0, blocks - 1)
                cells[dayIndex][block] = cells[dayIndex][block] + 1
            }
            val dayLabels = (0 until days).map { start.plusDays(it.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) }
            val blockLabels = (0 until blocks).map { String.format(Locale.US, "%02d", it * HOURS_PER_BLOCK) }
            return ActivityHeatmap(dayLabels = dayLabels, blockLabels = blockLabels, cells = cells)
        }

        fun parseHour(time: String): Int? {
            val clean = time.trim().take(8)
            return runCatching { java.time.LocalTime.parse(if (clean.length == 5) "$clean:00" else clean, TIME_FORMAT).hour }
                .getOrElse { clean.substringBefore(':').toIntOrNull() }
        }

        /**
         * Parses `frappe.desk.query_report.run` output for the Stock Ageing report. Only the quantity
         * columns (`range1`, `range2`...) are read; the `range1value`... amount columns are ignored.
         */
        fun parseStockAging(message: JsonElement?, limit: Int): StockAgingReport {
            val obj = message as? JsonObject ?: return StockAgingReport(emptyList(), emptyList())
            val columns = (obj["columns"] as? JsonArray).orEmpty()
            val columnNames = columns.mapIndexed { index, col ->
                when (col) {
                    is JsonObject -> col["fieldname"]?.asString() ?: "col$index"
                    is JsonPrimitive -> col.content.substringBefore(':').trim().lowercase().replace(' ', '_')
                    else -> "col$index"
                }
            }
            val rangeFields = columnNames.filter { QTY_RANGE_COLUMN.matches(it) }.sortedBy { it.removePrefix("range").toInt() }
            val rangeLabels = rangeFields.map { field ->
                val idx = columnNames.indexOf(field)
                (columns.getOrNull(idx) as? JsonObject)?.get("label")?.asString()?.let { cleanLabel(it) } ?: field
            }
            val rows = (obj["result"] as? JsonArray).orEmpty().mapNotNull { row ->
                val map: Map<String, JsonElement> = when (row) {
                    is JsonObject -> row
                    is JsonArray -> row.mapIndexed { i, v -> (columnNames.getOrNull(i) ?: "col$i") to v }.toMap()
                    else -> return@mapNotNull null
                }
                val itemCode = map["item_code"]?.asString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                StockAgingRow(
                    itemCode = itemCode,
                    itemName = map["item_name"]?.asString()?.takeIf { it.isNotBlank() } ?: itemCode,
                    warehouse = map["warehouse"]?.asString(),
                    averageAge = map["average_age"]?.asDouble() ?: 0.0,
                    qtyByRange = rangeFields.map { map[it]?.asDouble() ?: 0.0 },
                )
            }.filter { it.totalQty > 0.0 }
                .sortedByDescending { it.averageAge }
                .take(limit)
            return StockAgingReport(rangeLabels = rangeLabels, rows = rows)
        }

        private val QTY_RANGE_COLUMN = Regex("range\\d+")

        private fun cleanLabel(label: String): String = label.replace("Age (", "").replace(")", "").trim()

        private fun JsonElement.asString(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        private fun JsonElement.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.toDoubleOrNull()
    }
}
