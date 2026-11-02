package com.wmserp.app.presentation.orders

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.usecase.GetMyPickListsUseCase
import com.wmserp.app.domain.usecase.GetMyStocktakingSessionsUseCase
import com.wmserp.app.domain.usecase.GetReceivableReceiptsUseCase
import com.wmserp.app.domain.usecase.PendingCountQueue
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class OrdersViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getReceipts: GetReceivableReceiptsUseCase = mockk { coEvery { this@mockk.invoke(any()) } returns AppResult.Success(emptyList()) }
    private val getPickLists: GetMyPickListsUseCase = mockk()
    private val getSessions: GetMyStocktakingSessionsUseCase = mockk { coEvery { this@mockk.invoke() } returns AppResult.Success(emptyList()) }
    private val queue: PendingCountQueue = mockk { coEvery { pending(any()) } returns emptyList() }

    private val open = TestFixtures.pickList
    private val picked = open.copy(name = "STO-PICK-2026-00013", pickingStatus = PickingStatus.PICKED, allRowsPicked = true)
    private val documented = open.copy(name = "STO-PICK-2026-00014", generatedDocument = GeneratedDocument("Delivery Note", "MAT-DN-00009"))

    private fun createViewModel(): OrdersViewModel =
        OrdersViewModel(getReceipts, getPickLists, getSessions, queue, SavedStateHandle(mapOf(OrdersViewModel.ARG_TAB to OrdersTab.PICK.name)))

    @Test
    fun `completed pick lists are hidden by default and shown on request, open ones first`() = runTest {
        coEvery { getPickLists() } returns AppResult.Success(listOf(picked, open, documented))
        val vm = createViewModel()

        val state = vm.uiState.value
        assertEquals(OrdersTab.PICK, state.tab)
        assertEquals(listOf(open.name), state.filteredPickLists.map { it.name })
        assertEquals(2, state.completedPickCount)
        assertFalse(state.showCompletedPicks)

        vm.toggleCompletedPicks()

        assertTrue(vm.uiState.value.showCompletedPicks)
        assertEquals(listOf(open.name, picked.name, documented.name), vm.uiState.value.filteredPickLists.map { it.name })

        vm.toggleCompletedPicks()
        assertEquals(listOf(open.name), vm.uiState.value.filteredPickLists.map { it.name })
    }

    @Test
    fun `the search box still finds a completed pick list once they are shown`() = runTest {
        coEvery { getPickLists() } returns AppResult.Success(listOf(open, picked))
        val vm = createViewModel()

        vm.onQueryChange("00013")
        assertTrue(vm.uiState.value.filteredPickLists.isEmpty())

        vm.toggleCompletedPicks()
        assertEquals(listOf(picked.name), vm.uiState.value.filteredPickLists.map { it.name })
    }
}
