package com.wmserp.app.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/** `Stocktaking Session.status` (wmserp_picking app). */
enum class StocktakingStatus(val serverValue: String) {
    DRAFT("Draft"),
    COUNTING("Counting"),
    MANAGER_REVIEW("Manager Review"),
    RECOUNT("Recount"),
    FINAL_APPROVAL("Final Approval"),
    RECONCILED("Reconciled"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled"),
    UNKNOWN("");

    /** Counts are accepted from the app only while counting (or recounting) is open. */
    val acceptsCounts: Boolean get() = this == COUNTING || this == RECOUNT

    companion object {
        fun fromServer(value: String?): StocktakingStatus =
            entries.firstOrNull { it != UNKNOWN && it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

enum class CountingMode(val serverValue: String) {
    /** Every row is given to a counter beforehand; a row belongs to its counter. */
    ASSIGNED("Assigned"),

    /** Counters scan whatever they find; the system records who counted what. */
    OPEN("Open");

    companion object {
        fun fromServer(value: String?): CountingMode = if (OPEN.serverValue.equals(value?.trim(), ignoreCase = true)) OPEN else ASSIGNED
    }
}

enum class DuplicateCountPolicy(val serverValue: String) {
    LOCK("Lock after count"),
    ALLOW("Allow additional counts");

    companion object {
        fun fromServer(value: String?): DuplicateCountPolicy = if (ALLOW.serverValue.equals(value?.trim(), ignoreCase = true)) ALLOW else LOCK
    }
}

/** `Stocktaking Item.status`. */
enum class CountItemStatus(val serverValue: String) {
    NOT_COUNTED("Not Counted"),
    ASSIGNED("Assigned"),
    COUNTING("Counting"),
    COUNTED("Counted"),
    RECOUNT_REQUIRED("Recount Required"),
    RECOUNTED("Recounted"),
    MANAGER_REVIEW("Manager Review"),
    APPROVED("Approved"),
    FINALIZED("Finalized");

    /** The row carries a final quantity. */
    val isCounted: Boolean get() = this == COUNTED || this == MANAGER_REVIEW || this == RECOUNTED || this == APPROVED || this == FINALIZED

    /** The row still waits for a count (first, second or recount). */
    val needsCount: Boolean get() = this == NOT_COUNTED || this == ASSIGNED || this == COUNTING || this == RECOUNT_REQUIRED

    companion object {
        fun fromServer(value: String?): CountItemStatus =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: NOT_COUNTED
    }
}

enum class CountType(val serverValue: String) {
    COUNT_1("Count 1"),
    COUNT_2("Count 2"),
    RECOUNT("Recount");

    companion object {
        fun fromServer(value: String?): CountType =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: COUNT_1
    }
}

data class StocktakingTotals(
    val total: Int = 0,
    val counted: Int = 0,
    val uncounted: Int = 0,
    val matched: Int = 0,
    val variance: Int = 0,
    val recountRequired: Int = 0,
    val pendingReview: Int = 0,
    val approved: Int = 0,
    /** Null in a blind count (hidden from counters). */
    val qtyVariance: Double? = 0.0,
    val valueVariance: Double? = 0.0,
) {
    val progress: Float get() = if (total <= 0) 0f else (counted.toFloat() / total).coerceIn(0f, 1f)
}

/** The signed-in counter's own numbers in a session. */
data class MyStocktakingStats(
    val assigned: Int = 0,
    val open: Int = 0,
    val itemsCounted: Int = 0,
    val counts: Int = 0,
)

data class StocktakingSession(
    val name: String,
    val warehouse: String,
    val warehouseName: String = warehouse,
    /** Leaf warehouses covered (one, or the members of a warehouse group). */
    val warehouses: List<String> = listOf(warehouse),
    val company: String? = null,
    val postingDate: String? = null,
    val status: StocktakingStatus = StocktakingStatus.COUNTING,
    val mode: CountingMode = CountingMode.ASSIGNED,
    /** ERP quantities are hidden from counters. */
    val blindCount: Boolean = false,
    val duplicatePolicy: DuplicateCountPolicy = DuplicateCountPolicy.LOCK,
    /** A first count that differs must be counted a second time before it reaches the manager. */
    val requireSecondCount: Boolean = true,
    val qtyTolerance: Double = 0.0,
    val frozen: Boolean = false,
    val startedAt: String? = null,
    val stockReconciliation: String? = null,
    val totals: StocktakingTotals = StocktakingTotals(),
    val my: MyStocktakingStats = MyStocktakingStats(),
    /** The server's verdict: the caller may count in this session right now. */
    val canCount: Boolean = false,
    val isSupervisor: Boolean = false,
    val counters: List<String> = emptyList(),
    val qrKeys: WmsQrKeys = WmsQrKeys.DEFAULT,
    val modified: String? = null,
) {
    /** In Assigned mode a counter only downloads and sees their own rows; supervisors see everything. */
    val mineOnly: Boolean get() = mode == CountingMode.ASSIGNED && !isSupervisor
}

/** One item / warehouse / batch row of a session. */
data class StocktakingItem(
    val name: String,
    val itemCode: String,
    val itemName: String,
    val warehouse: String,
    val batchNo: String? = null,
    val expiryDate: String? = null,
    val uom: String? = null,
    /** Reference quantity in ERPNext when the session started; null in a blind count. */
    val erpQty: Double? = 0.0,
    val status: CountItemStatus = CountItemStatus.NOT_COUNTED,
    val nextCountType: CountType = CountType.COUNT_1,
    val counter: String? = null,
    val counterName: String? = null,
    val isMine: Boolean = false,
    val count1: Double? = null,
    val count2: Double? = null,
    val recountQty: Double? = null,
    val recountCount: Int = 0,
    val finalQty: Double? = null,
    val qtyDifference: Double? = 0.0,
    val valueDifference: Double? = 0.0,
    val countedBy: String? = null,
    val countedByName: String? = null,
    val countedAt: String? = null,
    val recountNote: String? = null,
    val location: String? = null,
    val hasBatchNo: Boolean = false,
    val addedDuringCount: Boolean = false,
    val modified: String? = null,
) {
    /** The latest quantity entered, whatever its type. */
    val lastCount: Double? get() = recountQty ?: count2 ?: count1
}

data class StocktakingItemsPage(
    val items: List<StocktakingItem>,
    /** Item code -> barcodes, for plain (non-QR) labels. */
    val barcodes: Map<String, List<String>> = emptyMap(),
    val start: Int = 0,
    val limit: Int = 0,
    val total: Int = 0,
    val sessionStatus: StocktakingStatus = StocktakingStatus.COUNTING,
)

/** Server-side identification of a scan (`lookup`). */
data class StocktakingLookup(
    val found: Boolean,
    val raw: String,
    val itemCode: String? = null,
    val itemName: String? = null,
    val batchNo: String? = null,
    val expiryDate: String? = null,
    val hasBatchNo: Boolean = false,
    val uom: String? = null,
    /** The item is a stock item that can be added to the session when it is not listed. */
    val canAdd: Boolean = false,
    val inSession: Boolean = false,
    val rows: List<StocktakingItem> = emptyList(),
)

/** One physical count entered on the device, queued until the server has confirmed it. */
data class CountSubmission(
    /** Idempotency key generated on the device; the server never records the same one twice. */
    val clientRef: String,
    val sessionName: String,
    /** Row name when the row is known; otherwise the item / batch / warehouse identify (or create) it. */
    val itemName: String? = null,
    val itemCode: String,
    val batchNo: String? = null,
    val warehouse: String? = null,
    val qty: Double,
    /** Device clock when the count was entered, Frappe format `yyyy-MM-dd HH:mm:ss`. */
    val deviceTime: String,
    val note: String? = null,
    /** How often a sync attempt failed with a non-network error (kept for the pending badge). */
    val attempts: Int = 0,
    val lastError: String? = null,
)

/** What the server (or the local evaluator, while offline) decided about a count. */
enum class CountOutcome(val serverValue: String) {
    ACCEPTED("accepted"),
    SECOND_COUNT_REQUIRED("second_count_required"),
    MANAGER_REVIEW("manager_review"),
    RECOUNT_RECORDED("recount_recorded"),
    ADDITIONAL_COUNT("additional_count"),
    DUPLICATE("duplicate"),
    REJECTED("rejected"),
    ERROR("error");

    companion object {
        fun fromServer(value: String?): CountOutcome =
            entries.firstOrNull { it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: ERROR
    }
}

enum class CountRejection(val serverValue: String) {
    ALREADY_COUNTED("already_counted"),
    NOT_ASSIGNED("not_assigned"),
    SESSION_CLOSED("session_closed"),
    FINALIZED("finalized"),
    VALIDATION("validation"),
    PERMISSION("permission"),
    UNKNOWN("");

    companion object {
        fun fromServer(value: String?): CountRejection =
            entries.firstOrNull { it != UNKNOWN && it.serverValue.equals(value?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

data class CountResult(
    val outcome: CountOutcome,
    val item: StocktakingItem? = null,
    val totals: StocktakingTotals? = null,
    val sessionStatus: StocktakingStatus? = null,
    val rejection: CountRejection? = null,
    /** Server text for rejections / errors (shown verbatim). */
    val message: String? = null,
    val countedBy: String? = null,
    val countedByName: String? = null,
    val counter: String? = null,
    val counterName: String? = null,
    val clientRef: String? = null,
) {
    val isRejected: Boolean get() = outcome == CountOutcome.REJECTED || outcome == CountOutcome.ERROR
}

data class SyncOutcome(
    val results: List<CountResult>,
    val totals: StocktakingTotals? = null,
    val sessionStatus: StocktakingStatus? = null,
)

/** Session header plus every row the counter works with, kept on the device for offline counting. */
data class CachedStocktaking(
    val session: StocktakingSession,
    val items: List<StocktakingItem>,
    val barcodes: Map<String, List<String>> = emptyMap(),
    val cachedAtMillis: Long = 0L,
)

const val COUNT_QTY_TOLERANCE = 1e-6

/** The count agrees with ERPNext within the session tolerance (absolute, stock UOM). */
fun countMatches(qty: Double, erpQty: Double?, tolerance: Double = 0.0): Boolean =
    erpQty != null && abs(qty - erpQty) <= tolerance.coerceAtLeast(0.0) + COUNT_QTY_TOLERANCE

private val FRAPPE_DATETIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/** Device time in Frappe's datetime format (`2026-09-30 10:32:05`). */
fun frappeTimestamp(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime().format(FRAPPE_DATETIME)
