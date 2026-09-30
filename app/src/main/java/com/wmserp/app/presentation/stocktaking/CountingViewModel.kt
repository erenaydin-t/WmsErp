package com.wmserp.app.presentation.stocktaking

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.R
import com.wmserp.app.core.scanner.ScannerController
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CachedStocktaking
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerMode
import com.wmserp.app.domain.model.StocktakingItem
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.domain.model.frappeTimestamp
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.CountEvaluator
import com.wmserp.app.domain.usecase.CountRefusal
import com.wmserp.app.domain.usecase.CountScanOutcome
import com.wmserp.app.domain.usecase.CountScanResolver
import com.wmserp.app.domain.usecase.LoadStocktakingSessionUseCase
import com.wmserp.app.domain.usecase.LookupStocktakingScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.PendingCountQueue
import com.wmserp.app.domain.usecase.SyncPendingCountsUseCase
import com.wmserp.app.domain.usecase.UpdateCachedStocktakingUseCase
import com.wmserp.app.domain.usecase.toRefusal
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.messageRes
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.orders.format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/** What the counting screen is doing: waiting for a scan, asking for a quantity, or asking which batch. */
sealed class CountingPhase {
    data object Scanning : CountingPhase()

    /**
     * A row was identified: the counter enters the physical quantity. [secondCount] flags the
     * mandatory second count after a mismatch; [isNew] rows are not in the session yet and are
     * added by the server when the count is submitted.
     */
    data class Counting(
        val item: StocktakingItem,
        val countType: CountType = CountType.COUNT_1,
        val qtyText: String = "",
        val secondCount: Boolean = false,
        val isNew: Boolean = false,
    ) : CountingPhase() {
        val qty: Double? get() = qtyText.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0.0 }
        val isValid: Boolean get() = qty != null
    }

    /** A plain barcode of a batch-tracked item: the counter picks the batch printed on the label. */
    data class ChoosingBatch(val itemCode: String, val itemName: String, val rows: List<StocktakingItem>) : CountingPhase()
}

enum class FeedbackKind { SUCCESS, INFO, WARNING, ERROR }

/** Short confirmation / rejection shown over the screen after a count or a scan. */
data class CountFeedback(val kind: FeedbackKind, val title: UiText, val detail: UiText? = null, val id: Long = 0L)

data class CountingUiState(
    val isLoading: Boolean = true,
    /** (loaded, total) while the rows are downloaded. */
    val loadProgress: Pair<Int, Int>? = null,
    val session: StocktakingSession? = null,
    val items: List<StocktakingItem> = emptyList(),
    val barcodes: Map<String, List<String>> = emptyMap(),
    /** The rows came from the device copy because the server was unreachable. */
    val fromCache: Boolean = false,
    val cachedAtMillis: Long = 0L,
    val phase: CountingPhase = CountingPhase.Scanning,
    val feedback: CountFeedback? = null,
    /** Counts saved on the device that the server has not confirmed yet. */
    val pending: List<CountSubmission> = emptyList(),
    val isSyncing: Boolean = false,
    val isLookingUp: Boolean = false,
    val query: String = "",
    val showSearch: Boolean = false,
    val cameraActive: Boolean = false,
    val hasHardwareScanner: Boolean = false,
    val scannerMode: ScannerMode = ScannerMode.AUTO,
    val beep: Boolean = true,
    val vibrate: Boolean = true,
    val userId: String? = null,
    /** The last row counted on this device (shown under the scan panel). */
    val lastCount: StocktakingItem? = null,
    val error: UiText? = null,
) {
    val counting: CountingPhase.Counting? get() = phase as? CountingPhase.Counting
    val totals: StocktakingTotals get() = session?.totals ?: StocktakingTotals()

    /** In Assigned mode the counter's own rows; in Open mode what this user counted. */
    val myTotal: Int get() = if (session?.mode == CountingMode.OPEN) items.count { it.countedBy == userId } else items.count { it.isMine }
    val myOpen: Int get() = if (session?.mode == CountingMode.OPEN) 0 else items.count { it.isMine && it.status.needsCount }
    val pendingCount: Int get() = pending.size
    val sessionOpen: Boolean get() = session?.status?.acceptsCounts == true

    /** Scans are accepted while the session counts, nothing is being asked and no lookup is running. */
    val canScan: Boolean get() = !isLoading && sessionOpen && phase is CountingPhase.Scanning && !isLookingUp

    val hardwareScannerReady: Boolean get() = hasHardwareScanner && scannerMode != ScannerMode.CAMERA
    val showCameraButton: Boolean get() = !hardwareScannerReady

    val searchResults: List<StocktakingItem>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return emptyList()
            return items.filter {
                it.itemCode.contains(q, ignoreCase = true) || it.itemName.contains(q, ignoreCase = true) || it.batchNo?.contains(q, ignoreCase = true) == true
            }.take(50)
        }
}

