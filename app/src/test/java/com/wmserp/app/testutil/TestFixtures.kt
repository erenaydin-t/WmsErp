package com.wmserp.app.testutil

import com.wmserp.app.domain.model.Batch
import com.wmserp.app.domain.model.BatchWarehouseStock
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickListItem
import com.wmserp.app.domain.model.PickListPurpose
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptItem
import com.wmserp.app.domain.model.StockLevel
import com.wmserp.app.domain.model.UserSession

object TestFixtures {
    val session = UserSession(userId = "user@example.com", fullName = "Eren Aydin", baseUrl = "https://erp.example.com")

    val item = Item(code = "ITEM-001", name = "Steel Bolt M8", group = "Fasteners", stockUom = "Nos", barcodes = listOf("8690000000017"), hasBatchNo = true)

    val stock = listOf(
        StockLevel(itemCode = "ITEM-001", warehouse = "Stores - WM", actualQty = 120.0, reservedQty = 20.0),
        StockLevel(itemCode = "ITEM-001", warehouse = "Finished Goods - WM", actualQty = 30.0),
    )

    val batch = Batch(
        name = "B-001",
        itemCode = "ITEM-001",
        itemName = "Steel Bolt M8",
        expiryDate = "2027-01-31",
        manufacturingDate = "2026-01-15",
        stockUom = "Nos",
        supplier = "SUP-001",
    )

    val batchStock = listOf(BatchWarehouseStock(warehouse = "Stores - WM", qty = 40.0))

    /** A draft Purchase Receipt at the warehouse stage: one batch tracked row without a batch yet, one plain row. */
    val purchaseReceipt = PurchaseReceipt(
        name = "MAT-PRE-2026-00001",
        supplier = "SUP-001",
        supplierName = "Acme Supplies",
        postingDate = "2026-09-28",
        company = "WM Co",
        setWarehouse = "Stores - WM",
        status = "Draft",
        workflowState = "Warehouse",
        docStatus = 0,
        supplierDeliveryNote = "DN-778",
        itemCount = 2,
        totalQty = 15.0,
        canReceive = true,
        hasWorkflow = true,
        items = listOf(
            PurchaseReceiptItem(
                rowName = "row1", idx = 1, itemCode = "ITEM-001", itemName = "Steel Bolt M8", qty = 10.0, uom = "Nos", stockUom = "Nos",
                warehouse = "Stores - WM", hasBatchNo = true, needsBatch = true, purchaseOrder = "PUR-ORD-2026-00001", barcodes = listOf("8690000000017"),
            ),
            PurchaseReceiptItem(
                rowName = "row2", idx = 2, itemCode = "ITEM-002", itemName = "Steel Nut M8", qty = 5.0, uom = "Nos", stockUom = "Nos",
                warehouse = "Stores - WM", purchaseOrder = "PUR-ORD-2026-00001",
            ),
        ),
    )

    val pickList = PickList(
        name = "STO-PICK-2026-00012",
        purpose = PickListPurpose.DELIVERY,
        purposeLabel = "Delivery",
        company = "WM Co",
        customer = "CUST-001",
        customerName = "Globex",
        parentWarehouse = "Stores - WM",
        status = "Open",
        pickingStatus = PickingStatus.READY_TO_PICK,
        itemCount = 3,
        pickedRows = 0,
        requiredQty = 18.0,
        pickedQty = 0.0,
        myRowCount = 2,
        myPickedRows = 0,
        myOpenRows = 2,
        items = listOf(
            PickListItem(
                rowName = "prow1", idx = 1, itemCode = "ITEM-001", itemName = "Steel Bolt M8", sourceWarehouse = "Stores - WM", targetWarehouse = null,
                batchNo = "B-001", expiryDate = "2027-01-31", serialNo = null, requiredQty = 10.0, pickedQty = 0.0, uom = "Nos", hasBatchNo = true,
                picker = "user@example.com", isMine = true,
            ),
            PickListItem(
                rowName = "prow2", idx = 2, itemCode = "ITEM-002", itemName = "Steel Nut M8", sourceWarehouse = "Stores - WM", targetWarehouse = null,
                batchNo = null, expiryDate = null, serialNo = null, requiredQty = 5.0, pickedQty = 0.0, uom = "Nos",
                picker = "user@example.com", isMine = true,
            ),
            PickListItem(
                rowName = "prow3", idx = 3, itemCode = "ITEM-003", itemName = "Washer M8", sourceWarehouse = "Stores - WM", targetWarehouse = null,
                batchNo = "B-777", expiryDate = null, serialNo = null, requiredQty = 3.0, pickedQty = 0.0, uom = "Nos", hasBatchNo = true,
                picker = "other@example.com", isMine = false,
            ),
        ),
    )
}
