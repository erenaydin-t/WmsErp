package com.wmserp.app.presentation.browse

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DocumentFilter
import com.wmserp.app.domain.model.Item
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.usecase.ListDocumentsUseCase
import com.wmserp.app.domain.usecase.ListItemsUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.presentation.navigation.Routes
import com.wmserp.app.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class BrowseViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val listDocuments: ListDocumentsUseCase = mockk()
    private val listItems: ListItemsUseCase = mockk()

    private fun vm(kind: BrowseKind, since: String? = null) = BrowseViewModel(
        listDocuments, listItems,
        SavedStateHandle(mapOf(Routes.ARG_KIND to kind.name, Routes.ARG_SINCE to since)),
    )

    private fun doc(doctype: OrderDoctype, name: String, date: String) = OrderDocument(doctype = doctype, name = name, partyName = "P", date = date)

    @Test
    fun `pending orders merge sales and purchase orders newest first and keep a partial failure visible`() = runTest {
        coEvery { listDocuments(OrderDoctype.SALES_ORDER, DocumentFilter(open = true), "", 30, 0) } returns
            AppResult.Success(listOf(doc(OrderDoctype.SALES_ORDER, "SO-1", "2026-10-02"), doc(OrderDoctype.SALES_ORDER, "SO-2", "2026-09-20")))
        coEvery { listDocuments(OrderDoctype.PURCHASE_ORDER, DocumentFilter(open = true), "", 30, 0) } returns
            AppResult.Success(listOf(doc(OrderDoctype.PURCHASE_ORDER, "PO-1", "2026-10-01")))

        val vm = vm(BrowseKind.PENDING_ORDERS)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(listOf("SO-1", "PO-1", "SO-2"), state.documents.map { it.name })
        assertFalse(state.canLoadMore)
        assertNull(state.error)
        assertTrue(state.showsSegments)

        // Purchase Orders refused: the Sales Orders stay, the error is shown.
        coEvery { listDocuments(OrderDoctype.PURCHASE_ORDER, any(), any(), any(), any()) } returns AppResult.Failure(AppError.Forbidden("no"))
        vm.refresh()
        assertEquals(listOf("SO-1", "SO-2"), vm.uiState.value.documents.map { it.name })
        assertEquals(UiText.Res(R.string.error_forbidden), vm.uiState.value.error)

        // Only one doctype once a segment is chosen.
        vm.selectSegment(OrderSegment.SALES)
        assertEquals(listOf("SO-1", "SO-2"), vm.uiState.value.documents.map { it.name })
        assertNull(vm.uiState.value.error)
        // Loaded at start and on refresh, not again for the Sales segment.
        coVerify(exactly = 2) { listDocuments(OrderDoctype.PURCHASE_ORDER, DocumentFilter(open = true), "", 30, 0) }
    }

    @Test
    fun `period cards pass their start date and page through the list`() = runTest {
        val firstPage = (1..30).map { doc(OrderDoctype.PURCHASE_RECEIPT, "PR-$it", "2026-10-${(it % 28 + 1).toString().padStart(2, '0')}") }
        coEvery { listDocuments(OrderDoctype.PURCHASE_RECEIPT, DocumentFilter(submittedSince = "2026-10-01"), "", 30, 0) } returns AppResult.Success(firstPage)
        coEvery { listDocuments(OrderDoctype.PURCHASE_RECEIPT, DocumentFilter(submittedSince = "2026-10-01"), "", 30, 30) } returns
            AppResult.Success(listOf(doc(OrderDoctype.PURCHASE_RECEIPT, "PR-31", "2026-10-01")))

        val vm = vm(BrowseKind.RECEIPTS, since = "2026-10-01")

        assertEquals("2026-10-01", vm.uiState.value.since)
        assertEquals(30, vm.uiState.value.documents.size)
        assertTrue(vm.uiState.value.canLoadMore)

        vm.loadMore()

        assertEquals(31, vm.uiState.value.documents.size)
        assertFalse(vm.uiState.value.canLoadMore)
        assertFalse(vm.uiState.value.isLoadingMore)
    }

    @Test
    fun `the item list searches after a short pause and reports failures`() = runTest {
        coEvery { listItems("", 30, 0) } returns AppResult.Success(listOf(Item(code = "A", name = "Alpha")))
        coEvery { listItems("bolt", 30, 0) } returns AppResult.Failure(AppError.Network("offline"))

        val vm = vm(BrowseKind.ITEMS)
        assertEquals(listOf("A"), vm.uiState.value.items.map { it.code })
        assertFalse(vm.uiState.value.showsSegments)

        vm.onQueryChange("bolt")
        advanceTimeBy(500)

        assertEquals(UiText.Res(R.string.error_network_unreachable), vm.uiState.value.error)
        assertFalse(vm.uiState.value.isRefreshing)
        coVerify(exactly = 1) { listItems("bolt", 30, 0) }
    }
}
