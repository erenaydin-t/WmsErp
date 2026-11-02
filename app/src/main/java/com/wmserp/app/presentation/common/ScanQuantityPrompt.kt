package com.wmserp.app.presentation.common

import kotlin.math.floor

/**
 * A matching scan that is waiting for the quantity it stands for.
 *
 * Scanning one label per unit is fine for a handful of pieces but not for a pallet of 200, so a
 * matching scan opens a prompt prefilled with everything still open on the row: one scan and one
 * confirmation take the whole quantity, and the picker lowers the number when they took less.
 * Over-picking is refused here, before anything is sent to ERPNext.
 */
data class ScanQuantityPrompt(
    val rowName: String,
    val itemCode: String,
    val itemName: String,
    val batchNo: String? = null,
    /** Quantity still open on the row (required - picked, or ordered - already counted). */
    val remaining: Double,
    val uom: String? = null,
    val qtyText: String = formatQty(remaining),
) {
    val qty: Double get() = qtyText.trim().replace(',', '.').toDoubleOrNull() ?: 0.0
    val isValid: Boolean get() = qty > 0.0 && qty <= remaining + TOLERANCE

    fun withText(text: String): ScanQuantityPrompt = copy(qtyText = text)
    fun plusOne(): ScanQuantityPrompt = copy(qtyText = formatQty((qty + 1.0).coerceAtMost(remaining)))
    fun minusOne(): ScanQuantityPrompt = copy(qtyText = formatQty((qty - 1.0).coerceAtLeast(0.0)))
    fun all(): ScanQuantityPrompt = copy(qtyText = formatQty(remaining))

    companion object {
        const val TOLERANCE = 1e-9

        /** "10" for whole quantities, "2.5" otherwise: what the quantity fields of the order screens show. */
        fun formatQty(value: Double): String = if (value == floor(value) && !value.isInfinite()) value.toLong().toString() else value.toString()
    }
}
