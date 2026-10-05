package com.wmserp.app.data.repository

import com.wmserp.app.data.remote.dto.StockLedgerEntryDto
import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DeliveryDelay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class AnalyticsRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private val fixedDate = object : DateProvider {
        override fun today(): LocalDate = LocalDate.of(2026, 9, 28)
    }
    private lateinit var repository: AnalyticsRepositoryImpl

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = AnalyticsRepositoryImpl(harness.dataSource, harness.apiCaller, fixedDate)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `bucketize groups delays by days late`() {
        val delays = listOf(1, 3, 4, 9, 15, 40).map { DeliveryDelay("SO-$it", "C", "2026-09-01", it, "To Deliver") }
        val buckets = AnalyticsRepositoryImpl.bucketize(delays)
        assertEquals(listOf(2, 1, 1, 2), buckets.map { it.count })
        assertEquals("15+ days", buckets.last().label)
    }

    @Test
    fun `buildHeatmap places entries in day rows and three hour blocks`() {
        val start = LocalDate.of(2026, 9, 22)
        val entries = listOf(
            StockLedgerEntryDto("1", "A", "W", 1.0, postingDate = "2026-09-22", postingTime = "08:15:00"),
            StockLedgerEntryDto("2", "A", "W", 1.0, postingDate = "2026-09-22", postingTime = "08:59:59.123456"),
            StockLedgerEntryDto("3", "A", "W", 1.0, postingDate = "2026-09-28", postingTime = "23:10:00"),
            StockLedgerEntryDto("4", "A", "W", 1.0, postingDate = "2026-10-05", postingTime = "01:00:00"),
        )
        val heatmap = AnalyticsRepositoryImpl.buildHeatmap(entries, start, 7)
        assertEquals(7, heatmap.cells.size)
        assertEquals(8, heatmap.cells.first().size)
        assertEquals(2, heatmap.cells[0][2])
        assertEquals(1, heatmap.cells[6][7])
        assertEquals(3, heatmap.total)
    }

    @Test
    fun `parseStockAging handles list rows using column metadata`() {
        val message = Json.parseToJsonElement(
            """{"columns":[{"fieldname":"item_code","label":"Item Code"},{"fieldname":"item_name","label":"Item Name"},
                {"fieldname":"average_age","label":"Average Age"},{"fieldname":"range1","label":"Age (0 - 30)"},
                {"fieldname":"range2","label":"Age (31 - 60)"},{"fieldname":"range3","label":"Age (61 - 90)"},{"fieldname":"range4","label":"Age (91 - Above)"}],
                "result":[["A","Alpha",12.5,10,0,0,0],["B","Beta",120,0,0,0,7],["","Totals",0,10,0,0,7]]}"""
        )
        val report = AnalyticsRepositoryImpl.parseStockAging(message, limit = 10)
        assertEquals(listOf("0 - 30", "31 - 60", "61 - 90", "91 - Above"), report.rangeLabels)
        assertEquals(listOf("B", "A"), report.rows.map { it.itemCode })
        assertEquals(listOf(10.0, 0.0, 0.0, 7.0), report.totalsByRange)
    }

    @Test
    fun `dashboard kpis count documents only and tolerate partial failures`() = runTest {
        // Five counts happen concurrently; use a dispatcher that answers by path.
        harness.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                val path = java.net.URLDecoder.decode(request.path ?: "", "UTF-8")
                return when {
                    path.contains("get_count") && path.contains("doctype=Item") -> MockResponse().setBody("""{"message":42}""")
                    path.contains("get_count") && path.contains("Purchase Order") -> MockResponse().setBody("""{"message":3}""")
                    path.contains("get_count") && path.contains("Sales Order") -> MockResponse().setBody("""{"message":4}""")
                    path.contains("get_count") && path.contains("Purchase Receipt") -> MockResponse().setBody("""{"message":5}""")
                    path.contains("get_count") && path.contains("Delivery Note") -> MockResponse().setResponseCode(403).setBody("""{"exc_type":"PermissionError"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val result = repository.getDashboardKpis()

        val kpis = (result as AppResult.Success).data
        assertEquals(42, kpis.totalItems)
        assertEquals(7, kpis.pendingOrders)
        assertEquals(5, kpis.receipts)
        assertEquals(0, kpis.dispatched)
        assertEquals("Sep 2026", kpis.periodLabel)
        // No request ever touches invoices or amounts.
        val paths = (0 until harness.server.requestCount).map { java.net.URLDecoder.decode(harness.server.takeRequest().path ?: "", "UTF-8") }
        assertEquals(5, paths.size)
        assertTrue(paths.none { it.contains("Sales Invoice") || it.contains("grand_total") })
    }

    @Test
    fun `stock ageing filters carry both the v14 range fields and the v15 range string`() {
        val filters = AnalyticsRepositoryImpl.stockAgingFilters("WM Co", LocalDate.of(2026, 9, 28))
        assertEquals("WM Co", filters["company"]!!.jsonPrimitive.content)
        assertEquals("2026-09-28", filters["to_date"]!!.jsonPrimitive.content)
        assertEquals("30, 60, 90", filters["range"]!!.jsonPrimitive.content)
        assertEquals("30", filters["range1"]!!.jsonPrimitive.content)
        assertEquals("60", filters["range2"]!!.jsonPrimitive.content)
        assertEquals("90", filters["range3"]!!.jsonPrimitive.content)
        assertEquals("0", filters["show_warehouse_wise_stock"]!!.jsonPrimitive.content)
    }

    @Test
    fun `parseStockAging ignores the value columns of v15 and v16 and reads dict rows`() {
        val message = Json.parseToJsonElement(
            """{"columns":[{"fieldname":"item_code","label":"Item Code"},{"fieldname":"item_name","label":"Item Name"},{"fieldname":"average_age","label":"Average Age"},
                {"fieldname":"range1","label":"Age (0 - 30)"},{"fieldname":"range1value","label":"Age (0 - 30) Value"},
                {"fieldname":"range2","label":"Age (31 - 60)"},{"fieldname":"range2value","label":"Age (31 - 60) Value"},
                {"fieldname":"range3","label":"Age (61 - 90)"},{"fieldname":"range3value","label":"Age (61 - 90) Value"},
                {"fieldname":"range4","label":"Age (91 - Above)"},{"fieldname":"range4value","label":"Age (91 - Above) Value"}],
                "result":[{"item_code":"A","item_name":"Alpha","average_age":12.5,"range1":10,"range1value":1000,"range2":0,"range2value":0,"range3":0,"range3value":0,"range4":0,"range4value":0},
                          {"item_code":"B","item_name":"Beta","average_age":120,"range1":0,"range1value":0,"range2":0,"range2value":0,"range3":0,"range3value":0,"range4":7,"range4value":7000}]}"""
        )
        val report = AnalyticsRepositoryImpl.parseStockAging(message, limit = 10)
        assertEquals(listOf("0 - 30", "31 - 60", "61 - 90", "91 - Above"), report.rangeLabels)
        assertEquals(listOf(10.0, 0.0, 0.0, 0.0), report.rows.first { it.itemCode == "A" }.qtyByRange)
        assertEquals(listOf(10.0, 0.0, 0.0, 7.0), report.totalsByRange)
    }
}
