package com.wmserp.app.domain.model

/** What the user expects the scanned barcode to represent. */
enum class ScanTarget(val label: String, val hint: String) {
    /** An item barcode, an item code, a batch number or a WMS JSON QR label (item, or item + batch). */
    ITEM("Item / Batch", "Ready to scan item or batch label..."),
    WAREHOUSE("Warehouse", "Ready to scan warehouse label..."),
    PURCHASE_RECEIPT("Purchase Receipt", "Ready to scan purchase receipt..."),
}

/** Where a barcode came from. */
enum class ScanSource { HARDWARE_KEYBOARD, HARDWARE_INTENT, CAMERA, MANUAL }

data class ScannedCode(
    val value: String,
    val source: ScanSource,
    val symbology: String? = null,
    val timestampMillis: Long = System.currentTimeMillis(),
)

/** Result of resolving a scanned code against ERPNext. */
sealed class ScanLookup {
    abstract val code: String

    data class ItemFound(override val code: String, val item: Item, val stock: List<StockLevel>) : ScanLookup()

    /** A batch label (JSON QR with a batch, or a plain batch number): the batch with its item and stock per warehouse. */
    data class BatchFound(override val code: String, val batch: Batch, val item: Item?, val stock: List<BatchWarehouseStock>) : ScanLookup() {
        val totalQty: Double get() = stock.sumOf { it.qty }
    }

    data class WarehouseFound(override val code: String, val warehouse: Warehouse, val stock: List<StockLevel>) : ScanLookup()
    data class PurchaseReceiptFound(override val code: String, val receipt: PurchaseReceipt) : ScanLookup()
    data class NotFound(override val code: String, val target: ScanTarget) : ScanLookup()
}

/** User preference for which scanner input is used. */
enum class ScannerMode { AUTO, HARDWARE, CAMERA }

data class ScannerSettings(
    val mode: ScannerMode = ScannerMode.AUTO,
    val beepOnScan: Boolean = true,
    val vibrateOnScan: Boolean = true,
    /**
     * A matching scan opens a quantity prompt prefilled with everything still open on the row
     * (one scan + confirm takes the whole quantity); off, every scan adds a single unit.
     */
    val askQuantityOnScan: Boolean = true,
)
