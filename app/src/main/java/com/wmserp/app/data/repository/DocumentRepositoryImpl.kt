package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.DocumentDto
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.repository.DocumentRepository

/**
 * Lists and opens transaction documents through `frappe.client.get_list` / `get` with the same
 * filters the dashboard counts use, so a card's number and the list behind it agree.
 */
class DocumentRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
) : DocumentRepository {

    override suspend fun listDocuments(doctype: OrderDoctype, filter: DocumentFilter, query: String, limit: Int, start: Int): AppResult<List<OrderDocument>> =
        apiCaller.call {
            dataSource.getList<DocumentDto>(
                doctype = doctype.erpName,
                fields = listFields(doctype),
                filters = filters(doctype, filter),
                orFilters = searchFilters(doctype, query.trim()),
                orderBy = orderBy(doctype),
                limit = limit,
                start = start,
            ).map { it.toDomain(doctype) }
        }

    override suspend fun getDocument(doctype: OrderDoctype, name: String): AppResult<OrderDocument> = apiCaller.call {
        dataSource.getDoc<DocumentDto>(doctype.erpName, name).toDomain(doctype)
    }

    companion object {
        private val COMMON_FIELDS = listOf("name", "status", "docstatus", "company", "modified")
        private val PARTY_CUSTOMER = listOf("customer", "customer_name")
        private val PARTY_SUPPLIER = listOf("supplier", "supplier_name")

        fun listFields(doctype: OrderDoctype): List<String> = COMMON_FIELDS + when (doctype) {
            OrderDoctype.SALES_ORDER -> PARTY_CUSTOMER + listOf("transaction_date", "delivery_date", "per_delivered", "set_warehouse", "po_no", "total_qty")
            OrderDoctype.PURCHASE_ORDER -> PARTY_SUPPLIER + listOf("transaction_date", "schedule_date", "per_received", "set_warehouse", "total_qty")
            OrderDoctype.PURCHASE_RECEIPT -> PARTY_SUPPLIER + listOf("posting_date", "set_warehouse", "supplier_delivery_note", "total_qty")
            OrderDoctype.DELIVERY_NOTE -> PARTY_CUSTOMER + listOf("posting_date", "set_warehouse", "total_qty")
            OrderDoctype.PICK_LIST -> PARTY_CUSTOMER + listOf("purpose", "parent_warehouse")
        }

        /** The same conditions [AnalyticsRepositoryImpl] counts with. */
        fun filters(doctype: OrderDoctype, filter: DocumentFilter): List<Filter> = buildList {
            if (filter.open) {
                when (doctype) {
                    OrderDoctype.SALES_ORDER -> { add(Filter.eq("docstatus", 1)); add(Filter.inList("status", AnalyticsRepositoryImpl.SO_OPEN_STATUSES)) }
                    OrderDoctype.PURCHASE_ORDER -> { add(Filter.eq("docstatus", 1)); add(Filter.inList("status", AnalyticsRepositoryImpl.PO_OPEN_STATUSES)) }
                    OrderDoctype.PICK_LIST -> { add(Filter.lt("docstatus", 2)); add(Filter.inList("status", AnalyticsRepositoryImpl.PICK_LIST_OPEN_STATUSES)) }
                    OrderDoctype.PURCHASE_RECEIPT, OrderDoctype.DELIVERY_NOTE -> add(Filter.eq("docstatus", 0))
                }
            }
            filter.submittedSince?.takeIf { it.isNotBlank() }?.let { since ->
                add(Filter.eq("docstatus", 1))
                add(Filter.gte(dateField(doctype), since))
            }
        }

        fun searchFilters(doctype: OrderDoctype, query: String): List<Filter> {
            if (query.isBlank()) return emptyList()
            val partyName = when (doctype) {
                OrderDoctype.PURCHASE_ORDER, OrderDoctype.PURCHASE_RECEIPT -> "supplier_name"
                else -> "customer_name"
            }
            return listOf(Filter.like("name", query), Filter.like(partyName, query))
        }

        fun orderBy(doctype: OrderDoctype): String = when (doctype) {
            OrderDoctype.PICK_LIST -> "modified desc"
            else -> "${dateField(doctype)} desc, modified desc"
        }

        private fun dateField(doctype: OrderDoctype): String = when (doctype) {
            OrderDoctype.SALES_ORDER, OrderDoctype.PURCHASE_ORDER -> "transaction_date"
            OrderDoctype.PURCHASE_RECEIPT, OrderDoctype.DELIVERY_NOTE -> "posting_date"
            OrderDoctype.PICK_LIST -> "modified"
        }
    }
}
