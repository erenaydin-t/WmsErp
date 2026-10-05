package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PurchaseReceipt
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.usecase.GetMyPickListsUseCase
import com.wmserp.app.domain.usecase.GetMyStocktakingSessionsUseCase
import com.wmserp.app.domain.usecase.GetReceivableReceiptsUseCase
import com.wmserp.app.domain.usecase.PendingCountQueue
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receive (draft Purchase Receipts at the warehouse stage), Pick (my pick lists) and Count (stocktaking). */
enum class OrdersTab { RECEIVE, PICK, COUNT }

data class OrdersUiState(
    val tab: OrdersTab = OrdersTab.RECEIVE,
    val query: String = "",
    /** Draft Purchase Receipts the signed-in user may count and confirm. */
    val receipts: List<PurchaseReceipt> = emptyList(),
    val pickLists: List<PickList> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
    /** Kept apart so a server without the wmserp_picking app only affects the Pick tab. */
    val pickListError: UiText? = null,
    /** Stocktaking sessions the user can count in (Orders → Count). */
    val sessions: List<StocktakingSession> = emptyList(),
    val sessionError: UiText? = null,
    /** Counts still queued on this device per session, for the "waiting to sync" line of the cards. */
    val pendingCounts: Map<String, Int> = emptyMap(),
) {
    val filteredSessions: List<StocktakingSession>
        get() = if (query.isBlank()) sessions else sessions.filter { it.name.contains(query, ignoreCase = true) || it.warehouseName.contains(query, ignoreCase = true) || it.warehouse.contains(query, ignoreCase = true) }

    /** The backend already returns only the user's own lists; the search box filters them on the device. */
    val filteredPickLists: List<PickList>
        get() = if (query.isBlank()) {
            pickLists
        } else {
            pickLists.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.customerName?.contains(query, ignoreCase = true) == true ||
                    it.customer?.contains(query, ignoreCase = true) == true ||
                    it.purposeLabel.contains(query, ignoreCase = true)
            }
        }
}

/** Lists the receivable Purchase Receipts, the user's Pick Lists and the stocktaking sessions. */
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getReceivableReceipts: GetReceivableReceiptsUseCase,
    private val getMyPickLists: GetMyPickListsUseCase,
    private val getMySessions: GetMyStocktakingSessionsUseCase,
    private val pendingQueue: PendingCountQueue,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrdersUiState(tab = savedStateHandle.get<String>(ARG_TAB)?.let { runCatching { OrdersTab.valueOf(it) }.getOrNull() } ?: OrdersTab.RECEIVE)
    )
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var loadedOnce = false

    init {
        load()
    }

    fun selectTab(tab: OrdersTab) = _uiState.update { it.copy(tab = tab) }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load(refresh = true, reloadPickLists = false)
        }
    }

    fun refresh() = load(refresh = true)

    /** Called when the screen comes back to the foreground (e.g. after confirming a receipt or finishing a pick list). */
    fun onResumed() {
        if (loadedOnce) load(refresh = true, silent = true)
    }

    fun load(refresh: Boolean = false, silent: Boolean = false, reloadPickLists: Boolean = true) {
        loadJob?.cancel()
        val query = _uiState.value.query
        _uiState.update {
            it.copy(
                isLoading = !refresh && it.receipts.isEmpty() && it.pickLists.isEmpty(),
                isRefreshing = refresh && !silent,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            val pr = async { getReceivableReceipts(query) }
            val pl = if (reloadPickLists) async { getMyPickLists() } else null
            val st = if (reloadPickLists) async { getMySessions() } else null
            val prResult = pr.await()
            val plResult = pl?.await()
            val stResult = st?.await()
            val pending = (stResult as? AppResult.Success)?.data?.associate { it.name to pendingQueue.pending(it.name).size }
            loadedOnce = true
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    receipts = (prResult as? AppResult.Success)?.data ?: it.receipts,
                    pickLists = (plResult as? AppResult.Success)?.data ?: it.pickLists,
                    error = prResult.errorOrNull()?.toUiText(),
                    pickListError = if (plResult == null) it.pickListError else plResult.errorOrNull()?.toUiText(),
                    sessions = (stResult as? AppResult.Success)?.data ?: it.sessions,
                    sessionError = if (stResult == null) it.sessionError else stResult.errorOrNull()?.toUiText(),
                    pendingCounts = pending ?: it.pendingCounts,
                )
            }
        }
    }

    companion object {
        const val ARG_TAB = "tab"
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
