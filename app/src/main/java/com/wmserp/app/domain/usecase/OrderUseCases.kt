package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.DeliveryNoteLine
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.PurchaseReceiptLine
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.repository.OrderRepository
import javax.inject.Inject

class GetOpenPurchaseOrdersUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(query: String = ""): AppResult<List<PurchaseOrder>> =
        orderRepository.getOpenPurchaseOrders(query.trim())
}

class GetPurchaseOrderUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(name: String): AppResult<PurchaseOrder> =
        orderRepository.getPurchaseOrder(name.trim()).flatMap { po ->
            if (po == null) AppResult.Failure(AppError.NotFound("Purchase Order $name not found")) else AppResult.Success(po)
        }
}

/** Quantity the user wants to receive for a purchase order row. */
data class ReceiveLine(val purchaseOrderRow: String, val qty: Double, val warehouse: String?)

/** Creates (and optionally submits) a Purchase Receipt for the received quantities of a PO. */
class ReceivePurchaseOrderUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(
        purchaseOrder: PurchaseOrder,
        lines: List<ReceiveLine>,
        defaultWarehouse: String?,
        submit: Boolean,
    ): AppResult<PurchaseReceipt> {
        val itemsByRow = purchaseOrder.items.associateBy { it.rowName }
        val receiptLines = mutableListOf<PurchaseReceiptLine>()
        for (line in lines) {
            if (line.qty <= 0.0) continue
            val poItem = itemsByRow[line.purchaseOrderRow]
                ?: return AppResult.Failure(AppError.Validation("Unknown purchase order row ${line.purchaseOrderRow}"))
            if (line.qty > poItem.pendingQty + QTY_TOLERANCE) {
                return AppResult.Failure(
                    AppError.Validation("${poItem.itemCode}: cannot receive ${line.qty.trimZeros()} (pending ${poItem.pendingQty.trimZeros()})")
                )
            }
            val warehouse = line.warehouse?.ifBlank { null } ?: poItem.warehouse ?: purchaseOrder.setWarehouse ?: defaultWarehouse
                ?: return AppResult.Failure(AppError.Validation("${poItem.itemCode}: select a warehouse"))
            receiptLines += PurchaseReceiptLine(
                itemCode = poItem.itemCode,
                qty = line.qty,
                warehouse = warehouse,
                purchaseOrderRow = poItem.rowName,
                uom = poItem.uom,
                rate = poItem.rate,
            )
        }
        if (receiptLines.isEmpty()) return AppResult.Failure(AppError.Validation("Enter at least one quantity to receive"))
        return orderRepository.createPurchaseReceipt(
            PurchaseReceiptDraft(
                purchaseOrderName = purchaseOrder.name,
                supplier = purchaseOrder.supplier,
                lines = receiptLines,
                company = purchaseOrder.company,
            ),
            submit = submit,
        )
    }

    private companion object {
        const val QTY_TOLERANCE = 0.000001
    }
}

class GetOpenSalesOrdersUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(query: String = ""): AppResult<List<SalesOrder>> =
        orderRepository.getOpenSalesOrders(query.trim())
}

class GetSalesOrderUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(name: String): AppResult<SalesOrder> =
        orderRepository.getSalesOrder(name.trim()).flatMap { so ->
            if (so == null) AppResult.Failure(AppError.NotFound("Sales Order $name not found")) else AppResult.Success(so)
        }
}

data class DispatchLine(val salesOrderRow: String, val qty: Double, val warehouse: String?)

/** Creates (and optionally submits) a Delivery Note for the dispatched quantities of a Sales Order. */
class DispatchSalesOrderUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(
        salesOrder: SalesOrder,
        lines: List<DispatchLine>,
        defaultWarehouse: String?,
        submit: Boolean,
    ): AppResult<DeliveryNote> {
        val itemsByRow = salesOrder.items.associateBy { it.rowName }
        val noteLines = mutableListOf<DeliveryNoteLine>()
        for (line in lines) {
            if (line.qty <= 0.0) continue
            val soItem = itemsByRow[line.salesOrderRow]
                ?: return AppResult.Failure(AppError.Validation("Unknown sales order row ${line.salesOrderRow}"))
            if (line.qty > soItem.pendingQty + QTY_TOLERANCE) {
                return AppResult.Failure(
                    AppError.Validation("${soItem.itemCode}: cannot dispatch ${line.qty.trimZeros()} (pending ${soItem.pendingQty.trimZeros()})")
                )
            }
            val warehouse = line.warehouse?.ifBlank { null } ?: soItem.warehouse ?: salesOrder.setWarehouse ?: defaultWarehouse
                ?: return AppResult.Failure(AppError.Validation("${soItem.itemCode}: select a warehouse"))
            noteLines += DeliveryNoteLine(
                itemCode = soItem.itemCode,
                qty = line.qty,
                warehouse = warehouse,
                salesOrderRow = soItem.rowName,
                uom = soItem.uom,
                rate = soItem.rate,
            )
        }
        if (noteLines.isEmpty()) return AppResult.Failure(AppError.Validation("Enter at least one quantity to dispatch"))
        return orderRepository.createDeliveryNote(
            DeliveryNoteDraft(
                salesOrderName = salesOrder.name,
                customer = salesOrder.customer,
                lines = noteLines,
                company = salesOrder.company,
            ),
            submit = submit,
        )
    }

    private companion object {
        const val QTY_TOLERANCE = 0.000001
    }
}

internal fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString() else this.toString()
