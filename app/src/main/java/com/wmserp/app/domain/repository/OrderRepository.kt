package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.BatchStock
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.ItemTracking
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.SalesOrder

interface OrderRepository {
    suspend fun getOpenPurchaseOrders(query: String = "", limit: Int = 30): AppResult<List<PurchaseOrder>>
    suspend fun getPurchaseOrder(name: String): AppResult<PurchaseOrder?>
    suspend fun createPurchaseReceipt(draft: PurchaseReceiptDraft, submit: Boolean): AppResult<PurchaseReceipt>

    suspend fun getOpenSalesOrders(query: String = "", limit: Int = 30): AppResult<List<SalesOrder>>
    suspend fun getSalesOrder(name: String): AppResult<SalesOrder?>
    suspend fun createDeliveryNote(draft: DeliveryNoteDraft, submit: Boolean): AppResult<DeliveryNote>
    suspend fun getRecentDeliveryNotes(limit: Int = 10): AppResult<List<DeliveryNote>>

    /** Batch / serial tracking flags of the given items, keyed by item code. */
    suspend fun getItemTracking(itemCodes: Collection<String>): AppResult<Map<String, ItemTracking>>

    /**
     * Batches of [itemCode] with stock in [warehouse] that can be delivered today (enabled, not expired,
     * positive quantity), ordered first-expiry-first-out.
     */
    suspend fun getUsableBatches(itemCode: String, warehouse: String): AppResult<List<BatchStock>>
}
