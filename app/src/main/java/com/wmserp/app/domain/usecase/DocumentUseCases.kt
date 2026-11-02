package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.ItemDetail
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.repository.DocumentRepository
import com.wmserp.app.domain.repository.InventoryRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject

/** The filtered document list behind a dashboard / inventory card. */
class ListDocumentsUseCase @Inject constructor(private val documentRepository: DocumentRepository) {
    suspend operator fun invoke(doctype: OrderDoctype, filter: DocumentFilter, query: String = "", limit: Int = 30, start: Int = 0): AppResult<List<OrderDocument>> =
        documentRepository.listDocuments(doctype, filter, query.trim(), limit, start)
}

/** One document with its rows, for the read-only detail sheet. */
class GetDocumentUseCase @Inject constructor(private val documentRepository: DocumentRepository) {
    suspend operator fun invoke(doctype: OrderDoctype, name: String): AppResult<OrderDocument> =
        documentRepository.getDocument(doctype, name.trim())
}

/** The active items, pageable, with an optional search. */
class ListItemsUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(query: String = "", limit: Int = 30, start: Int = 0): AppResult<List<Item>> =
        inventoryRepository.listItems(query.trim(), limit, start)
}

/** An item and its stock per warehouse, loaded together for the item detail sheet. */
class GetItemDetailUseCase @Inject constructor(private val inventoryRepository: InventoryRepository) {
    suspend operator fun invoke(itemCode: String): AppResult<ItemDetail> = coroutineScope {
        val item = async { inventoryRepository.getItem(itemCode.trim()) }
        val stock = async { inventoryRepository.getStockLevels(itemCode.trim()) }
        when (val result = item.await()) {
            is AppResult.Success -> AppResult.Success(ItemDetail(result.data, stock.await().getOrNull().orEmpty().filter { it.actualQty != 0.0 || it.reservedQty != 0.0 }))
            is AppResult.Failure -> AppResult.Failure(result.error)
        }
    }
}
