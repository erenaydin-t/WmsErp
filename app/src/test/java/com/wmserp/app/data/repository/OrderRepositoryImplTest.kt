package com.wmserp.app.data.repository

import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.BatchAllocation
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.DeliveryNoteLine
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.PurchaseReceiptLine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
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
import java.time.LocalDate

class OrderRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private val fixedDate = object : DateProvider {
        override fun today(): LocalDate = LocalDate.of(2026, 9, 28)
    }
    private lateinit var repository: OrderRepositoryImpl

    /** What `make_purchase_receipt` returns: the pending rows with everything ERPNext copies from the order. */
    private val mappedReceipt = """
        {"message":{"doctype":"Purchase Receipt","name":null,"__islocal":1,"supplier":"SUP-001","company":"WM Co","set_warehouse":"Stores - WM",
          "naming_series":"MAT-PRE-.YYYY.-","currency":"USD",
          "items":[
            {"doctype":"Purchase Receipt Item","name":null,"idx":1,"item_code":"ITEM-001","qty":10,"stock_qty":10,"conversion_factor":1,"rate":12.5,
             "warehouse":"Stores - WM","purchase_order":"PUR-ORD-2026-00001","purchase_order_item":"row1","department":"Warehouse - WM","cost_center":"Main - WM"},
            {"doctype":"Purchase Receipt Item","name":null,"idx":2,"item_code":"ITEM-002","qty":3,"stock_qty":3,"conversion_factor":1,"rate":4,
             "warehouse":"Stores - WM","purchase_order":"PUR-ORD-2026-00001","purchase_order_item":"row2","department":"Warehouse - WM"}
          ],
          "taxes":[{"doctype":"Purchase Taxes and Charges","charge_type":"On Net Total","account_head":"VAT - WM","rate":9}]}}
    """.trimIndent()

    private val mappedDeliveryNote = """
        {"message":{"doctype":"Delivery Note","name":null,"customer":"CUST-001","company":"WM Co","set_warehouse":"Finished Goods - WM",
          "items":[
            {"doctype":"Delivery Note Item","name":null,"idx":1,"item_code":"20100103","qty":9,"stock_qty":108,"uom":"BOX","stock_uom":"Nos","conversion_factor":12,
             "rate":1000,"warehouse":"Finished Goods - WM","against_sales_order":"SAL-ORD-2026-00014","so_detail":"srow1","department":"Sales - WM",
             "serial_and_batch_bundle":null,"use_serial_batch_fields":0},
            {"doctype":"Delivery Note Item","name":null,"idx":2,"item_code":"20100042","qty":1,"stock_qty":1,"uom":"Nos","conversion_factor":1,
             "rate":500,"warehouse":"Finished Goods - WM","against_sales_order":"SAL-ORD-2026-00014","so_detail":"srow2","department":"Sales - WM"}
          ]}}
    """.trimIndent()

    /** `getdoctype` bundle of a plain site: nothing required beyond what the mapper fills. */
    private val plainMeta = """{"docs":[{"name":"Purchase Receipt","fields":[{"fieldname":"items","fieldtype":"Table","options":"Purchase Receipt Item","reqd":1},
        {"fieldname":"posting_date","fieldtype":"Date","reqd":1,"default":"Today"}]},
        {"name":"Purchase Receipt Item","fields":[{"fieldname":"item_code","label":"Item Code","fieldtype":"Link","options":"Item","reqd":0}]}],"user_settings":"{}"}"""

    /** A site that made Department mandatory on receipt rows. */
    private val departmentMeta = """{"docs":[{"name":"Purchase Receipt","fields":[{"fieldname":"items","fieldtype":"Table","options":"Purchase Receipt Item","reqd":1}]},
        {"name":"Purchase Receipt Item","fields":[
           {"fieldname":"item_code","label":"Item Code","fieldtype":"Link","options":"Item","reqd":1},
           {"fieldname":"department","label":"Department","fieldtype":"Link","options":"Department","reqd":1,"is_custom_field":1}]}],"user_settings":"{}"}"""

    private val mappedReceiptWithoutDepartment = mappedReceipt.replace(""""department":"Warehouse - WM",""", "")

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = OrderRepositoryImpl(harness.dataSource, harness.apiCaller, fixedDate)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `receipt starts from the ERPNext mapper, keeps only counted rows and their inherited fields`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedReceipt))
        harness.server.enqueue(MockResponse().setBody(plainMeta))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-PRE-2026-00007","supplier":"SUP-001","status":"Draft","posting_date":"2026-09-28","docstatus":0}}"""))

        val draft = PurchaseReceiptDraft(
            purchaseOrderName = "PUR-ORD-2026-00001",
            supplier = "SUP-001",
            lines = listOf(PurchaseReceiptLine("ITEM-001", 4.0, "Quarantine - WM", "row1", "Nos", 12.5)),
            company = "WM Co",
        )
        val result = repository.createPurchaseReceipt(draft, submit = false)

        val receipt = (result as AppResult.Success).data
        assertEquals("MAT-PRE-2026-00007", receipt.name)
        assertEquals(0, receipt.docStatus)

        val mapperCall = harness.server.takeRequest()
        assertEquals("GET", mapperCall.method)
        val mapperPath = URLDecoder.decode(mapperCall.path!!, "UTF-8")
        assertTrue(mapperPath.startsWith("/api/method/erpnext.buying.doctype.purchase_order.purchase_order.make_purchase_receipt"))
        assertTrue(mapperPath.contains("source_name=PUR-ORD-2026-00001"))
        val metaPath = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(metaPath.startsWith("/api/method/frappe.desk.form.load.getdoctype?doctype=Purchase Receipt"))

        val insert = harness.server.takeRequest()
        assertEquals("POST", insert.method)
        assertEquals("/api/resource/Purchase%20Receipt", insert.path)
        val body = Json.parseToJsonElement(insert.body.readUtf8()).jsonObject
        assertEquals("Purchase Receipt", body["doctype"]!!.jsonPrimitive.content)
        assertNull(body["__islocal"])
        assertEquals("Quarantine - WM", body["set_warehouse"]!!.jsonPrimitive.content)
        assertEquals(1, body["taxes"]!!.jsonArray.size)
        val items = body["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(1, items.size)
        val row = items.single()
        assertEquals("ITEM-001", row["item_code"]!!.jsonPrimitive.content)
        assertEquals(4.0, row["qty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(4.0, row["received_qty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(0.0, row["rejected_qty"]!!.jsonPrimitive.double, 0.0)
        assertEquals("Quarantine - WM", row["warehouse"]!!.jsonPrimitive.content)
        assertEquals("row1", row["purchase_order_item"]!!.jsonPrimitive.content)
        assertEquals("Warehouse - WM", row["department"]!!.jsonPrimitive.content)
        assertEquals("Main - WM", row["cost_center"]!!.jsonPrimitive.content)
        assertEquals(1, row["idx"]!!.jsonPrimitive.int)
    }

    @Test
    fun `submitting a receipt posts frappe client submit after the insert`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedReceipt))
        harness.server.enqueue(MockResponse().setBody(plainMeta))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-PRE-2026-00008","supplier":"SUP-001","status":"Draft","docstatus":0}}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-PRE-2026-00008","supplier":"SUP-001","status":"Draft","docstatus":0}}"""))
        harness.server.enqueue(MockResponse().setBody("""{"message":{"name":"MAT-PRE-2026-00008","supplier":"SUP-001","status":"To Bill","docstatus":1}}"""))

        val draft = PurchaseReceiptDraft("PUR-ORD-2026-00001", "SUP-001", listOf(PurchaseReceiptLine("ITEM-002", 3.0, "Stores - WM", "row2")))
        val receipt = (repository.createPurchaseReceipt(draft, submit = true) as AppResult.Success).data

        assertEquals(1, receipt.docStatus)
        assertEquals("To Bill", receipt.status)
        repeat(4) { harness.server.takeRequest() }
        assertEquals("/api/method/frappe.client.submit", harness.server.takeRequest().path)
    }

    @Test
    fun `a counted row that ERPNext no longer offers is refused instead of silently dropped`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedReceipt))

        val draft = PurchaseReceiptDraft("PUR-ORD-2026-00001", "SUP-001", listOf(PurchaseReceiptLine("ITEM-009", 1.0, "Stores - WM", "row9")))
        val result = repository.createPurchaseReceipt(draft, submit = false)

        val error = (result as AppResult.Failure).error
        assertEquals(ErrorCode.ORDER_ROW_UNAVAILABLE, error.code)
        assertEquals(listOf("ITEM-009", "PUR-ORD-2026-00001"), error.args)
        assertEquals(1, harness.server.requestCount)
    }

    @Test
    fun `delivery note splits batch tracked rows over their allocations using the classic batch fields`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedDeliveryNote))
        harness.server.enqueue(MockResponse().setBody("""{"docs":[{"name":"Delivery Note","fields":[]}],"user_settings":"{}"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-DN-2026-00031","customer":"CUST-001","customer_name":"Globex","status":"Draft","docstatus":0}}"""))

        val draft = DeliveryNoteDraft(
            salesOrderName = "SAL-ORD-2026-00014",
            customer = "CUST-001",
            lines = listOf(
                DeliveryNoteLine(
                    itemCode = "20100103", qty = 2.0, warehouse = "Finished Goods - WM", salesOrderRow = "srow1", uom = "BOX",
                    conversionFactor = 12.0, batches = listOf(BatchAllocation("B-2026-01", 20.0), BatchAllocation("B-2026-02", 4.0)),
                ),
                DeliveryNoteLine(itemCode = "20100042", qty = 1.0, warehouse = "Finished Goods - WM", salesOrderRow = "srow2"),
            ),
        )
        val note = (repository.createDeliveryNote(draft, submit = false) as AppResult.Success).data
        assertEquals("MAT-DN-2026-00031", note.name)

        val mapperPath = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(mapperPath.startsWith("/api/method/erpnext.selling.doctype.sales_order.sales_order.make_delivery_note"))
        harness.server.takeRequest() // getdoctype
        val insert = harness.server.takeRequest()
        assertEquals("/api/resource/Delivery%20Note", insert.path)
        val body = Json.parseToJsonElement(insert.body.readUtf8()).jsonObject
        val items = body["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(3, items.size)

        val (first, second, plain) = items
        assertEquals("B-2026-01", first["batch_no"]!!.jsonPrimitive.content)
        assertEquals(20.0 / 12.0, first["qty"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(20.0, first["stock_qty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(1, first["use_serial_batch_fields"]!!.jsonPrimitive.int)
        assertEquals("srow1", first["so_detail"]!!.jsonPrimitive.content)
        assertEquals("Sales - WM", first["department"]!!.jsonPrimitive.content)
        assertEquals("B-2026-02", second["batch_no"]!!.jsonPrimitive.content)
        assertEquals(4.0 / 12.0, second["qty"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(2, second["idx"]!!.jsonPrimitive.int)
        assertFalse(plain.containsKey("batch_no") && plain["batch_no"]!!.jsonPrimitive.content.isNotEmpty())
        assertEquals(1.0, plain["qty"]!!.jsonPrimitive.double, 0.0)
        assertEquals(3, plain["idx"]!!.jsonPrimitive.int)
    }

    @Test
    fun `required fields the site added are asked for before anything is inserted`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedReceiptWithoutDepartment))
        harness.server.enqueue(MockResponse().setBody(departmentMeta))
        harness.server.enqueue(MockResponse().setBody("""{"message":[[{"fieldname":"department","label":"Department","document_type":"Department"}],{}]}"""))

        val draft = PurchaseReceiptDraft("PUR-ORD-2026-00001", "SUP-001", listOf(PurchaseReceiptLine("ITEM-001", 4.0, "Stores - WM", "row1")), "WM Co")
        val result = repository.createPurchaseReceipt(draft, submit = false)

        val error = (result as AppResult.Failure).error as AppError.MissingRequiredFields
        assertEquals(listOf("Purchase Receipt Item.department"), error.fields.map { it.key })
        assertEquals("Department", error.fields.single().options)
        assertEquals(ErrorCode.MISSING_REQUIRED_FIELDS, error.code)
        assertEquals(listOf("Department"), error.args)
        assertEquals(3, harness.server.requestCount)
    }

    @Test
    fun `default accounting dimensions and remembered answers fill required fields`() = runTest {
        harness.server.enqueue(MockResponse().setBody(mappedReceiptWithoutDepartment))
        harness.server.enqueue(MockResponse().setBody(departmentMeta))
        harness.server.enqueue(MockResponse().setBody("""{"message":[[{"fieldname":"department"}],{"WM Co":{"department":"Warehouse - WM"}}]}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-PRE-2026-00009","supplier":"SUP-001","status":"Draft","docstatus":0}}"""))

        val draft = PurchaseReceiptDraft("PUR-ORD-2026-00001", "SUP-001", listOf(PurchaseReceiptLine("ITEM-001", 4.0, "Stores - WM", "row1")), "WM Co")
        assertTrue(repository.createPurchaseReceipt(draft, submit = false) is AppResult.Success)
        repeat(3) { harness.server.takeRequest() }
        val fromDimension = Json.parseToJsonElement(harness.server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("Warehouse - WM", fromDimension["items"]!!.jsonArray.single().jsonObject["department"]!!.jsonPrimitive.content)

        // Meta is cached; an answer given by the user beats the dimension default.
        harness.server.enqueue(MockResponse().setBody(mappedReceiptWithoutDepartment))
        harness.server.enqueue(MockResponse().setBody("""{"message":[[],{}]}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-PRE-2026-00010","supplier":"SUP-001","status":"Draft","docstatus":0}}"""))
        val answered = draft.copy(fieldValues = mapOf("Purchase Receipt Item.department" to "Sales - WM"))
        assertTrue(repository.createPurchaseReceipt(answered, submit = false) is AppResult.Success)
        harness.server.takeRequest() // mapper
        harness.server.takeRequest() // dimensions
        val fromAnswer = Json.parseToJsonElement(harness.server.takeRequest().body.readUtf8()).jsonObject
        assertEquals("Sales - WM", fromAnswer["items"]!!.jsonArray.single().jsonObject["department"]!!.jsonPrimitive.content)
        assertEquals(7, harness.server.requestCount)
    }

    @Test
    fun `link values are looked up per company first and without it as a fallback`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"Sales - WM"},{"name":"Warehouse - WM"}]}"""))
        val scoped = (repository.searchLinkValues("Department", "", "WM Co") as AppResult.Success).data
        assertEquals(listOf("Sales - WM", "Warehouse - WM"), scoped)
        val scopedPath = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(scopedPath.startsWith("/api/resource/Department?"))
        assertTrue(scopedPath.contains(""""company","=","WM Co""""))

        harness.server.enqueue(MockResponse().setResponseCode(417).setBody("""{"exc_type":"ValidationError","exception":"frappe.exceptions.ValidationError: Field not permitted in query: company"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"PRJ-0001"}]}"""))
        val fallback = (repository.searchLinkValues("Project", "PRJ", "WM Co") as AppResult.Success).data
        assertEquals(listOf("PRJ-0001"), fallback)
        harness.server.takeRequest()
        val plainPath = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(plainPath.contains(""""name","like","%PRJ%""""))
        assertFalse(plainPath.contains("company"))
    }

    @Test
    fun `item tracking maps the check fields of the requested items`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"20100103","has_batch_no":1,"has_serial_no":0},{"name":"20100042","has_batch_no":0,"has_serial_no":0}]}"""))

        val tracking = (repository.getItemTracking(listOf("20100103", "20100042", "20100103")) as AppResult.Success).data

        assertTrue(tracking.getValue("20100103").hasBatchNo)
        assertFalse(tracking.getValue("20100042").hasBatchNo)
        val path = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(path.startsWith("/api/resource/Item?"))
        assertTrue(path.contains("""["name","in",["20100103","20100042"]]"""))
        assertTrue((repository.getItemTracking(emptyList()) as AppResult.Success).data.isEmpty())
    }

    @Test
    fun `usable batches drop expired, disabled and empty batches and sort first expiry first`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"message":[{"batch_no":"B-LATE","qty":7,"warehouse":"Finished Goods - WM"},{"batch_no":"B-SOON","qty":5},
                   {"batch_no":"B-EXPIRED","qty":3},{"batch_no":"B-DISABLED","qty":9},{"batch_no":"B-EMPTY","qty":0},{"batch_no":"B-FOREVER","qty":2}]}"""
            )
        )
        harness.server.enqueue(
            MockResponse().setBody(
                """{"data":[{"name":"B-LATE","expiry_date":"2027-01-31","disabled":0},{"name":"B-SOON","expiry_date":"2026-10-15","disabled":0},
                   {"name":"B-EXPIRED","expiry_date":"2026-09-27","disabled":0},{"name":"B-DISABLED","expiry_date":"2027-06-30","disabled":1},
                   {"name":"B-FOREVER","expiry_date":null,"disabled":0}]}"""
            )
        )

        val batches = (repository.getUsableBatches("20100103", "Finished Goods - WM") as AppResult.Success).data

        assertEquals(listOf("B-SOON", "B-LATE", "B-FOREVER"), batches.map { it.batchNo })
        assertEquals(listOf(5.0, 7.0, 2.0), batches.map { it.qty })
        val stockPath = URLDecoder.decode(harness.server.takeRequest().path!!, "UTF-8")
        assertTrue(stockPath.startsWith("/api/method/erpnext.stock.doctype.batch.batch.get_batch_qty?"))
        assertTrue(stockPath.contains("item_code=20100103") && stockPath.contains("warehouse=Finished Goods - WM"))
    }

    @Test
    fun `no batch stock yields an empty list without querying batches`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":[]}"""))

        val batches = (repository.getUsableBatches("20100103", "Finished Goods - WM") as AppResult.Success).data

        assertTrue(batches.isEmpty())
        assertEquals(1, harness.server.requestCount)
    }
}
