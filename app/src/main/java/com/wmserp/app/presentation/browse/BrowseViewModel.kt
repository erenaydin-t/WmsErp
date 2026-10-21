package com.wmserp.app.presentation.browse

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.usecase.ListDocumentsUseCase
import com.wmserp.app.domain.usecase.ListItemsUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.common.toUiText
import com.wmserp.app.presentation.navigation.Routes
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

data class BrowseUiState(
    val kind: BrowseKind = BrowseKind.ITEMS,
    /** ISO start date of a period card (*Receipts this month*), shown under the title. */
    val since: String? = null,
    val query: String = "",
    val segment: OrderSegment = OrderSegment.ALL,
    val items: List<Item> = emptyList(),
    val documents: List<OrderDocument> = emptyList(),
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val isLoadingMore: Boolean = false,
    val canLoadMore: Boolean = false,
    val error: UiText? = null,
) {
    val isEmpty: Boolean get() = if (kind.isItemList) items.isEmpty() else documents.isEmpty()
    val showsSegments: Boolean get() = kind == BrowseKind.PENDING_ORDERS
}

/** The filtered list behind a KPI card: items, or documents of one or two doctypes, searchable and pageable. */
@HiltViewModel
class BrowseViewModel @Inject constructor(
    private val listDocuments: ListDocumentsUseCase,
    private val listItems: ListItemsUseCase,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        BrowseUiState(
            kind = BrowseKind.fromName(savedStateHandle.get<String>(Routes.ARG_KIND)) ?: BrowseKind.ITEMS,
            since = savedStateHandle.get<String>(Routes.ARG_SINCE)?.takeIf { it.isNotBlank() },
        )
    )
    val uiState: StateFlow<BrowseUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var page = 0

    init {
        load()
    }

    fun onQueryChange(value: String) {
        _uiState.update { it.copy(query = value) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            load(refresh = true)
        }
    }

    fun selectSegment(segment: OrderSegment) {
        if (_uiState.value.segment == segment) return
        _uiState.update { it.copy(segment = segment) }
        load(refresh = true)
    }

    fun refresh() = load(refresh = true)

    fun load(refresh: Boolean = false) {
        loadJob?.cancel()
        page = 0
        _uiState.update { it.copy(isLoading = !refresh && it.isEmpty, isRefreshing = refresh, isLoadingMore = false, error = null) }
        loadJob = viewModelScope.launch { fetch(start = 0, append = false) }
    }

    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.canLoadMore || loadJob?.isActive == true) return
        _uiState.update { it.copy(isLoadingMore = true) }
        loadJob = viewModelScope.launch { fetch(start = (page + 1) * PAGE_SIZE, append = true) }
    }

    private suspend fun fetch(start: Int, append: Boolean) {
        val state = _uiState.value
        val query = state.query
        if (state.kind.isItemList) {
            when (val result = listItems(query, PAGE_SIZE, start)) {
                is AppResult.Success -> {
                    if (append) page++
                    _uiState.update {
                        it.copy(
                            items = if (append) it.items + result.data else result.data,
                            canLoadMore = result.data.size >= PAGE_SIZE,
                            isLoading = false, isRefreshing = false, isLoadingMore = false, error = null,
                        )
                    }
                }
                is AppResult.Failure -> fail(result.error.toUiText())
            }
            return
        }
        val doctypes = state.segment.doctype?.let { listOf(it) }?.takeIf { state.showsSegments } ?: state.kind.doctypes
        val filter = state.kind.filter(state.since)
        val results = kotlinx.coroutines.coroutineScope {
            doctypes.map { doctype -> async { doctype to listDocuments(doctype, filter, query, PAGE_SIZE, start) } }.map { it.await() }
        }
        val failure = results.firstOrNull { it.second is AppResult.Failure }?.second as? AppResult.Failure
        if (failure != null && results.all { it.second is AppResult.Failure }) {
            fail(failure.error.toUiText())
            return
        }
        val fetched = results.flatMap { (_, r) -> (r as? AppResult.Success)?.data.orEmpty() }
        val merged = if (doctypes.size > 1) fetched.sortedWith(compareByDescending<OrderDocument> { it.date.orEmpty() }.thenBy { it.name }) else fetched
        if (append) page++
        _uiState.update {
            it.copy(
                documents = if (append) (it.documents + merged).distinctBy { d -> d.doctype to d.name } else merged,
                canLoadMore = results.any { (_, r) -> ((r as? AppResult.Success)?.data?.size ?: 0) >= PAGE_SIZE },
                isLoading = false, isRefreshing = false, isLoadingMore = false,
                // One doctype out of two failed (e.g. no permission on Purchase Orders): say so, keep the rest.
                error = failure?.error?.toUiText(),
            )
        }
    }

    private fun fail(error: UiText) = _uiState.update { it.copy(isLoading = false, isRefreshing = false, isLoadingMore = false, error = error) }

    companion object {
        const val PAGE_SIZE = 30
        private const val SEARCH_DEBOUNCE_MS = 350L
    }
}
