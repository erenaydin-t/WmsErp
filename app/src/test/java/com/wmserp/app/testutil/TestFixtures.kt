package com.wmserp.app.testutil

import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseOrderItem
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.model.SalesOrderItem
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.UserSession

object TestFixtures {
    val session = UserSession(userId = "user@example.com", fullName = "Eren Aydin", baseUrl = "https://erp.example.com")

    val item = Item(code = "ITEM-001", name = "Steel Bolt M8", group = "Fasteners", stockUom = "Nos", barcodes = listOf("8690000000017"))

    val stock = listOf(
        StockLevel(itemCode = "ITEM-001", warehouse = "Stores - WM", actualQty = 120.0, reservedQty = 20.0),
        StockLevel(itemCode = "ITEM-001", warehouse = "Finished Goods - WM", actualQty = 30.0),
    )

    val purchaseOrder = PurchaseOrder(
        name = "PUR-ORD-2026-00001",
        supplier = "SUP-001",
        supplierName = "Acme Supplies",
        status = "To Receive and Bill",
        transactionDate = "2026-09-20",
        scheduleDate = "2026-09-27",
        grandTotal = 1500.0,
        currency = "USD",
        perReceived = 0.0,
        setWarehouse = "Stores - WM",
        company = "WM Co",
        items = listOf(
            PurchaseOrderItem(rowName = "row1", itemCode = "ITEM-001", itemName = "Steel Bolt M8", qty = 10.0, receivedQty = 0.0, uom = "Nos", warehouse = "Stores - WM", rate = 100.0, amount = 1000.0),
            PurchaseOrderItem(rowName = "row2", itemCode = "ITEM-002", itemName = "Steel Nut M8", qty = 5.0, receivedQty = 2.0, uom = "Nos", warehouse = null, rate = 100.0, amount = 500.0),
        ),
    )

    val salesOrder = SalesOrder(
        name = "SAL-ORD-2026-00001",
        customer = "CUST-001",
        customerName = "Globex",
        status = "To Deliver and Bill",
        transactionDate = "2026-09-20",
        deliveryDate = "2026-09-25",
        grandTotal = 900.0,
        currency = "USD",
        setWarehouse = "Finished Goods - WM",
        items = listOf(
            SalesOrderItem(rowName = "srow1", itemCode = "ITEM-001", itemName = "Steel Bolt M8", qty = 4.0, deliveredQty = 0.0, uom = "Nos", warehouse = "Finished Goods - WM", rate = 150.0),
        ),
    )
}
