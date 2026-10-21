package com.wmserp.app.data.repository

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountRejection
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.DuplicateCountPolicy
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.WmsQrKeys
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StocktakingRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: StocktakingRepositoryImpl

    private val sessionJson = """
        {"name":"ST-2026-0001","warehouse":"Main - C","warehouse_name":"Main Warehouse","warehouses":["Main - C"],"company":"WM Co",
         "posting_date":"2026-09-30","status":"Counting","counting_mode":"Open","blind_count":false,"duplicate_count_policy":"Allow additional counts",
         "require_second_count":true,"qty_tolerance":0.5,"freeze_warehouse":true,"frozen":true,"started_at":"2026-09-30 08:00:00","stock_reconciliation":null,
         "totals":{"total_items":5000,"counted_items":4620,"uncounted_items":380,"matched_items":4200,"variance_items":420,"recount_required":75,"pending_review":75,"approved_items":0,"qty_variance":-12.5,"value_variance":-50000},
         "my":{"assigned":0,"open":0,"items_counted":300,"counts":312},"can_count":true,"is_supervisor":false,"counters":["u@x.com"],
         "qr_item_key":"sku","qr_batch_key":"lot","modified":"2026-09-30 10:00:00"}
    """.trimIndent()

    private val itemJson = """
        {"name":"r1","session":"ST-2026-0001","item_code":"PCT-500","item_name":"Paracetamol 500mg","warehouse":"Main - C","batch_no":"PCT-250901",
         "expiry_date":"2028-09-01","stock_uom":"Nos","erp_qty":100,"valuation_rate":10,"item_group":"Analgesics","brand":null,"has_batch_no":true,
         "location":"A-01","added_during_count":false,"status":"Recount Required","next_count_type":"Count 2","counter":"u@x.com","counter_name":"Eren",
         "is_mine":true,"count_1":95,"count_2":null,"recount_qty":null,"recount_count":0,"final_qty":null,"qty_difference":0,"value_difference":0,
         "counted_by":"u@x.com","counted_by_name":"Eren","counted_at":"2026-09-30 10:32:00","recount_note":null,"modified":"2026-09-30 10:32:00"}
    """.trimIndent()

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = StocktakingRepositoryImpl(harness.dataSource, harness.apiCaller)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `getSession maps the header, options, totals and QR keys`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":$sessionJson}"""))

        val session = (repository.getSession("ST-2026-0001") as AppResult.Success).data

        assertEquals(StocktakingStatus.COUNTING, session.status)
        assertEquals(CountingMode.OPEN, session.mode)
        assertEquals(DuplicateCountPolicy.ALLOW, session.duplicatePolicy)
        assertEquals(0.5, session.qtyTolerance, 0.0)
        assertEquals(WmsQrKeys("sku", "lot"), session.qrKeys)
        assertEquals(5000, session.totals.total)
        assertEquals(380, session.totals.uncounted)
        assertEquals(-50000.0, session.totals.valueVariance)
        assertEquals(312, session.my.counts)
        assertTrue(session.canCount)
        assertFalse(session.mineOnly)
        val request = harness.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/method/wmserp_picking.api.stocktaking.get_session?name=ST-2026-0001", request.path)
    }

    @Test
    fun `getItems maps rows and barcodes and asks for the caller's rows only when told`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"items":[$itemJson],"barcodes":{"PCT-500":["8690000000017"]},"start":0,"limit":1000,"total":1,"session_status":"Counting"}}"""))

        val page = (repository.getItems("ST-2026-0001", 0, 1000, mineOnly = true) as AppResult.Success).data

        assertEquals(1, page.total)
        val row = page.items.single()
        assertEquals(CountItemStatus.RECOUNT_REQUIRED, row.status)
        assertEquals(CountType.COUNT_2, row.nextCountType)
        assertEquals(95.0, row.count1)
        assertNull(row.finalQty)
        assertEquals("A-01", row.location)
        assertTrue(row.isMine)
        assertEquals(listOf("8690000000017"), page.barcodes["PCT-500"])
        assertEquals("/api/method/wmserp_picking.api.stocktaking.get_items?name=ST-2026-0001&start=0&limit=1000&mine=1", harness.server.takeRequest().path)
    }

    @Test
    fun `submitCount posts the count with its client reference and maps the outcome`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"outcome":"second_count_required","item":$itemJson,"count":"c1","totals":{"total_items":5000,"counted_items":4620},"session_status":"Counting"}}"""))
        val submission = CountSubmission("ref-1", "ST-2026-0001", "r1", "PCT-500", "PCT-250901", "Main - C", 95.0, "2026-09-30 10:32:00")

        val result = (repository.submitCount(submission) as AppResult.Success).data

        assertEquals(CountOutcome.SECOND_COUNT_REQUIRED, result.outcome)
        assertEquals("r1", result.item?.name)
        assertEquals(4620, result.totals?.counted)
        assertFalse(result.isRejected)
        val request = harness.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/method/wmserp_picking.api.stocktaking.submit_count", request.path)
        val body = harness.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("ST-2026-0001", body["name"]!!.jsonPrimitive.content)
        assertEquals("r1", body["item"]!!.jsonPrimitive.content)
        assertEquals("95.0", body["qty"]!!.jsonPrimitive.content)
        assertEquals("ref-1", body["client_ref"]!!.jsonPrimitive.content)
        assertEquals("2026-09-30 10:32:00", body["device_time"]!!.jsonPrimitive.content)
        assertEquals("App", body["source"]!!.jsonPrimitive.content)
    }

    @Test
    fun `syncCounts replays the queue and maps rejections per entry`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":{"results":[
                    {"client_ref":"ref-1","outcome":"accepted","item":$itemJson,"totals":{"total_items":5000,"counted_items":4621}},
                    {"client_ref":"ref-2","outcome":"rejected","reason":"already_counted","message":"ASP-100 has already been counted by Reza.","counted_by":"reza@x.com","counted_by_name":"Reza"},
                    {"client_ref":"ref-3","outcome":"error","reason":"validation","message":"Item NOPE does not exist in ERPNext."}
                  ],"totals":{"total_items":5000,"counted_items":4621},"session_status":"Counting"}}"""
            )
        )
        val a = CountSubmission("ref-1", "ST-2026-0001", "r1", "PCT-500", "PCT-250901", null, 100.0, "2026-09-30 10:00:00")
        val b = CountSubmission("ref-2", "ST-2026-0001", null, "ASP-100", "ASP-1", "Main - C", 12.0, "2026-09-30 10:01:00")
        val c = CountSubmission("ref-3", "ST-2026-0001", null, "NOPE", null, null, 1.0, "2026-09-30 10:02:00")

        val outcome = (repository.syncCounts("ST-2026-0001", listOf(a, b, c)) as AppResult.Success).data

        assertEquals(listOf(CountOutcome.ACCEPTED, CountOutcome.REJECTED, CountOutcome.ERROR), outcome.results.map { it.outcome })
        assertEquals(CountRejection.ALREADY_COUNTED, outcome.results[1].rejection)
        assertEquals("Reza", outcome.results[1].countedByName)
        assertEquals(CountRejection.VALIDATION, outcome.results[2].rejection)
        assertTrue(outcome.results[2].isRejected)
        assertEquals(4621, outcome.totals?.counted)
        val request = harness.server.takeRequest()
        assertEquals("/api/method/wmserp_picking.api.stocktaking.sync_counts", request.path)
        val body = harness.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val counts = body["counts"]!!.jsonArray
        assertEquals(3, counts.size)
        assertEquals("ref-2", counts[1].jsonObject["client_ref"]!!.jsonPrimitive.content)
        assertNull(counts[1].jsonObject["item"])
        assertEquals("Main - C", counts[1].jsonObject["warehouse"]!!.jsonPrimitive.content)
    }

    @Test
    fun `lookup maps an item that is not in the session and network failures stay typed`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"found":true,"raw":"8690000000099","kind":"barcode","item_code":"NEW-1","item_name":"New product","batch_no":null,"has_batch_no":false,"stock_uom":"Nos","can_add":true,"in_session":false,"rows":[]}}"""))

        val lookup = (repository.lookup("ST-2026-0001", "8690000000099") as AppResult.Success).data
        assertTrue(lookup.found)
        assertEquals("NEW-1", lookup.itemCode)
        assertTrue(lookup.canAdd)
        assertFalse(lookup.inSession)
        assertEquals("/api/method/wmserp_picking.api.stocktaking.lookup?name=ST-2026-0001&code=8690000000099", harness.server.takeRequest().path)

        harness.shutdown()
        val failure = repository.getMySessions() as AppResult.Failure
        assertTrue(failure.error is AppError.Network)
    }
}
