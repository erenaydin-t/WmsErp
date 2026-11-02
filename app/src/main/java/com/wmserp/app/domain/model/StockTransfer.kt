package com.wmserp.app.domain.model

/** A choice the transfer sheet asks for because the site's Stock Entry has the field (Department, Received By). */
data class TransferChoiceField(
    val fieldname: String,
    val label: String,
    /** Link target DocType searched for suggestions (Department, Employee...). */
    val options: String,
)

/** What the scan-menu transfer must ask besides warehouses and quantity (`get_transfer_form`). */
data class TransferForm(
    val department: TransferChoiceField? = null,
    val receivedBy: TransferChoiceField? = null,
    /** Batch tracked item: without a scanned batch the backend takes batches automatically (FEFO / FIFO). */
    val hasBatchNo: Boolean = false,
)

/** One stock transfer from the scan menu; [batchNo] is the scanned batch, if any. */
data class TransferRequest(
    val itemCode: String,
    val qty: Double,
    val sourceWarehouse: String,
    val targetWarehouse: String,
    val batchNo: String? = null,
    val department: String? = null,
    val receivedBy: String? = null,
)

/** The draft Material Transfer the backend created; one line per batch taken. */
data class CreatedTransfer(val name: String, val docStatus: Int = 0, val lines: List<TransferLine> = emptyList())

data class TransferLine(val batchNo: String?, val qty: Double)
