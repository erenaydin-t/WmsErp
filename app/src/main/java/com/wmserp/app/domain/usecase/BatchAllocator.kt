package com.wmserp.app.domain.usecase

import com.wmserp.app.domain.model.BatchAllocation
import com.wmserp.app.domain.model.BatchStock
import java.time.LocalDate

/**
 * First-expiry-first-out batch allocation for delivery lines of batch-tracked items.
 * Quantities are in the item's stock UOM.
 */
object BatchAllocator {
    const val TOLERANCE = 0.000001

    /**
     * Batches that may be delivered on [today]: positive stock and not expired, ordered by expiry
     * date (earliest first, batches without expiry last) and then by batch number.
     */
    fun usable(batches: List<BatchStock>, today: LocalDate): List<BatchStock> = batches
        .filter { it.qty > TOLERANCE }
        .filter { batch -> batch.expiryDate?.let { parseDate(it) }?.let { !it.isBefore(today) } ?: true }
        .sortedWith(compareBy<BatchStock> { it.expiryDate?.let { d -> parseDate(d) } ?: LocalDate.MAX }.thenBy { it.batchNo })

    /** Greedy allocation of [qty] over [batches] in order; null when they cannot cover it. */
    fun allocate(batches: List<BatchStock>, qty: Double): List<BatchAllocation>? {
        var remaining = qty
        val result = mutableListOf<BatchAllocation>()
        for (batch in batches) {
            if (remaining <= TOLERANCE) break
            if (batch.qty <= TOLERANCE) continue
            val take = minOf(batch.qty, remaining)
            result += BatchAllocation(batch.batchNo, take)
            remaining -= take
        }
        return if (remaining <= TOLERANCE) result else null
    }

    /** The batches left after [allocations] were taken from them. */
    fun remaining(batches: List<BatchStock>, allocations: List<BatchAllocation>): List<BatchStock> {
        val taken = allocations.groupBy { it.batchNo }.mapValues { (_, list) -> list.sumOf { it.qty } }
        return batches.map { batch -> taken[batch.batchNo]?.let { batch.copy(qty = batch.qty - it) } ?: batch }
    }

    fun available(batches: List<BatchStock>): Double = batches.sumOf { it.qty.coerceAtLeast(0.0) }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}
