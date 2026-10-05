package com.wmserp.app.data.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.StockEntry
import com.wmserp.app.domain.model.StockEntryItem
import com.wmserp.app.domain.model.StockEntryType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.URLDecoder

class InventoryRepositoryImplTest {

    private val harness = RepositoryTestHarness()
    private lateinit var repository: InventoryRepositoryImpl

    @Before
    fun setUp() = runTest {
        harness.start()
        harness.sessionStore.update { it.copy(baseUrl = harness.baseUrl, userId = "u", sid = "sid") }
        repository = InventoryRepositoryImpl(harness.dataSource, harness.sessionStore, harness.apiCaller)
    }

    @After
    fun tearDown() = harness.shutdown()

    @Test
    fun `findItemByBarcode filters the Item Barcode child table then loads the full document`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"ITEM-001","item_code":"ITEM-001","item_name":"Bolt"}]}"""))
        harness.server.enqueue(
            MockResponse().setBody(
                """{"data":{"name":"ITEM-001","item_code":"ITEM-001","item_name":"Bolt","stock_uom":"Nos","image":"/files/bolt.png",
                   "barcodes":[{"barcode":"8690000000017","barcode_type":"EAN"}],"disabled":0,"is_stock_item":1}}"""
            )
        )

        val result = repository.findItemByBarcode("8690000000017")

        val item = (result as AppResult.Success).data!!
        assertEquals("ITEM-001", item.code)
        assertEquals(listOf("8690000000017"), item.barcodes)
        assertEquals("${harness.baseUrl}/files/bolt.png", item.imageUrl)

        val list = harness.server.takeRequest()
        val decoded = URLDecoder.decode(list.path, "UTF-8")
        assertTrue(decoded.startsWith("/api/resource/Item?"))
        assertTrue(decoded.contains("""[["Item Barcode","barcode","=","8690000000017"]]"""))
        assertEquals("/api/resource/Item/ITEM-001", harness.server.takeRequest().path)
    }

    @Test
    fun `findItemByBarcode returns null when neither barcode nor item code match`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[]}"""))
        harness.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"exc_type":"DoesNotExistError"}"""))

        val result = repository.findItemByBarcode("unknown")

        assertNull((result as AppResult.Success).data)
    }

    @Test
    fun `getWarehouseStock resolves item names in a single batch request`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"item_code":"A","warehouse":"W","actual_qty":5.0},{"item_code":"B","warehouse":"W","actual_qty":2}]}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"A","item_name":"Alpha"},{"name":"B","item_name":"Beta"}]}"""))

        val result = repository.getWarehouseStock("W")

        val stock = (result as AppResult.Success).data
        assertEquals(listOf("Alpha", "Beta"), stock.map { it.itemName })
        assertEquals(5.0, stock.first().actualQty, 0.0)
    }

    @Test
    fun `createStockEntry inserts then submits through frappe client submit`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-STE-00001","stock_entry_type":"Material Transfer","docstatus":0,"items":[{"item_code":"A","qty":3.0,"s_warehouse":"W1","t_warehouse":"W2"}]}}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"MAT-STE-00001","stock_entry_type":"Material Transfer","docstatus":0,"items":[]}}"""))
        harness.server.enqueue(MockResponse().setBody("""{"message":{"name":"MAT-STE-00001","stock_entry_type":"Material Transfer","docstatus":1,"items":[{"item_code":"A","qty":3.0}]}}"""))

        val entry = StockEntry(type = StockEntryType.MATERIAL_TRANSFER, items = listOf(StockEntryItem("A", 3.0, "W1", "W2")))
        val result = repository.createStockEntry(entry, submit = true)

        val submitted = (result as AppResult.Success).data
        assertEquals(1, submitted.docStatus)
        assertEquals("MAT-STE-00001", submitted.name)

        val insert = harness.server.takeRequest()
        assertEquals("POST", insert.method)
        assertEquals("/api/resource/Stock%20Entry", insert.path)
        val body = insert.body.readUtf8()
        assertTrue(body.contains("\"stock_entry_type\":\"Material Transfer\""))
        assertTrue(body.contains("\"s_warehouse\":\"W1\""))
        harness.server.takeRequest()
        assertEquals("/api/method/frappe.client.submit", harness.server.takeRequest().path)
    }

    @Test
    fun `session expiry on a normal call is broadcast on the event bus`() = runTest {
        harness.server.enqueue(MockResponse().setResponseCode(403).setBody("""{"exc_type":"AuthenticationError","message":"Not logged in"}"""))
        var expired = false
        val job = launch(Dispatchers.Unconfined) { harness.eventBus.events.collect { expired = true } }

        val result = repository.getItem("X")

        assertTrue(result is AppResult.Failure)
        assertTrue(expired)
        job.cancel()
    }

    @Test
    fun `getBatch reads the batch by name and falls back to the printed batch id`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"data":{"name":"B-001","batch_id":"B-001","item":"ITEM-001","item_name":"Bolt","expiry_date":"2027-01-31","disabled":0,"stock_uom":"Nos"}}"""))
        harness.server.enqueue(MockResponse().setResponseCode(404).setBody("""{"exc_type":"DoesNotExistError"}"""))
        harness.server.enqueue(MockResponse().setBody("""{"data":[{"name":"BATCH-00007","batch_id":"LOT-7","item":"ITEM-002","disabled":1}]}"""))

        val byName = (repository.getBatch("B-001") as AppResult.Success).data!!
        val byId = (repository.getBatch("LOT-7") as AppResult.Success).data!!

        assertEquals("ITEM-001", byName.itemCode)
        assertEquals("2027-01-31", byName.expiryDate)
        assertTrue(byName.isExpiredOn("2027-02-01"))
        assertEquals("BATCH-00007", byId.name)
        assertTrue(byId.disabled)
        assertEquals("/api/resource/Batch/B-001", harness.server.takeRequest().path)
        assertEquals("/api/resource/Batch/LOT-7", harness.server.takeRequest().path)
        val list = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(list.contains("""["batch_id","=","LOT-7"]"""))
    }

    @Test
    fun `getBatchStock keeps the warehouses with stock, largest first`() = runTest {
        harness.server.enqueue(MockResponse().setBody("""{"message":[{"warehouse":"Stores - WM","qty":5},{"warehouse":"Finished Goods - WM","qty":12.5},{"warehouse":"Scrap - WM","qty":0}]}"""))

        val stock = (repository.getBatchStock("B-001") as AppResult.Success).data

        assertEquals(listOf("Finished Goods - WM", "Stores - WM"), stock.map { it.warehouse })
        assertEquals(12.5, stock.first().qty, 0.0)
        val path = URLDecoder.decode(harness.server.takeRequest().path, "UTF-8")
        assertTrue(path.startsWith("/api/method/erpnext.stock.doctype.batch.batch.get_batch_qty?"))
        assertTrue(path.contains("batch_no=B-001"))
    }
}
