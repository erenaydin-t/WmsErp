package com.wmserp.app.data.repository

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PickListRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: PickListRepositoryImpl

    private val pickListJson = """
        {"name":"STO-PICK-2026-00012","purpose":"Delivery","company":"WM Co","customer":"CUST-001","customer_name":"Globex",
         "parent_warehouse":"Stores - WM","target_warehouse":null,"status":"Open","picking_status":"Picking","picker":"u@x.com",
         "assigned_to":["u@x.com"],"picking_started_at":"2026-09-28 09:12:00.000000","picking_started_by":"u@x.com",
         "picking_completed_at":null,"picking_completed_by":null,"generated_doctype":null,"generated_docname":null,
         "work_order":null,"material_request":null,"modified":"2026-09-28 09:12:00.000000","item_count":2,"required_qty":15,"picked_qty":9,
         "items":[
           {"name":"prow1","idx":1,"item_code":"ITEM-001","item_name":"Steel Bolt M8","warehouse":"Stores - WM","target_warehouse":null,
            "batch_no":null,"expiry_date":null,"serial_no":null,"required_qty":10,"picked_qty":4,"uom":"Nos","order_qty":10,"order_uom":"Nos",
            "conversion_factor":1,"has_batch_no":0,"has_serial_no":0,"optional":0,"barcodes":["8690000000017"],"sales_order":"SAL-ORD-2026-00001","row_status":"Partial"},
           {"name":"prow2","idx":2,"item_code":"ITEM-002","item_name":"Steel Nut M8","warehouse":"Stores - WM","target_warehouse":null,
            "batch_no":"B-001","expiry_date":"2027-01-31","serial_no":null,"required_qty":5,"picked_qty":5,"uom":"Nos","order_qty":5,"order_uom":"Nos",
            "conversion_factor":1.0,"has_batch_no":1,"has_serial_no":0,"optional":1,"barcodes":[],"sales_order":null,"row_status":"Picked"}
         ]}
    """.trimIndent()

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = PickListRepositoryImpl(harness.dataSource, harness.apiCaller)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `getMyPickLists reads the whitelisted method and maps the summaries`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":[{"name":"STO-PICK-2026-00012","purpose":"Material Transfer","customer":null,"customer_name":null,
                   "picking_status":"Ready to Pick","picker":"u@x.com","item_count":2,"required_qty":15,"picked_qty":0,
                   "generated_doctype":null,"generated_docname":null,"modified":"2026-09-28 09:12:00"}]}"""
            )
        )

        val result = repository.getMyPickLists()

        val list = (result as AppResult.Success).data
        assertEquals(1, list.size)
        assertEquals(PickListPurpose.MATERIAL_TRANSFER, list[0].purpose)
        assertEquals(PickingStatus.READY_TO_PICK, list[0].pickingStatus)
        assertEquals(2, list[0].itemCount)
        assertEquals(15.0, list[0].requiredQty, 0.0)
        assertNull(list[0].generatedDocument)

        val request = harness.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/method/wmserp_picking.api.pick_list.get_my_pick_lists", request.path)
    }

    @Test
    fun `saveProgress posts the rows as JSON and maps the returned document`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":$pickListJson}"""))

        val result = repository.saveProgress("STO-PICK-2026-00012", listOf(PickProgressLine("prow1", 4.0), PickProgressLine("prow2", 5.0, "B-001")))

        val pickList = (result as AppResult.Success).data
        assertEquals(PickingStatus.PICKING, pickList.pickingStatus)
        assertEquals("Globex", pickList.customerName)
        assertEquals(2, pickList.items.size)
        assertEquals(9.0, pickList.pickedQty, 0.0)
        assertEquals(15.0, pickList.requiredQty, 0.0)
        assertEquals(PickRowStatus.PARTIAL, pickList.items[0].status)
        assertEquals(listOf("8690000000017"), pickList.items[0].barcodes)
        assertEquals("B-001", pickList.items[1].batchNo)
        assertEquals("2027-01-31", pickList.items[1].expiryDate)
        assertTrue(pickList.items[1].hasBatchNo)
        assertTrue(pickList.items[1].optional)
        assertEquals(PickRowStatus.PICKED, pickList.items[1].status)
        assertNull(pickList.generatedDocument)

        val request = harness.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/method/wmserp_picking.api.pick_list.save_progress", request.path)
        val body = harness.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("STO-PICK-2026-00012", body["name"]!!.jsonPrimitive.content)
        val items = body["items"]!!.jsonArray
        assertEquals(2, items.size)
        assertEquals("prow1", items[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals(4.0, items[0].jsonObject["picked_qty"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertNull(items[0].jsonObject["batch_no"])
        assertEquals("B-001", items[1].jsonObject["batch_no"]!!.jsonPrimitive.content)
    }

    @Test
    fun `generateDocument maps the duplicate prevention flag`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody("""{"message":{"doctype":"Stock Entry","name":"MAT-STE-00009","docstatus":0,"already_generated":true,"documents":[{"doctype":"Stock Entry","name":"MAT-STE-00009"}]}}""")
        )

        val result = repository.generateDocument("STO-PICK-2026-00012")

        val doc = (result as AppResult.Success).data
        assertEquals("Stock Entry", doc.doctype)
        assertEquals("MAT-STE-00009", doc.name)
        assertTrue(doc.alreadyGenerated)
        assertEquals("/api/method/wmserp_picking.api.pick_list.generate_document", harness.server.takeRequest().path)
    }

    @Test
    fun `server side validation messages are surfaced verbatim`() = runTest {
        harness.server.enqueue(
            MockResponse().setResponseCode(417).setBody(
                """{"exc_type":"ValidationError","_server_messages":"[\"{\\\"message\\\": \\\"Picking is incomplete: ITEM-001 (4/10)\\\"}\"]"}"""
            )
        )

        val result = repository.completePicking("STO-PICK-2026-00012", listOf(PickProgressLine("prow1", 4.0)))

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation)
        assertNull(error.code)
        assertTrue(error.message.contains("Picking is incomplete"))
    }

    @Test
    fun `resolveScan maps the match type`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"row_name":"prow2","item_code":"ITEM-002","batch_no":"B-001","match":"batch","expiry_date":"2027-01-31"}}"""))

        val match = (repository.resolveScan("STO-PICK-2026-00012", "B-001") as AppResult.Success).data

        assertEquals("prow2", match.rowName)
        assertEquals("B-001", match.batchNo)
        assertEquals(com.wmserp.app.domain.model.PickScanMatchType.BATCH, match.matchType)
    }
}
