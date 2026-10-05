package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.ReceiptCount
import com.wmserp.app.domain.model.ReceiveResult

/** Backed by the `wmserp_picking.api.purchase_receipt` whitelisted methods of the ERPNext custom app. */
interface ReceiptRepository {
    /** Draft Purchase Receipts at the warehouse stage that the user may receive. */
    suspend fun getReceivableReceipts(query: String = "", limit: Int = 50): AppResult<List<PurchaseReceipt>>

    /** One receipt with its rows; null when it does not exist. */
    suspend fun getPurchaseReceipt(name: String): AppResult<PurchaseReceipt?>

    /**
     * Writes the counted quantities into the draft and, when [submit], submits it (through the
     * workflow action that submits it when the site has one); rows missing from [counts] are then
     * dropped from the receipt. Without [submit] the counts are saved as progress and the other
     * rows stay untouched. Fails with [com.wmserp.app.domain.common.AppError.MissingRequiredFields]
     * when ERPNext still needs values the app must ask for; [fieldValues] answers them.
     */
    suspend fun receive(
        name: String,
        counts: List<ReceiptCount>,
        fieldValues: Map<String, String> = emptyMap(),
        submit: Boolean = true,
    ): AppResult<ReceiveResult>

    /** Names of [doctype] documents matching [query] (for Link fields), limited to [company] when the DocType has one. */
    suspend fun searchLinkValues(doctype: String, query: String = "", company: String? = null, limit: Int = 100): AppResult<List<String>>
}
