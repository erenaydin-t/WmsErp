package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.usecase.GetMyPickListsUseCase
import com.wmserp.app.domain.usecase.GetOpenPurchaseOrdersUseCase
import com.wmserp.app.domain.usecase.GetOpenSalesOrdersUseCase
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

enum class OrdersTab { RECEIVE, DISPATCH, PICK }

data class OrdersUiState(
    val tab: OrdersTab = OrdersTab.RECEIVE,
    val query: String = "",
    val purchaseOrders: List<PurchaseOrder> = emptyList(),
    val salesOrders: List<SalesOrder> = emptyList(),
    val pickLists: List<PickList> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: UiText? = null,
    /** Kept apart so a server without the wmserp_picking app only affects the Pick tab. */
    val pickListError: UiText? = null,
) {
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

/** Lists open Purchase Orders (to receive), Sales Orders (to dispatch) and the user's Pick Lists. */
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOpenPurchaseOrders: GetOpenPurchaseOrdersUseCase,
    private val getOpenSalesOrders: GetOpenSalesOrdersUseCase,
    private val getMyPickLists: GetMyPickListsUseCase,
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

    /** Called when the screen comes back to the foreground (e.g. after finishing a pick list). */
    fun onResumed() {
        if (loadedOnce) load(refresh = true, silent = true)
    }

    fun load(refresh: Boolean = false, silent: Boolean = false, reloadPickLists: Boolean = true) {
        loadJob?.cancel()
        val query = _uiState.value.query
        _uiState.update {
            it.copy(
                isLoading = !refresh && it.purchaseOrders.isEmpty() && it.salesOrders.isEmpty() && it.pickLists.isEmpty(),
                isRefreshing = refresh && !silent,
                error = null,
            )
        }
        loadJob = viewModelScope.launch {
            val po = async { getOpenPurchaseOrders(query) }
            val so = async { getOpenSalesOrders(query) }
            val pl = if (reloadPickLists) async { getMyPickLists() } else null
            val poResult = po.await()
            val soResult = so.await()
            val plResult = pl?.await()
            loadedOnce = true
            val error = listOfNotNull(poResult.errorOrNull(), soResult.errorOrNull()).firstOrNull()?.toUiText()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    purchaseOrders = (poResult as? AppResult.Success)?.data ?: it.purchaseOrders,
                    salesOrders = (soResult as? AppResult.Success)?.data ?: it.salesOrders,
                    pickLists = (plResult as? AppResult.Success)?.data ?: it.pickLists,
                    error = error,
                    pickListError = if (plResult == null) it.pickListError else plResult.errorOrNull()?.toUiText(),
                )
            }
        }
    }

    companion object {
        const val ARG_TAB = "tab"
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
