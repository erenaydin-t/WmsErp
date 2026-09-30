package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.common.ErrorCode
import com.wmserp.app.domain.model.BatchStock
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.DeliveryNoteLine
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.PurchaseReceiptLine
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class GetOpenPurchaseOrdersUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(query: String = ""): AppResult<List<PurchaseOrder>> =
        orderRepository.getOpenPurchaseOrders(query.trim())
}

class GetPurchaseOrderUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(name: String): AppResult<PurchaseOrder> =
        orderRepository.getPurchaseOrder(name.trim()).flatMap { po ->
            if (po == null) AppResult.Failure(AppError.NotFound("Purchase Order $name not found", ErrorCode.PURCHASE_ORDER_NOT_FOUND, listOf(name))) else AppResult.Success(po)
        }
}

/** Quantity the user wants to receive for a purchase order row. */
data class ReceiveLine(val purchaseOrderRow: String, val qty: Double, val warehouse: String?)

/**
 * Creates (and optionally submits) a Purchase Receipt for the received quantities of a PO.
 * [fieldValues] answers required fields the site added (see [com.wmserp.app.domain.model.RequiredField]);
 * answers remembered from earlier documents are merged in automatically.
 */
class ReceivePurchaseOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(
        purchaseOrder: PurchaseOrder,
        lines: List<ReceiveLine>,
        defaultWarehouse: String?,
        submit: Boolean,
        fieldValues: Map<String, String> = emptyMap(),
    ): AppResult<PurchaseReceipt> {
        val itemsByRow = purchaseOrder.items.associateBy { it.rowName }
        val receiptLines = mutableListOf<PurchaseReceiptLine>()
        for (line in lines) {
            if (line.qty <= 0.0) continue
            val poItem = itemsByRow[line.purchaseOrderRow]
                ?: return AppResult.Failure(AppError.Validation("Unknown purchase order row ${line.purchaseOrderRow}", ErrorCode.UNKNOWN_ORDER_ROW, listOf(line.purchaseOrderRow)))
            if (line.qty > poItem.pendingQty + QTY_TOLERANCE) {
                return AppResult.Failure(
                    AppError.Validation(
                        "${poItem.itemCode}: cannot receive ${line.qty.trimZeros()} (pending ${poItem.pendingQty.trimZeros()})",
                        ErrorCode.OVER_RECEIVE,
                        listOf(poItem.itemCode, line.qty.trimZeros(), poItem.pendingQty.trimZeros()),
                    )
                )
            }
            val warehouse = line.warehouse?.ifBlank { null } ?: poItem.warehouse ?: purchaseOrder.setWarehouse ?: defaultWarehouse
                ?: return AppResult.Failure(AppError.Validation("${poItem.itemCode}: select a warehouse", ErrorCode.SELECT_WAREHOUSE_FOR_ITEM, listOf(poItem.itemCode)))
            receiptLines += PurchaseReceiptLine(
                itemCode = poItem.itemCode,
                qty = line.qty,
                warehouse = warehouse,
                purchaseOrderRow = poItem.rowName,
                uom = poItem.uom,
                rate = poItem.rate,
            )
        }
        if (receiptLines.isEmpty()) return AppResult.Failure(AppError.Validation("Enter at least one quantity to receive", ErrorCode.NOTHING_TO_RECEIVE))
        return orderRepository.createPurchaseReceipt(
            PurchaseReceiptDraft(
                purchaseOrderName = purchaseOrder.name,
                supplier = purchaseOrder.supplier,
                lines = receiptLines,
                company = purchaseOrder.company,
                fieldValues = settingsRepository.documentFieldDefaults.first() + fieldValues,
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
            if (so == null) AppResult.Failure(AppError.NotFound("Sales Order $name not found", ErrorCode.SALES_ORDER_NOT_FOUND, listOf(name))) else AppResult.Success(so)
        }
}

data class DispatchLine(val salesOrderRow: String, val qty: Double, val warehouse: String?)

/**
 * Creates (and optionally submits) a Delivery Note for the dispatched quantities of a Sales Order.
 * Batch-tracked items are split over the warehouse's batches first-expiry-first-out (ERPNext refuses a
 * Delivery Note row of such an item without a batch); serial-numbered items are refused because their
 * serial numbers cannot be chosen here.
 */
class DispatchSalesOrderUseCase @Inject constructor(
    private val orderRepository: OrderRepository,
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(
        salesOrder: SalesOrder,
        lines: List<DispatchLine>,
        defaultWarehouse: String?,
        submit: Boolean,
        fieldValues: Map<String, String> = emptyMap(),
    ): AppResult<DeliveryNote> {
        val itemsByRow = salesOrder.items.associateBy { it.rowName }
        val noteLines = mutableListOf<DeliveryNoteLine>()
        for (line in lines) {
            if (line.qty <= 0.0) continue
            val soItem = itemsByRow[line.salesOrderRow]
                ?: return AppResult.Failure(AppError.Validation("Unknown sales order row ${line.salesOrderRow}", ErrorCode.UNKNOWN_ORDER_ROW, listOf(line.salesOrderRow)))
            if (line.qty > soItem.pendingQty + QTY_TOLERANCE) {
                return AppResult.Failure(
                    AppError.Validation(
                        "${soItem.itemCode}: cannot dispatch ${line.qty.trimZeros()} (pending ${soItem.pendingQty.trimZeros()})",
                        ErrorCode.OVER_DISPATCH,
                        listOf(soItem.itemCode, line.qty.trimZeros(), soItem.pendingQty.trimZeros()),
                    )
                )
            }
            val warehouse = line.warehouse?.ifBlank { null } ?: soItem.warehouse ?: salesOrder.setWarehouse ?: defaultWarehouse
                ?: return AppResult.Failure(AppError.Validation("${soItem.itemCode}: select a warehouse", ErrorCode.SELECT_WAREHOUSE_FOR_ITEM, listOf(soItem.itemCode)))
            noteLines += DeliveryNoteLine(
                itemCode = soItem.itemCode,
                qty = line.qty,
                warehouse = warehouse,
                salesOrderRow = soItem.rowName,
                uom = soItem.uom,
                rate = soItem.rate,
                conversionFactor = soItem.conversionFactor.takeIf { it > 0.0 } ?: 1.0,
            )
        }
        if (noteLines.isEmpty()) return AppResult.Failure(AppError.Validation("Enter at least one quantity to dispatch", ErrorCode.NOTHING_TO_DISPATCH))
        val allocated = when (val result = allocateBatches(noteLines)) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }
        return orderRepository.createDeliveryNote(
            DeliveryNoteDraft(
                salesOrderName = salesOrder.name,
                customer = salesOrder.customer,
                lines = allocated,
                company = salesOrder.company,
                fieldValues = settingsRepository.documentFieldDefaults.first() + fieldValues,
            ),
            submit = submit,
        )
    }

    /** Splits the lines of batch-tracked items over usable batches; lines of the same item and warehouse share them. */
    private suspend fun allocateBatches(lines: List<DeliveryNoteLine>): AppResult<List<DeliveryNoteLine>> {
        val tracking = when (val result = orderRepository.getItemTracking(lines.map { it.itemCode }.distinct())) {
            is AppResult.Success -> result.data
            is AppResult.Failure -> return AppResult.Failure(result.error)
        }
        val stockByLocation = mutableMapOf<Pair<String, String>, List<BatchStock>>()
        val allocated = mutableListOf<DeliveryNoteLine>()
        for (line in lines) {
            val flags = tracking[line.itemCode] ?: tracking.entries.firstOrNull { it.key.equals(line.itemCode, ignoreCase = true) }?.value
            if (flags?.hasSerialNo == true) {
                return AppResult.Failure(
                    AppError.Validation(
                        "${line.itemCode} needs serial numbers; deliver it from ERPNext",
                        ErrorCode.SERIAL_ITEM_UNSUPPORTED,
                        listOf(line.itemCode),
                    )
                )
            }
            if (flags?.hasBatchNo != true) {
                allocated += line
                continue
            }
            val key = line.itemCode to line.warehouse
            val batches = stockByLocation[key] ?: when (val result = orderRepository.getUsableBatches(line.itemCode, line.warehouse)) {
                is AppResult.Success -> result.data
                is AppResult.Failure -> return AppResult.Failure(result.error)
            }
            val allocation = BatchAllocator.allocate(batches, line.stockQty)
                ?: return AppResult.Failure(
                    AppError.Validation(
                        "${line.itemCode}: only ${BatchAllocator.available(batches).trimZeros()} available in batches at ${line.warehouse} (need ${line.stockQty.trimZeros()})",
                        ErrorCode.INSUFFICIENT_BATCH_STOCK,
                        listOf(line.itemCode, BatchAllocator.available(batches).trimZeros(), line.stockQty.trimZeros(), line.warehouse),
                    )
                )
            stockByLocation[key] = BatchAllocator.remaining(batches, allocation)
            allocated += line.copy(batches = allocation)
        }
        return AppResult.Success(allocated)
    }

    private companion object {
        const val QTY_TOLERANCE = 0.000001
    }
}

/** Possible values of a required Link field, offered in the "more details" dialog. */
class SearchLinkValuesUseCase @Inject constructor(private val orderRepository: OrderRepository) {
    suspend operator fun invoke(doctype: String, query: String = "", company: String? = null): AppResult<List<String>> =
        orderRepository.searchLinkValues(doctype, query.trim(), company)
}

/** Remembers the answers to required fields so the next documents do not ask again. */
class SaveDocumentFieldDefaultsUseCase @Inject constructor(private val settingsRepository: SettingsRepository) {
    suspend operator fun invoke(values: Map<String, String>) =
        settingsRepository.setDocumentFieldDefaults(values.filterValues { it.isNotBlank() })
}

internal fun Double.trimZeros(): String =
    if (this == Math.floor(this) && !this.isInfinite()) this.toLong().toString() else this.toString()
