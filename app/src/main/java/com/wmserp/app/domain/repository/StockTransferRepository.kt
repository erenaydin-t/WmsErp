package com.wmserp.app.domain.repository

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CreatedTransfer
import com.wmserp.app.domain.model.TransferForm
import com.wmserp.app.domain.model.TransferRequest

/** Backed by `wmserp_picking.api.stock_transfer`: scan-menu transfers created as drafts on the server. */
interface StockTransferRepository {
    suspend fun getTransferForm(itemCode: String): AppResult<TransferForm>

    /**
     * Creates the draft Material Transfer. [values] answers other required fields the site added;
     * when some are still missing the failure is [com.wmserp.app.domain.common.AppError.MissingRequiredFields].
     */
    suspend fun createTransfer(request: TransferRequest, values: Map<String, String> = emptyMap()): AppResult<CreatedTransfer>
}
