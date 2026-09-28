package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.mapper.toRequest
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.DeliveryNoteDto
import com.wmserp.app.data.remote.dto.DeliveryNoteRequest
import com.wmserp.app.data.remote.dto.PurchaseOrderDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptRequest
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.repository.OrderRepository

class OrderRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : OrderRepository {

    override suspend fun getOpenPurchaseOrders(query: String, limit: Int): AppResult<List<PurchaseOrder>> = apiCaller.call {
        val orFilters = if (query.isBlank()) emptyList() else listOf(
            Filter.like("name", query),
            Filter.like("supplier_name", query),
            Filter.like("supplier", query),
        )
        dataSource.getList<PurchaseOrderDto>(
            doctype = PURCHASE_ORDER,
            fields = PO_LIST_FIELDS,
            filters = listOf(Filter.eq("docstatus", 1), Filter.inList("status", PO_OPEN_STATUSES)),
            orFilters = orFilters,
            orderBy = "schedule_date asc, modified desc",
            limit = limit,
        ).map { it.toDomain() }
    }

    override suspend fun getPurchaseOrder(name: String): AppResult<PurchaseOrder?> = apiCaller.call {
        dataSource.getDocOrNull<PurchaseOrderDto>(PURCHASE_ORDER, name)?.toDomain()
    }

    override suspend fun createPurchaseReceipt(draft: PurchaseReceiptDraft, submit: Boolean): AppResult<PurchaseReceipt> = apiCaller.call {
        val inserted = dataSource.insert<PurchaseReceiptRequest, PurchaseReceiptDto>(PURCHASE_RECEIPT, draft.toRequest())
        if (submit) {
            val submitted = dataSource.submit(PURCHASE_RECEIPT, inserted.name)
            dataSource.json.decodeFromJsonElement(PurchaseReceiptDto.serializer(), submitted).toDomain()
        } else {
            inserted.toDomain()
        }
    }

    override suspend fun getOpenSalesOrders(query: String, limit: Int): AppResult<List<SalesOrder>> = apiCaller.call {
        val orFilters = if (query.isBlank()) emptyList() else listOf(
            Filter.like("name", query),
            Filter.like("customer_name", query),
            Filter.like("customer", query),
        )
        dataSource.getList<SalesOrderDto>(
            doctype = SALES_ORDER,
            fields = SO_LIST_FIELDS,
            filters = listOf(Filter.eq("docstatus", 1), Filter.inList("status", SO_OPEN_STATUSES)),
            orFilters = orFilters,
            orderBy = "delivery_date asc, modified desc",
            limit = limit,
        ).map { it.toDomain() }
    }

    override suspend fun getSalesOrder(name: String): AppResult<SalesOrder?> = apiCaller.call {
        dataSource.getDocOrNull<SalesOrderDto>(SALES_ORDER, name)?.toDomain()
    }

    override suspend fun createDeliveryNote(draft: DeliveryNoteDraft, submit: Boolean): AppResult<DeliveryNote> = apiCaller.call {
        val inserted = dataSource.insert<DeliveryNoteRequest, DeliveryNoteDto>(DELIVERY_NOTE, draft.toRequest())
        if (submit) {
            val submitted = dataSource.submit(DELIVERY_NOTE, inserted.name)
            dataSource.json.decodeFromJsonElement(DeliveryNoteDto.serializer(), submitted).toDomain()
        } else {
            inserted.toDomain()
        }
    }

    override suspend fun getRecentDeliveryNotes(limit: Int): AppResult<List<DeliveryNote>> = apiCaller.call {
        dataSource.getList<DeliveryNoteDto>(
            doctype = DELIVERY_NOTE,
            fields = DN_LIST_FIELDS,
            filters = listOf(Filter.eq("docstatus", 1)),
            orderBy = "posting_date desc, modified desc",
            limit = limit,
        ).map { it.toDomain() }
    }

    companion object {
        const val PURCHASE_ORDER = "Purchase Order"
        const val PURCHASE_RECEIPT = "Purchase Receipt"
        const val SALES_ORDER = "Sales Order"
        const val DELIVERY_NOTE = "Delivery Note"

        val PO_OPEN_STATUSES = listOf("To Receive and Bill", "To Receive")
        val SO_OPEN_STATUSES = listOf("To Deliver and Bill", "To Deliver")

        val PO_LIST_FIELDS = listOf(
            "name", "supplier", "supplier_name", "status", "transaction_date", "schedule_date",
            "grand_total", "currency", "per_received", "set_warehouse", "company", "docstatus",
        )
        val SO_LIST_FIELDS = listOf(
            "name", "customer", "customer_name", "status", "transaction_date", "delivery_date",
            "grand_total", "currency", "per_delivered", "set_warehouse", "company", "docstatus",
        )
        val DN_LIST_FIELDS = listOf("name", "customer", "customer_name", "status", "posting_date", "docstatus", "grand_total", "currency")
    }
}
