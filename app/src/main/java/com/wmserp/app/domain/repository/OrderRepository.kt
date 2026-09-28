package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
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
}
