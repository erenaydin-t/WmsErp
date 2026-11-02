package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument

/** Read-only access to the transaction documents the app lists from its KPI cards. */
interface DocumentRepository {
    /** Documents of [doctype] matching [filter] and the free-text [query] (name or party name), newest first. */
    suspend fun listDocuments(
        doctype: OrderDoctype,
        filter: DocumentFilter,
        query: String = "",
        limit: Int = 30,
        start: Int = 0,
    ): AppResult<List<OrderDocument>>

    /** One document with its rows. */
    suspend fun getDocument(doctype: OrderDoctype, name: String): AppResult<OrderDocument>
}
