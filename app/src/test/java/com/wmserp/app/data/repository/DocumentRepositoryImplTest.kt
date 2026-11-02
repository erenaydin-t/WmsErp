package com.wmserp.app.data.repository

import com.wmserp.app.data.remote.FrappeQuery
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.OrderDoctype
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class DocumentRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: DocumentRepositoryImpl

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = DocumentRepositoryImpl(harness.dataSource, harness.apiCaller)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `open sales orders use the dashboard's filters, never amounts, and search name or customer`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"data":[{"name":"SAL-ORD-2026-00010","customer":"CUST-1","customer_name":"Globex","status":"To Deliver and Bill","docstatus":1,
                   "transaction_date":"2026-10-01","delivery_date":"2026-10-20","per_delivered":25.0,"set_warehouse":"Stores - WM","po_no":"PO-9","total_qty":12}]}"""
            )
        )

        val result = repository.listDocuments(OrderDoctype.SALES_ORDER, DocumentFilter(open = true), query = "glob", limit = 30, start = 60)

        val docs = (result as AppResult.Success).data
        assertEquals(1, docs.size)
        val so = docs.first()
        assertEquals(OrderDoctype.SALES_ORDER, so.doctype)
        assertEquals("Globex", so.partyName)
        assertEquals("2026-10-20", so.dueDate)
        assertEquals(25.0, so.percentDone!!, 0.0)
        assertEquals("PO-9", so.reference)
        assertEquals(12.0, so.totalQty, 0.0)

        val path = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(path.startsWith("/api/resource/Sales Order?"))
        assertTrue(path.contains("""["docstatus","=",1]"""))
        assertTrue(path.contains("""["status","in",["To Deliver and Bill","To Deliver"]]"""))
        assertTrue(path.contains("""["name","like","%glob%"]"""))
        assertTrue(path.contains("""["customer_name","like","%glob%"]"""))
        assertTrue(path.contains("limit_start=60"))
        assertTrue(path.contains("limit_page_length=30"))
        assertFalse(path.contains("grand_total") || path.contains("amount") || path.contains("rate"))
    }

    @Test
    fun `period lists filter submitted documents from the start date on`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[]}"""))

        repository.listDocuments(OrderDoctype.DELIVERY_NOTE, DocumentFilter(submittedSince = "2026-10-01"))

        val path = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(path.contains("""["docstatus","=",1]"""))
        assertTrue(path.contains("""["posting_date",">=","2026-10-01"]"""))
        assertTrue(path.contains("order_by=posting_date desc, modified desc"))
    }

    @Test
    fun `open pick lists match the inventory card count`() {
        val filters = FrappeQuery.filters(DocumentRepositoryImpl.filters(OrderDoctype.PICK_LIST, DocumentFilter(open = true)))!!
        assertEquals("""[["docstatus","<",2],["status","in",["Draft","Open"]]]""", filters)
        assertEquals(listOf("supplier", "supplier_name"), DocumentRepositoryImpl.listFields(OrderDoctype.PURCHASE_ORDER).filter { it.startsWith("supplier") })
    }

    @Test
    fun `getDocument maps rows, the done quantity and the source order`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"data":{"name":"PUR-ORD-2026-01666","supplier":"SUP-00039","supplier_name":"Acme","status":"To Receive and Bill","docstatus":1,
                   "transaction_date":"2026-10-16","schedule_date":"2026-10-20","per_received":92.3,"company":"WM Co","total_qty":13,
                   "items":[{"name":"r1","idx":1,"item_code":"13100074","item_name":"Vial","qty":10,"uom":"Nos","received_qty":9,"warehouse":"Q - WM"},
                            {"name":"r2","idx":2,"item_code":"16200273","qty":3,"stock_uom":"Box","received_qty":3,"warehouse":"C - WM"}]}}"""
            )
        )

        val result = repository.getDocument(OrderDoctype.PURCHASE_ORDER, "PUR-ORD-2026-01666")

        val po = (result as AppResult.Success).data
        assertEquals("Acme", po.partyName)
        assertEquals("2026-10-20", po.dueDate)
        assertEquals(2, po.items.size)
        val first = po.items.first()
        assertEquals("Vial", first.itemName)
        assertEquals(9.0, first.doneQty!!, 0.0)
        assertEquals(1.0, first.remainingQty, 0.0)
        assertEquals("16200273", po.items[1].itemName) // falls back to the code
        assertEquals("Box", po.items[1].uom)
        assertEquals("/api/resource/Purchase Order/PUR-ORD-2026-01666", URLDecoder.decode(harness.server.takeRequest().path, "UTF-8"))
    }

    @Test
    fun `pick list rows come from locations and a missing document is a NotFound error`() = runTest {
        harness.server.enqueue(
            MockResponse().setBody(
                """{"data":{"name":"STO-PICK-2026-00012","customer":"CUST-1","customer_name":"Globex","status":"Open","docstatus":0,"purpose":"Delivery",
                   "parent_warehouse":"Stores - WM","modified":"2026-10-16 10:00:00",
                   "locations":[{"name":"l1","idx":1,"item_code":"A","item_name":"Alpha","qty":5,"picked_qty":2,"warehouse":"Stores - WM","batch_no":"B-1","sales_order":"SAL-ORD-1"}]}}"""
            )
        )
        harness.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"exc_type":"DoesNotExistError"}"""))

        val pick = (repository.getDocument(OrderDoctype.PICK_LIST, "STO-PICK-2026-00012") as AppResult.Success).data
        assertEquals("Delivery", pick.reference)
        assertEquals("Stores - WM", pick.warehouse)
        assertEquals("2026-10-16", pick.date)
        assertEquals(1, pick.items.size)
        assertEquals(2.0, pick.items.first().doneQty!!, 0.0)
        assertEquals("B-1", pick.items.first().batchNo)
        assertEquals("SAL-ORD-1", pick.items.first().against)
        assertNull(pick.percentDone)

        val missing = repository.getDocument(OrderDoctype.SALES_ORDER, "nope")
        assertTrue((missing as AppResult.Failure).error is AppError.NotFound)
    }
}
