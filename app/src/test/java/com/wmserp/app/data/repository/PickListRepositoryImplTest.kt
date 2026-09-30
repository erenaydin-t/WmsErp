package com.wmserp.app.data.repository

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.WmsQrKeys
import kotlinx.coroutines.test.runTest
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

class PickListRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: PickListRepositoryImpl

    private val pickListJson = """
        {"name":"STO-PICK-2026-00012","purpose":"Delivery","company":"WM Co","customer":"CUST-001","customer_name":"Globex",
         "parent_warehouse":"Stores - WM","target_warehouse":null,"status":"Open","picking_status":"Picking",
         "card_started_at":"2026-09-28 09:12:00.000000","card_completed_at":null,"generated_doctype":null,"generated_docname":null,
         "work_order":null,"material_request":null,"modified":"2026-09-28 09:12:00.000000","creation":"2026-09-28 08:00:00.000000",
         "item_count":3,"picked_rows":1,"required_qty":18,"picked_qty":9,"my_row_count":2,"my_picked_rows":1,"my_open_rows":1,"all_rows_picked":false,
         "items":[
           {"name":"prow1","idx":1,"item_code":"ITEM-001","item_name":"Steel Bolt M8","warehouse":"Stores - WM","target_warehouse":null,
            "batch_no":"B-001","expiry_date":"2027-01-31","serial_no":null,"required_qty":10,"picked_qty":4,"uom":"Nos","order_qty":10,"order_uom":"Nos",
            "conversion_factor":1,"has_batch_no":1,"sales_order":"SAL-ORD-2026-00001","picker":"u@x.com","is_mine":true,"row_status":"Picking",
            "row_started_at":"2026-09-28 09:12:00.000000","row_completed_at":null,"duration_seconds":null},
           {"name":"prow2","idx":2,"item_code":"ITEM-002","item_name":"Steel Nut M8","warehouse":"Stores - WM","target_warehouse":null,
            "batch_no":null,"expiry_date":null,"serial_no":null,"required_qty":5,"picked_qty":5,"uom":"Nos","order_qty":5,"order_uom":"Nos",
            "conversion_factor":1.0,"has_batch_no":0,"sales_order":null,"picker":"u@x.com","is_mine":true,"row_status":"Picked",
            "row_started_at":"2026-09-28 09:00:00.000000","row_completed_at":"2026-09-28 09:03:20.000000","duration_seconds":200.0},
           {"name":"prow3","idx":3,"item_code":"ITEM-003","item_name":"Washer","warehouse":"Stores - WM","target_warehouse":null,
            "batch_no":"B-777","expiry_date":null,"serial_no":null,"required_qty":3,"picked_qty":0,"uom":"Nos","order_qty":3,"order_uom":"Nos",
            "conversion_factor":1,"has_batch_no":1,"sales_order":null,"picker":"o@x.com","is_mine":false,"row_status":"Not Picked",
            "row_started_at":null,"row_completed_at":null,"duration_seconds":null}
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
    fun `QR keys come from get_settings and are cached`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"qr_item_key":"sku","qr_batch_key":"lot","app_version":"0.2.0"}}"""))

        val first = (repository.getQrKeys() as AppResult.Success).data
        val second = (repository.getQrKeys() as AppResult.Success).data

        assertEquals(WmsQrKeys("sku", "lot"), first)
        assertEquals(first, second)
        assertEquals(1, harness.server.requestCount)
        assertEquals("/api/method/wmserp_picking.api.pick_list.get_settings", harness.server.takeRequest().path)
    }

    @Test
    fun `getMyPickLists reads the whitelisted method and maps the task summaries`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":[{"name":"STO-PICK-2026-00012","purpose":"Material Transfer","customer":null,"customer_name":null,
                   "picking_status":"Ready to Pick","item_count":3,"picked_rows":0,"required_qty":18,"picked_qty":0,
                   "my_row_count":2,"my_picked_rows":0,"my_open_rows":2,"all_rows_picked":false,
                   "generated_doctype":null,"generated_docname":null,"modified":"2026-09-28 09:12:00"}]}"""
            )
        )

        val list = (repository.getMyPickLists() as AppResult.Success).data

        assertEquals(1, list.size)
        assertEquals(PickListPurpose.MATERIAL_TRANSFER, list[0].purpose)
        assertEquals(PickingStatus.READY_TO_PICK, list[0].pickingStatus)
        assertEquals(2, list[0].myRowCount)
        assertEquals(2, list[0].myOpenRows)
        assertFalse(list[0].allRowsPicked)
        val request = harness.server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/method/wmserp_picking.api.pick_list.get_my_pick_lists", request.path)
    }

    @Test
    fun `saveRowProgress posts the row, quantity, label and elapsed time and maps the row update`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"pick_list":$pickListJson,"row":null,"row_completed":false,"card_completed":false,"is_last_picker":false}}"""))

        val result = repository.saveRowProgress("STO-PICK-2026-00012", "prow1", 4.0, "ITEM-001", "B-001", 37.5)

        val update = (result as AppResult.Success).data
        assertFalse(update.rowCompleted)
        assertFalse(update.isLastPicker)
        val pickList = update.pickList
        assertEquals(PickingStatus.PICKING, pickList.pickingStatus)
        assertEquals(3, pickList.items.size)
        assertEquals(listOf("prow1", "prow2"), pickList.myItems.map { it.rowName })
        assertEquals("B-001", pickList.items[0].batchNo)
        assertEquals(PickRowStatus.PICKING, pickList.items[0].rowStatus)
        assertEquals(PickRowStatus.PICKED, pickList.items[1].rowStatus)
        assertEquals(200.0, pickList.items[1].durationSeconds!!, 0.0)
        assertEquals("u@x.com", pickList.items[0].picker)
        assertFalse(pickList.items[2].isMine)
        assertNull(pickList.generatedDocument)

        val request = harness.server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/method/wmserp_picking.api.pick_list.save_row_progress", request.path)
        val body = harness.json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("STO-PICK-2026-00012", body["name"]!!.jsonPrimitive.content)
        assertEquals("prow1", body["row"]!!.jsonPrimitive.content)
        assertEquals(4.0, body["picked_qty"]!!.jsonPrimitive.content.toDouble(), 0.0)
        assertEquals("ITEM-001", body["item_code"]!!.jsonPrimitive.content)
        assertEquals("B-001", body["batch_no"]!!.jsonPrimitive.content)
        assertEquals(37.5, body["elapsed_seconds"]!!.jsonPrimitive.content.toDouble(), 0.0)
    }

    @Test
    fun `completeRow surfaces the last picker flag`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":{"pick_list":$pickListJson,"row":null,"row_completed":true,"card_completed":true,"is_last_picker":true}}"""))

        val update = (repository.completeRow("STO-PICK-2026-00012", "prow1", 10.0, null, "B-001", 300.0) as AppResult.Success).data

        assertTrue(update.rowCompleted)
        assertTrue(update.cardCompleted)
        assertTrue(update.isLastPicker)
        assertEquals("/api/method/wmserp_picking.api.pick_list.complete_row", harness.server.takeRequest().path)
    }

    @Test
    fun `picker KPIs are mapped with nullable averages`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":{"date":"2026-09-28","user":"u@x.com","rows_picked":12,"qty_picked":140,"pick_lists_touched":3,"pick_lists_completed":1,
                   "open_rows":4,"open_pick_lists":2,"total_seconds":1800,"avg_seconds_per_row":150.0,"fastest_seconds":40,"slowest_seconds":400,"rows_per_hour":24.0}}"""
            )
        )

        val kpis = (repository.getPickerKpis() as AppResult.Success).data

        assertEquals(12, kpis.rowsPicked)
        assertEquals(140.0, kpis.qtyPicked, 0.0)
        assertEquals(150.0, kpis.avgSecondsPerRow!!, 0.0)
        assertEquals(24.0, kpis.rowsPerHour!!, 0.0)
        assertEquals(4, kpis.openRows)
        assertEquals("/api/method/wmserp_picking.api.pick_list.get_picker_kpis", harness.server.takeRequest().path)
    }

    @Test
    fun `server side validation messages are surfaced verbatim`() = runTest {
        harness.server.enqueue(
            MockResponse().setResponseCode(417).setBody(
                """{"exc_type":"ValidationError","_server_messages":"[\"{\\\"message\\\": \\\"Wrong batch. Expected: B-001, scanned: B-2\\\"}\"]"}"""
            )
        )

        val result = repository.saveRowProgress("STO-PICK-2026-00012", "prow1", 1.0, "ITEM-001", "B-2", null)

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation)
        assertNull(error.code)
        assertTrue(error.message.contains("Wrong batch"))
    }
}