/**
 * Scan → identify → count → submit → ready for the next scan. Every submitted count is written to
 * the device queue first and then synchronized with ERPNext, so a Wi-Fi drop never loses a count;
 * the screen moves on immediately using the same rules as the server (second count on a mismatch,
 * locked rows, assignments) and the server's answer replaces the local guess when it arrives.
 */
@HiltViewModel
class CountingViewModel @Inject constructor(
    private val loadSession: LoadStocktakingSessionUseCase,
    private val syncPending: SyncPendingCountsUseCase,
    private val queue: PendingCountQueue,
    private val updateCache: UpdateCachedStocktakingUseCase,
    private val lookupScan: LookupStocktakingScanUseCase,
    observeScannerSettings: ObserveScannerSettingsUseCase,
    authRepository: AuthRepository,
    private val scanner: ScannerController,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val name: String = savedStateHandle.get<String>(ARG_NAME).orEmpty()

    /** Device clock; replaceable in tests. */
    internal var clock: () -> Long = System::currentTimeMillis

    /** Idempotency keys of the counts; replaceable in tests. */
    internal var newClientRef: () -> String = { UUID.randomUUID().toString() }

    /** Retry the queue by itself while counts are waiting (off in tests). */
    internal var autoRetry: Boolean = true
    internal var retryDelayMs: Long = RETRY_DELAY_MS

    private val _uiState = MutableStateFlow(CountingUiState(hasHardwareScanner = scanner.hasHardwareScanner))
    val uiState: StateFlow<CountingUiState> = _uiState.asStateFlow()

    private var settingsApplied = false
    private var retryJob: Job? = null
    private var feedbackSeq = 0L

    init {
        viewModelScope.launch {
            observeScannerSettings().collect { s ->
                _uiState.update {
                    it.copy(
                        beep = s.beepOnScan,
                        vibrate = s.vibrateOnScan,
                        scannerMode = s.mode,
                        cameraActive = if (settingsApplied) it.cameraActive else s.mode == ScannerMode.CAMERA,
                    )
                }
                settingsApplied = true
            }
        }
        viewModelScope.launch {
            authRepository.session.collect { session -> _uiState.update { it.copy(userId = session?.userId) } }
        }
        load()
    }

    // ---- loading -----------------------------------------------------------------------------

    fun load() {
        _uiState.update { it.copy(isLoading = true, loadProgress = null, error = null) }
        viewModelScope.launch {
            val pending = queue.pending(name)
            when (val result = loadSession(name, clock()) { loaded, total -> _uiState.update { it.copy(loadProgress = loaded to total) } }) {
                is AppResult.Success -> {
                    val loaded = result.data
                    _uiState.update { state ->
                        state.copy(
                            isLoading = false,
                            loadProgress = null,
                            session = loaded.cache.session,
                            items = applyPendingLocally(loaded.cache.items, pending, loaded.cache.session, state.userId),
                            barcodes = loaded.cache.barcodes,
                            fromCache = loaded.fromCache,
                            cachedAtMillis = loaded.cache.cachedAtMillis,
                            pending = pending,
                        )
                    }
                    if (pending.isNotEmpty()) syncNow()
                }
                is AppResult.Failure -> _uiState.update { it.copy(isLoading = false, loadProgress = null, error = result.error.toUiText()) }
            }
        }
    }

    /** Rows freshly downloaded do not know about the counts still queued on this device; replay them. */
    private fun applyPendingLocally(items: List<StocktakingItem>, pending: List<CountSubmission>, session: StocktakingSession, user: String?): List<StocktakingItem> {
        if (pending.isEmpty()) return items
        val byName = LinkedHashMap(items.associateBy { it.name })
        for (submission in pending) {
            val row = submission.itemName?.let { byName[it] }
                ?: byName.values.firstOrNull { it.itemCode.equals(submission.itemCode, ignoreCase = true) && it.batchNo.equals(submission.batchNo, ignoreCase = true) }
                ?: continue
            if (CountEvaluator.refusal(row, session, user) != null) continue
            byName[row.name] = CountEvaluator.evaluate(row, submission.qty, session, user, submission.deviceTime).item
        }
        return byName.values.toList()
    }

    /** Called when the screen returns to the foreground: flush what is waiting. */
    fun onResumed() {
        if (_uiState.value.pending.isNotEmpty()) syncNow()
    }

    fun toggleCamera() = _uiState.update { it.copy(cameraActive = !it.cameraActive) }
    fun dismissFeedback() = _uiState.update { it.copy(feedback = null, error = null) }
    fun toggleSearch() = _uiState.update { it.copy(showSearch = !it.showSearch, query = if (it.showSearch) "" else it.query) }
    fun setQuery(value: String) = _uiState.update { it.copy(query = value) }

    // ---- identification ----------------------------------------------------------------------

    /** Hardware wedge, intent, camera or manual entry. */
    fun onScanned(code: ScannedCode) {
        val state = _uiState.value
        val session = state.session ?: return
        if (!state.canScan) return
        when (val outcome = CountScanResolver.resolve(code.value, session.qrKeys, state.items, state.barcodes)) {
            is CountScanOutcome.Match -> openCount(outcome.row, code.source)
            is CountScanOutcome.ChooseBatch -> {
                if (code.source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
                _uiState.update { it.copy(phase = CountingPhase.ChoosingBatch(outcome.itemCode, outcome.itemName, outcome.rows), feedback = null, showSearch = false, query = "") }
            }
            is CountScanOutcome.NotInSession -> resolveRemotely(session, outcome, code.source)
            is CountScanOutcome.Invalid -> Unit
        }
    }

    /** A row from the search list or the batch chooser. */
    fun selectRow(rowName: String) {
        val row = _uiState.value.items.firstOrNull { it.name == rowName } ?: (_uiState.value.phase as? CountingPhase.ChoosingBatch)?.rows?.firstOrNull { it.name == rowName } ?: return
        openCount(row, ScanSource.MANUAL)
    }

    private fun openCount(row: StocktakingItem, source: ScanSource, isNew: Boolean = false) {
        val state = _uiState.value
        val session = state.session ?: return
        val refusal = CountEvaluator.refusal(row, session, state.userId)
        if (refusal != null) {
            reject(refusalText(refusal, row))
            return
        }
        if (source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
        _uiState.update {
            it.copy(
                phase = CountingPhase.Counting(
                    item = row,
                    countType = if (row.status == CountItemStatus.RECOUNT_REQUIRED) row.nextCountType else if (row.status.isCounted) CountType.RECOUNT else CountType.COUNT_1,
                    secondCount = row.status == CountItemStatus.RECOUNT_REQUIRED && row.nextCountType == CountType.COUNT_2,
                    isNew = isNew,
                ),
                feedback = null,
                error = null,
                showSearch = false,
                query = "",
            )
        }
    }

    /** The device copy does not know the label: ask the server (needs the network). */
    private fun resolveRemotely(session: StocktakingSession, outcome: CountScanOutcome.NotInSession, source: ScanSource) {
        val state = _uiState.value
        if (state.fromCache) {
            // Offline: a JSON label still identifies the item, so the row can be created on sync.
            if (outcome.itemCode != null) {
                openCount(newRow(session, outcome.itemCode, outcome.itemCode, outcome.batchNo, null, null), source, isNew = true)
            } else {
                reject(UiText.Res(R.string.st_unknown_offline))
            }
            return
        }
        _uiState.update { it.copy(isLookingUp = true) }
        viewModelScope.launch {
            when (val result = lookupScan(session.name, outcome.raw)) {
                is AppResult.Success -> {
                    val lookup = result.data
                    _uiState.update { it.copy(isLookingUp = false) }
                    when {
                        !lookup.found || lookup.itemCode == null -> reject(UiText.Res(R.string.st_unknown_scan, listOf(outcome.raw.take(40))))
                        lookup.rows.size == 1 -> {
                            mergeItems(lookup.rows)
                            openCount(lookup.rows.first(), source)
                        }
                        lookup.rows.size > 1 -> {
                            mergeItems(lookup.rows)
                            if (source != ScanSource.MANUAL) scanner.feedback(state.beep, state.vibrate)
                            _uiState.update { it.copy(phase = CountingPhase.ChoosingBatch(lookup.itemCode, lookup.itemName ?: lookup.itemCode, lookup.rows), feedback = null) }
                        }
                        lookup.canAdd -> openCount(newRow(session, lookup.itemCode, lookup.itemName ?: lookup.itemCode, lookup.batchNo, lookup.expiryDate, lookup.uom), source, isNew = true)
                        else -> reject(UiText.Res(R.string.st_unknown_scan, listOf(outcome.raw.take(40))))
                    }
                }
                is AppResult.Failure -> {
                    _uiState.update { it.copy(isLookingUp = false) }
                    if (result.error is AppError.Network && outcome.itemCode != null) {
                        openCount(newRow(session, outcome.itemCode, outcome.itemCode, outcome.batchNo, null, null), source, isNew = true)
                    } else if (result.error is AppError.Network) {
                        reject(UiText.Res(R.string.st_unknown_offline))
                    } else {
                        reject(result.error.toUiText())
                    }
                }
            }
        }
    }

    /** A row the session does not hold yet; the server creates it with the current ERP quantity on submit. */
    private fun newRow(session: StocktakingSession, itemCode: String, itemName: String, batchNo: String?, expiry: String?, uom: String?): StocktakingItem =
        StocktakingItem(
            name = "",
            itemCode = itemCode,
            itemName = itemName,
            warehouse = session.warehouses.singleOrNull() ?: session.warehouse,
            batchNo = batchNo,
            expiryDate = expiry,
            uom = uom,
            erpQty = null,
            status = if (session.mode == CountingMode.ASSIGNED) CountItemStatus.ASSIGNED else CountItemStatus.NOT_COUNTED,
            counter = if (session.mode == CountingMode.ASSIGNED) _uiState.value.userId else null,
            isMine = session.mode == CountingMode.ASSIGNED,
            hasBatchNo = batchNo != null,
            addedDuringCount = true,
        )

    // ---- counting ----------------------------------------------------------------------------

    fun setQtyText(text: String) = _uiState.update { s -> s.copy(phase = (s.phase as? CountingPhase.Counting)?.copy(qtyText = text) ?: s.phase) }

    /** One tap when the shelf agrees with ERPNext. */
    fun useErpQty() {
        val counting = _uiState.value.counting ?: return
        val erp = counting.item.erpQty ?: return
        setQtyText(erp.format())
    }

    fun cancelCounting() = _uiState.update { it.copy(phase = CountingPhase.Scanning, feedback = null) }

    /** Records the quantity: the row moves on at once, the count is queued and sent. */
    fun submitCount() {
        val state = _uiState.value
        val session = state.session ?: return
        val counting = state.counting ?: return
        val qty = counting.qty ?: run {
            _uiState.update { it.copy(error = UiText.Res(R.string.st_count_invalid)) }
            return
        }
        val now = clock()
        val submission = CountSubmission(
            clientRef = newClientRef(),
            sessionName = session.name,
            itemName = counting.item.name.takeIf { !counting.isNew && it.isNotBlank() },
            itemCode = counting.item.itemCode,
            batchNo = counting.item.batchNo,
            warehouse = counting.item.warehouse.takeIf { it.isNotBlank() },
            qty = qty,
            deviceTime = frappeTimestamp(now),
        )
        val evaluation = CountEvaluator.evaluate(counting.item, qty, session, state.userId, submission.deviceTime)
        val updated = evaluation.item
        _uiState.update { s ->
            val items = if (counting.isNew) s.items + updated.copy(name = updated.name.ifBlank { "pending:${submission.clientRef}" }) else s.items.map { if (it.name == updated.name) updated else it }
            val totals = localTotals(s.totals, counting.item, updated)
            val secondCount = evaluation.outcome == CountOutcome.SECOND_COUNT_REQUIRED
            s.copy(
                items = items,
                session = s.session?.copy(totals = totals),
                phase = if (secondCount) CountingPhase.Counting(updated, CountType.COUNT_2, qtyText = "", secondCount = true, isNew = counting.isNew) else CountingPhase.Scanning,
                feedback = if (secondCount) null else outcomeFeedback(evaluation.outcome, updated, qty),
                lastCount = updated,
                error = null,
            )
        }
        viewModelScope.launch {
            val pending = queue.enqueue(submission)
            _uiState.update { it.copy(pending = pending) }
            persistCache()
            syncNow()
        }
    }

    /** Uncounted → counted moves the counters even before the server confirms. */
    private fun localTotals(totals: StocktakingTotals, before: StocktakingItem, after: StocktakingItem): StocktakingTotals {
        val becameCounted = !before.status.isCounted && after.status.isCounted
        val becameOpen = before.status.isCounted && !after.status.isCounted
        val wasNew = before.name.isBlank()
        return totals.copy(
            total = totals.total + if (wasNew) 1 else 0,
            counted = totals.counted + (if (becameCounted) 1 else 0) - (if (becameOpen) 1 else 0),
            uncounted = (totals.uncounted - (if (becameCounted && !wasNew) 1 else 0) + (if (becameOpen) 1 else 0)).coerceAtLeast(0),
        )
    }

    // ---- synchronization -------------------------------------------------------------------------

    /** Sends the queue now; on a network failure everything stays queued and a retry is scheduled. */
    fun syncNow() {
        if (_uiState.value.isSyncing || _uiState.value.session == null) return
        _uiState.update { it.copy(isSyncing = true) }
        viewModelScope.launch {
            val report = syncPending(name)
            _uiState.update { state ->
                var items = state.items
                var session = state.session
                var phase = state.phase
                var feedback = state.feedback
                var lastCount = state.lastCount
                for (result in report.confirmed) {
                    val serverItem = result.item ?: continue
                    items = merge(items, serverItem, result.clientRef)
                    result.totals?.let { totals -> session = session?.copy(totals = totals) }
                    result.sessionStatus?.let { status -> session = session?.copy(status = status) }
                    if (lastCount != null && (lastCount.name == serverItem.name || lastCount.name == "pending:${result.clientRef}")) lastCount = serverItem
                    val counting = phase as? CountingPhase.Counting
                    if (counting != null && (counting.item.name == serverItem.name || counting.item.name.isBlank() && counting.item.itemCode == serverItem.itemCode && counting.item.batchNo == serverItem.batchNo)) {
                        phase = counting.copy(item = serverItem, isNew = false)
                    }
                    // The server wants a second count the device could not predict (blind count, a new
                    // row): reopen the count card when the screen is idle, otherwise say so.
                    if (result.outcome == CountOutcome.SECOND_COUNT_REQUIRED && (counting == null || counting.item.name != serverItem.name)) {
                        if (phase is CountingPhase.Scanning && session?.status?.acceptsCounts == true) {
                            phase = CountingPhase.Counting(serverItem, CountType.COUNT_2, qtyText = "", secondCount = true)
                            feedback = null
                        } else {
                            feedback = CountFeedback(FeedbackKind.WARNING, UiText.Res(R.string.st_second_count_title), UiText.Res(R.string.st_second_count_message, listOf((serverItem.count1 ?: 0.0).format())), nextFeedbackId())
                        }
                    }
                }
                for (result in report.refused) {
                    val serverItem = result.item
                    if (serverItem != null) items = merge(items, serverItem, result.clientRef)
                    result.totals?.let { totals -> session = session?.copy(totals = totals) }
                    result.sessionStatus?.let { status -> session = session?.copy(status = status) }
                    feedback = CountFeedback(
                        FeedbackKind.ERROR,
                        UiText.Res(R.string.st_sync_refused, listOf(report.refused.size.toString(), result.message ?: "")),
                        result.rejection?.toRefusal()?.let { refusalText(it, serverItem, result.countedByName ?: result.counterName) },
                        nextFeedbackId(),
                    )
                }
                state.copy(items = items, session = session, phase = phase, feedback = feedback, lastCount = lastCount, pending = report.remaining, isSyncing = false, fromCache = if (report.offline) state.fromCache else false)
            }
            if (report.refused.isNotEmpty()) scanner.feedback(beep = _uiState.value.beep, vibrate = true, error = true)
            persistCache()
            if (report.remaining.isNotEmpty() && report.offline) scheduleRetry() else if (report.remaining.isNotEmpty() && report.error == null) scheduleRetry()
        }
    }

    private fun scheduleRetry() {
        if (!autoRetry || retryJob?.isActive == true) return
        retryJob = viewModelScope.launch {
            delay(retryDelayMs)
            if (_uiState.value.pending.isNotEmpty()) syncNow()
        }
    }

    private fun merge(items: List<StocktakingItem>, serverItem: StocktakingItem, clientRef: String?): List<StocktakingItem> {
        val placeholder = clientRef?.let { "pending:$it" }
        val index = items.indexOfFirst { it.name == serverItem.name || (placeholder != null && it.name == placeholder) }
        return if (index >= 0) items.mapIndexed { i, item -> if (i == index) serverItem else item } else items + serverItem
    }

    private fun mergeItems(rows: List<StocktakingItem>) {
        _uiState.update { state -> state.copy(items = rows.fold(state.items) { acc, row -> merge(acc, row, null) }) }
    }

    private suspend fun persistCache() {
        val state = _uiState.value
        val session = state.session ?: return
        updateCache(CachedStocktaking(session, state.items.filterNot { it.name.startsWith("pending:") }, state.barcodes, state.cachedAtMillis.takeIf { it > 0 } ?: clock()))
    }

    // ---- feedback ---------------------------------------------------------------------------------

    private fun reject(message: UiText) {
        scanner.feedback(beep = _uiState.value.beep, vibrate = true, error = true)
        _uiState.update { it.copy(feedback = CountFeedback(FeedbackKind.ERROR, message, null, nextFeedbackId()), phase = CountingPhase.Scanning) }
    }

    private fun refusalText(refusal: CountRefusal, row: StocktakingItem?, nameHint: String? = null): UiText {
        val who = nameHint ?: when (refusal) {
            CountRefusal.ALREADY_COUNTED -> row?.countedByName ?: row?.countedBy
            CountRefusal.NOT_ASSIGNED -> row?.counterName ?: row?.counter
            else -> null
        }
        return if (who != null) UiText.Res(refusal.messageRes(withName = true), listOf(who)) else UiText.Res(refusal.messageRes(withName = false))
    }

    private fun outcomeFeedback(outcome: CountOutcome, item: StocktakingItem, qty: Double): CountFeedback {
        val detail = UiText.Res(R.string.st_count_detail, listOf(item.itemName, qty.format(), item.uom ?: ""))
        return when (outcome) {
            CountOutcome.ACCEPTED -> CountFeedback(FeedbackKind.SUCCESS, UiText.Res(R.string.st_accepted), detail, nextFeedbackId())
            CountOutcome.MANAGER_REVIEW -> CountFeedback(
                FeedbackKind.INFO,
                UiText.Res(R.string.st_sent_review),
                if (item.count2 != null) UiText.Res(R.string.st_sent_review_detail, listOf((item.count1 ?: 0.0).format(), item.count2.format())) else detail,
                nextFeedbackId(),
            )
            CountOutcome.RECOUNT_RECORDED -> CountFeedback(FeedbackKind.INFO, UiText.Res(R.string.st_recount_recorded), detail, nextFeedbackId())
            CountOutcome.ADDITIONAL_COUNT -> CountFeedback(FeedbackKind.INFO, UiText.Res(R.string.st_additional_recorded), detail, nextFeedbackId())
            else -> CountFeedback(FeedbackKind.SUCCESS, UiText.Res(R.string.st_accepted), detail, nextFeedbackId())
        }
    }

    private fun nextFeedbackId(): Long = ++feedbackSeq

    companion object {
        const val ARG_NAME = "sessionName"
        const val RETRY_DELAY_MS = 15_000L
    }
}

/** Kept for the screens: the feedback the server sends for a rejected count, as text. */
internal fun CountResult.messageOrEmpty(): String = message.orEmpty()
