package com.wmserp.app.data.repository

import com.wmserp.app.data.mapper.OrderDocuments
import com.wmserp.app.data.mapper.toDomain
import com.wmserp.app.data.remote.ApiCaller
import com.wmserp.app.data.remote.ErpNextDataSource
import com.wmserp.app.data.remote.Filter
import com.wmserp.app.data.remote.dto.BatchDto
import com.wmserp.app.data.remote.dto.BatchQtyDto
import com.wmserp.app.data.remote.dto.DeliveryNoteDto
import com.wmserp.app.data.remote.dto.ItemTrackingDto
import com.wmserp.app.data.remote.dto.LinkNameDto
import com.wmserp.app.data.remote.dto.PurchaseOrderDto
import com.wmserp.app.data.remote.dto.PurchaseReceiptDto
import com.wmserp.app.data.remote.dto.SalesOrderDto
import com.wmserp.app.data.util.DateProvider
import com.wmserp.app.domain.common.AppException
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.BatchStock
import com.wmserp.app.domain.model.DeliveryNote
import com.wmserp.app.domain.model.DeliveryNoteDraft
import com.wmserp.app.domain.model.ItemTracking
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.PurchaseReceiptDraft
import com.wmserp.app.domain.model.RequiredField
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.repository.OrderRepository
import com.wmserp.app.domain.usecase.BatchAllocator
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.HttpException
import java.io.IOException

