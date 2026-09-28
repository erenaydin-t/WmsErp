package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.PurchaseOrder
import com.wmserp.app.domain.model.SalesOrder
import com.wmserp.app.domain.usecase.GetOpenPurchaseOrdersUseCase
import com.wmserp.app.domain.usecase.GetOpenSalesOrdersUseCase
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

enum class OrdersTab(val title: String) { RECEIVE("Receive"), DISPATCH("Dispatch") }

data class OrdersUiState(
    val tab: OrdersTab = OrdersTab.RECEIVE,
    val query: String = "",
    val purchaseOrders: List<PurchaseOrder> = emptyList(),
    val salesOrders: List<SalesOrder> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

/** Lists open Purchase Orders (to receive) and Sales Orders (to dispatch). */
@HiltViewModel
class OrdersViewModel @Inject constructor(
    private val getOpenPurchaseOrders: GetOpenPurchaseOrdersUseCase,
    private val getOpenSalesOrders: GetOpenSalesOrdersUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        OrdersUiState(tab = savedStateHandle.get<String>(ARG_TAB)?.let { runCatching { OrdersTab.valueOf(it) }.getOrNull() } ?: OrdersTab.RECEIVE)
    )
    val uiState: StateFlow<OrdersUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null

    init {
        load()
    }

    fun selectTab(tab: OrdersTab) = _uiState.update { it.copy(tab = tab) }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load(refresh = true)
        }
    }

    fun refresh() = load(refresh = true)

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        val query = _uiState.value.query
        _uiState.update { it.copy(isLoading = !refresh && it.purchaseOrders.isEmpty() && it.salesOrders.isEmpty(), isRefreshing = refresh, error = null) }
        loadJob = viewModelScope.launch {
            val po = async { getOpenPurchaseOrders(query) }
            val so = async { getOpenSalesOrders(query) }
            val poResult = po.await()
            val soResult = so.await()
            val error = listOfNotNull(poResult.errorOrNull(), soResult.errorOrNull()).firstOrNull()?.message
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isRefreshing = false,
                    purchaseOrders = (poResult as? AppResult.Success)?.data ?: it.purchaseOrders,
                    salesOrders = (soResult as? AppResult.Success)?.data ?: it.salesOrders,
                    error = error,
                )
            }
        }
    }

    companion object {
        const val ARG_TAB = "tab"
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
