package com.wmserp.app.data.repository

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ReceiptCount
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
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
import java.net.URLDecoder

class ReceiptRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: ReceiptRepositoryImpl

    private val receiptJson = """
        {"name":"MAT-PRE-2026-00001","supplier":"SUP-001","supplier_name":"Acme Supplies","posting_date":"2026-09-28","company":"WM Co",
         "set_warehouse":"Stores - WM","status":"Draft","workflow_state":"Warehouse","docstatus":0,"supplier_delivery_note":"DN-778",
         "item_count":2,"total_qty":15,"can_receive":true,"has_workflow":true,
         "items":[
           {"name":"row1","idx":1,"item_code":"ITEM-001","item_name":"Steel Bolt M8","qty":10,"received_qty":10,"uom":"Nos","stock_uom":"Nos","conversion_factor":1,
            "warehouse":"Stores - WM","batch_no":null,"has_batch_no":true,"has_serial_no":false,"needs_batch":true,"purchase_order":"PUR-ORD-2026-00001","barcodes":["8690000000017"]},
           {"name":"row2","idx":2,"item_code":"ITEM-002","item_name":"Steel Nut M8","qty":5,"received_qty":5,"uom":"Nos","stock_uom":"Nos","conversion_factor":1.0,
            "warehouse":"Stores - WM","batch_no":null,"has_batch_no":false,"has_serial_no":false,"needs_batch":false,"purchase_order":"PUR-ORD-2026-00001","barcodes":[]}
         ]}
    """.trimIndent()

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = ReceiptRepositoryImpl(harness.dataSource, harness.apiCaller)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `receivable receipts come from the whitelisted method and carry no amounts`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":[$receiptJson]}"""))

        val result = repository.getReceivableReceipts(query = "acme")

        val receipt = (result as AppResult.Success).data.single()
        assertEquals("MAT-PRE-2026-00001", receipt.name)
        assertEquals("Acme Supplies", receipt.supplierName)
        assertEquals("Warehouse", receipt.workflowState)
        assertEquals(15.0, receipt.totalQty, 0.0)
        assertTrue(receipt.canReceive)
        val path = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(path.startsWith("/api/method/wmserp_picking.api.purchase_receipt.get_receivable?"))
        assertTrue(path.contains("query=acme"))
        assertTrue(path.contains("limit=50"))
    }

    @Test
    fun `a receipt is loaded with its rows and a missing one is null`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":$receiptJson}"""))
        harness.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"exc_type":"DoesNotExistError"}"""))

        val found = (repository.getPurchaseReceipt("MAT-PRE-2026-00001") as AppResult.Success).data!!
        val missing = (repository.getPurchaseReceipt("MAT-PRE-2026-00009") as AppResult.Success).data

        assertEquals(2, found.items.size)
        val bolt = found.items.first()
        assertTrue(bolt.hasBatchNo)
        assertTrue(bolt.needsBatch)
        assertEquals(listOf("8690000000017"), bolt.barcodes)
        assertTrue(bolt.matches("8690000000017"))
        assertNull(missing)
    }

    @Test
    fun `receive posts the counts, the answers and the submit flags, and maps the outcome`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":${receiptJson.trimEnd('}')},"created":true,"submitted":true,"docstatus":1,"workflow_state":"Received",
                   "differences":[{"row":"row2","item_code":"ITEM-002","expected":5,"counted":3}],"removed_rows":[]}}"""
            )
        )

        val result = repository.receive(
            name = "MAT-PRE-2026-00001",
            counts = listOf(ReceiptCount("row1", 10.0, "Stores - WM", "LOT-1"), ReceiptCount("row2", 3.0)),
            fieldValues = mapOf("Purchase Receipt.department" to "Warehouse - WM", "Purchase Receipt.cost_center" to ""),
            submit = true,
        )

        val outcome = (result as AppResult.Success).data
        assertTrue(outcome.submitted)
        assertEquals(1, outcome.differences.size)
        assertEquals(3.0, outcome.differences.single().counted, 0.0)
        assertTrue(outcome.receipt.isSubmitted)

        val request = harness.server.takeRequest()
        assertEquals("/api/method/wmserp_picking.api.purchase_receipt.receive", request.path)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("MAT-PRE-2026-00001", body["name"]!!.jsonPrimitive.content)
        assertEquals("1", body["submit"]!!.jsonPrimitive.content)
        assertEquals("1", body["remove_unreceived"]!!.jsonPrimitive.content)
        val rows = body["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals("LOT-1", rows[0].jsonObject["batch_no"]!!.jsonPrimitive.content)
        assertEquals("Stores - WM", rows[0].jsonObject["warehouse"]!!.jsonPrimitive.content)
        assertNull(rows[1].jsonObject["batch_no"])
        assertEquals("Warehouse - WM", body["values"]!!.jsonObject["Purchase Receipt.department"]!!.jsonPrimitive.content)
        assertNull(body["values"]!!.jsonObject["Purchase Receipt.cost_center"])
    }

    @Test
    fun `saving progress does not drop the rows that were not counted`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":${receiptJson.trimEnd('}')},"created":true,"submitted":false,"differences":[],"removed_rows":[]}}"""))

        val result = repository.receive("MAT-PRE-2026-00001", listOf(ReceiptCount("row2", 2.0)), submit = false)

        assertFalse((result as AppResult.Success).data.submitted)
        val body = Json.parseToJsonElement(harness.server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("0", body["submit"]!!.jsonPrimitive.content)
        assertEquals("0", body["remove_unreceived"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a missing_fields answer becomes the required fields error`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":{"doctype":"Purchase Receipt","created":false,
                   "missing_fields":[{"doctype":"Purchase Receipt Item","fieldname":"department","label":"Department","fieldtype":"Link","options":"Department"}]}}"""
            )
        )

        val result = repository.receive("MAT-PRE-2026-00001", listOf(ReceiptCount("row2", 5.0)))

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.MissingRequiredFields)
        val field = (error as AppError.MissingRequiredFields).fields.single()
        assertEquals("Purchase Receipt Item.department", field.key)
        assertEquals("Department", field.options)
        assertTrue(field.isLink)
    }

    @Test
    fun `server side refusals are surfaced verbatim`() = runTest {
        harness.server.enqueue(
            MockResponse().setResponseCode(417).setBody(
                """{"exc_type":"ValidationError","_server_messages":"[\"{\\\"message\\\": \\\"ITEM-001: this item is batch tracked, scan or enter its batch\\\"}\"]"}"""
            )
        )

        val result = repository.receive("MAT-PRE-2026-00001", listOf(ReceiptCount("row1", 10.0)))

        val error = (result as AppResult.Failure).error
        assertTrue(error is AppError.Validation)
        assertTrue(error.message.contains("batch tracked"))
    }

    @Test
    fun `link values are scoped to the company first and fall back to the plain list`() = runTest {
        harness.server.enqueue(MockResponse().setResponseCode(417).setBody("""{"exc_type":"ValidationError","_server_messages":"[\"{\\\"message\\\": \\\"Field not permitted in query: company\\\"}\"]"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"Sales - WM"},{"name":"Warehouse - WM"}]}"""))

        val result = repository.searchLinkValues("Department", "", "WM Co")

        assertEquals(listOf("Sales - WM", "Warehouse - WM"), (result as AppResult.Success).data)
        val scoped = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(scoped.contains("""["company","=","WM Co"]"""))
        val plain = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertFalse(plain.contains("company"))
    }
}