class OrderRepositoryImpl(
    private val dataSource: ErpNextDataSource,
    private val apiCaller: ApiCaller,
    private val dateProvider: DateProvider,
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

    /**
     * The receipt starts from ERPNext's own `make_purchase_receipt` mapping of the order, so rates,
     * taxes and every custom field ERPNext copies from the order rows are kept; only the counted
     * quantities and the warehouse are overridden.
     */
    override suspend fun createPurchaseReceipt(draft: PurchaseReceiptDraft, submit: Boolean): AppResult<PurchaseReceipt> = apiCaller.call {
        val mapped = dataSource.mapDocument(MAKE_PURCHASE_RECEIPT, draft.purchaseOrderName)
        val doc = withRequiredFields(PURCHASE_RECEIPT, OrderDocuments.purchaseReceipt(mapped, draft), draft.fieldValues)
        val inserted = dataSource.insertDocument(PURCHASE_RECEIPT, doc)
        val receipt = decode(PurchaseReceiptDto.serializer(), inserted)
        if (submit) {
            decode(PurchaseReceiptDto.serializer(), dataSource.submit(PURCHASE_RECEIPT, receipt.name)).toDomain()
        } else {
            receipt.toDomain()
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

    /** Same approach as [createPurchaseReceipt], with batch-tracked rows split over their allocated batches. */
    override suspend fun createDeliveryNote(draft: DeliveryNoteDraft, submit: Boolean): AppResult<DeliveryNote> = apiCaller.call {
        val mapped = dataSource.mapDocument(MAKE_DELIVERY_NOTE, draft.salesOrderName)
        val doc = withRequiredFields(DELIVERY_NOTE, OrderDocuments.deliveryNote(mapped, draft), draft.fieldValues)
        val inserted = dataSource.insertDocument(DELIVERY_NOTE, doc)
        val note = decode(DeliveryNoteDto.serializer(), inserted)
        if (submit) {
            decode(DeliveryNoteDto.serializer(), dataSource.submit(DELIVERY_NOTE, note.name)).toDomain()
        } else {
            note.toDomain()
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

    override suspend fun getItemTracking(itemCodes: Collection<String>): AppResult<Map<String, ItemTracking>> = apiCaller.call {
        val codes = itemCodes.distinct()
        if (codes.isEmpty()) return@call emptyMap()
        dataSource.getList<ItemTrackingDto>(
            doctype = ITEM,
            fields = listOf("name", "has_batch_no", "has_serial_no"),
            filters = listOf(Filter.inList("name", codes)),
            limit = codes.size,
        ).associate { it.name to it.toDomain() }
    }

    /**
     * Stock per batch comes from ERPNext's `get_batch_qty` (the same helper its forms use); expiry dates
     * and the disabled flag from the `Batch` documents. Expired and disabled batches are dropped and the
     * rest ordered first-expiry-first-out.
     */
    override suspend fun getUsableBatches(itemCode: String, warehouse: String): AppResult<List<BatchStock>> = apiCaller.call {
        val stock = dataSource.getMethod<JsonElement>(GET_BATCH_QTY, mapOf("item_code" to itemCode, "warehouse" to warehouse))
        val quantities = (stock as? JsonArray)?.map { decode(BatchQtyDto.serializer(), it) }.orEmpty()
            .filter { !it.batchNo.isNullOrBlank() && it.qty > BatchAllocator.TOLERANCE }
        if (quantities.isEmpty()) return@call emptyList()
        val batches = dataSource.getList<BatchDto>(
            doctype = BATCH,
            fields = listOf("name", "expiry_date", "disabled"),
            filters = listOf(Filter.inList("name", quantities.map { it.batchNo!! })),
            limit = quantities.size,
        ).associateBy { it.name }
        val candidates = quantities.mapNotNull { row ->
            val batch = batches[row.batchNo]
            if (batch != null && batch.disabled != 0) return@mapNotNull null
            BatchStock(batchNo = row.batchNo!!, qty = row.qty, expiryDate = batch?.expiryDate)
        }
        BatchAllocator.usable(candidates, dateProvider.today())
    }

    override suspend fun searchLinkValues(doctype: String, query: String, company: String?, limit: Int): AppResult<List<String>> = apiCaller.call {
        val filters = if (query.isBlank()) emptyList() else listOf(Filter.like("name", query))
        val scoped = if (company.isNullOrBlank()) null else optional { linkNames(doctype, filters + Filter.eq("company", company), limit) }
        scoped ?: linkNames(doctype, filters, limit)
    }

    private suspend fun linkNames(doctype: String, filters: List<Filter>, limit: Int): List<String> =
        dataSource.getList<LinkNameDto>(doctype = doctype, fields = listOf("name"), filters = filters, orderBy = "name asc", limit = limit).map { it.name }

    /**
     * Fills the required fields the site added, or fails with [com.wmserp.app.domain.common.AppError.MissingRequiredFields]
     * so the UI can ask for them. Meta lookups are best effort: without them ERPNext still validates,
     * only with its own less helpful error.
     */
    private suspend fun withRequiredFields(doctype: String, doc: JsonObject, answers: Map<String, String>): JsonObject {
        val required: List<RequiredField> = optional { dataSource.requiredFields(doctype) } ?: return doc
        if (required.isEmpty()) return doc
        val company = (doc["company"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
        val dimensionDefaults = optional { dataSource.dimensionDefaults(company) } ?: emptyMap()
        return OrderDocuments.completeRequired(doc, required, answers, dimensionDefaults)
    }

    /** Runs a non-essential lookup; null when the server cannot answer it (never swallows cancellation). */
    private suspend fun <T> optional(block: suspend () -> T): T? = try {
        block()
    } catch (e: AppException) {
        null
    } catch (e: HttpException) {
        null
    } catch (e: IOException) {
        null
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private fun <T> decode(serializer: kotlinx.serialization.KSerializer<T>, element: JsonElement): T =
        dataSource.json.decodeFromJsonElement(serializer, element)

    companion object {
        const val PURCHASE_ORDER = "Purchase Order"
        const val PURCHASE_RECEIPT = "Purchase Receipt"
        const val SALES_ORDER = "Sales Order"
        const val DELIVERY_NOTE = "Delivery Note"
        const val ITEM = "Item"
        const val BATCH = "Batch"

        const val MAKE_PURCHASE_RECEIPT = "erpnext.buying.doctype.purchase_order.purchase_order.make_purchase_receipt"
        const val MAKE_DELIVERY_NOTE = "erpnext.selling.doctype.sales_order.sales_order.make_delivery_note"
        const val GET_BATCH_QTY = "erpnext.stock.doctype.batch.batch.get_batch_qty"

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
