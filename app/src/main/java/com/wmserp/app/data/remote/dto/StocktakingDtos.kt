package com.wmserp.app.data.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Response of `get_session` / entries of `get_my_sessions`. */
@Serializable
data class StocktakingSessionDto(
    val name: String,
    val warehouse: String,
    @SerialName("warehouse_name") val warehouseName: String? = null,
    val warehouses: List<String> = emptyList(),
    val company: String? = null,
    @SerialName("posting_date") val postingDate: String? = null,
    val status: String? = null,
    @SerialName("counting_mode") val countingMode: String? = null,
    @SerialName("blind_count") val blindCount: Boolean = false,
    @SerialName("duplicate_count_policy") val duplicateCountPolicy: String? = null,
    @SerialName("require_second_count") val requireSecondCount: Boolean = true,
    @SerialName("qty_tolerance") val qtyTolerance: Double = 0.0,
    @SerialName("freeze_warehouse") val freezeWarehouse: Boolean = true,
    val frozen: Boolean = false,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("counting_completed_at") val countingCompletedAt: String? = null,
    @SerialName("approved_at") val approvedAt: String? = null,
    @SerialName("stock_reconciliation") val stockReconciliation: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
    val remarks: String? = null,
    val totals: StocktakingTotalsDto = StocktakingTotalsDto(),
    val my: MyStocktakingStatsDto = MyStocktakingStatsDto(),
    @SerialName("can_count") val canCount: Boolean = false,
    @SerialName("is_supervisor") val isSupervisor: Boolean = false,
    val counters: List<String> = emptyList(),
    @SerialName("qr_item_key") val qrItemKey: String? = null,
    @SerialName("qr_batch_key") val qrBatchKey: String? = null,
    val modified: String? = null,
)

@Serializable
data class StocktakingTotalsDto(
    @SerialName("total_items") val totalItems: Int = 0,
    @SerialName("counted_items") val countedItems: Int = 0,
    @SerialName("uncounted_items") val uncountedItems: Int = 0,
    @SerialName("matched_items") val matchedItems: Int = 0,
    @SerialName("variance_items") val varianceItems: Int = 0,
    @SerialName("recount_required") val recountRequired: Int = 0,
    @SerialName("pending_review") val pendingReview: Int = 0,
    @SerialName("approved_items") val approvedItems: Int = 0,
    @SerialName("qty_variance") val qtyVariance: Double? = 0.0,
    @SerialName("value_variance") val valueVariance: Double? = 0.0,
)

@Serializable
data class MyStocktakingStatsDto(
    val assigned: Int = 0,
    val open: Int = 0,
    @SerialName("items_counted") val itemsCounted: Int = 0,
    val counts: Int = 0,
)

/** One `Stocktaking Item` row as serialized by the backend. */
@Serializable
data class StocktakingItemDto(
    val name: String,
    val session: String? = null,
    @SerialName("item_code") val itemCode: String,
    @SerialName("item_name") val itemName: String? = null,
    val warehouse: String,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("stock_uom") val stockUom: String? = null,
    @SerialName("erp_qty") val erpQty: Double? = null,
    @SerialName("valuation_rate") val valuationRate: Double? = null,
    @SerialName("item_group") val itemGroup: String? = null,
    val brand: String? = null,
    @SerialName("has_batch_no") val hasBatchNo: Boolean = false,
    val location: String? = null,
    @SerialName("added_during_count") val addedDuringCount: Boolean = false,
    val status: String? = null,
    @SerialName("next_count_type") val nextCountType: String? = null,
    val counter: String? = null,
    @SerialName("counter_name") val counterName: String? = null,
    @SerialName("is_mine") val isMine: Boolean = false,
    @SerialName("count_1") val count1: Double? = null,
    @SerialName("count_2") val count2: Double? = null,
    @SerialName("recount_qty") val recountQty: Double? = null,
    @SerialName("recount_count") val recountCount: Int = 0,
    @SerialName("final_qty") val finalQty: Double? = null,
    @SerialName("qty_difference") val qtyDifference: Double? = null,
    @SerialName("value_difference") val valueDifference: Double? = null,
    @SerialName("counted_by") val countedBy: String? = null,
    @SerialName("counted_by_name") val countedByName: String? = null,
    @SerialName("counted_at") val countedAt: String? = null,
    @SerialName("recount_note") val recountNote: String? = null,
    val modified: String? = null,
)

/** Response of `get_items`. */
@Serializable
data class StocktakingItemsPageDto(
    val items: List<StocktakingItemDto> = emptyList(),
    val barcodes: Map<String, List<String>> = emptyMap(),
    val start: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    @SerialName("session_status") val sessionStatus: String? = null,
)

/** Response of `lookup`. */
@Serializable
data class StocktakingLookupDto(
    val found: Boolean = false,
    val raw: String? = null,
    val kind: String? = null,
    @SerialName("item_code") val itemCode: String? = null,
    @SerialName("item_name") val itemName: String? = null,
    @SerialName("batch_no") val batchNo: String? = null,
    @SerialName("expiry_date") val expiryDate: String? = null,
    @SerialName("has_batch_no") val hasBatchNo: Boolean = false,
    @SerialName("stock_uom") val stockUom: String? = null,
    @SerialName("can_add") val canAdd: Boolean = false,
    @SerialName("in_session") val inSession: Boolean = false,
    val rows: List<StocktakingItemDto> = emptyList(),
)

/** Response of `submit_count` and each entry of `sync_counts.results`. */
@Serializable
data class CountResultDto(
    val outcome: String? = null,
    val reason: String? = null,
    val message: String? = null,
    val item: StocktakingItemDto? = null,
    val count: String? = null,
    val totals: StocktakingTotalsDto? = null,
    @SerialName("session_status") val sessionStatus: String? = null,
    @SerialName("counted_by") val countedBy: String? = null,
    @SerialName("counted_by_name") val countedByName: String? = null,
    val counter: String? = null,
    @SerialName("counter_name") val counterName: String? = null,
    @SerialName("client_ref") val clientRef: String? = null,
    @SerialName("replayed_outcome") val replayedOutcome: String? = null,
)

/** Response of `sync_counts`. */
@Serializable
data class SyncCountsDto(
    val results: List<CountResultDto> = emptyList(),
    val totals: StocktakingTotalsDto? = null,
    @SerialName("session_status") val sessionStatus: String? = null,
)
