package com.wmserp.app.presentation.dashboard

import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.DashboardData
import com.wmserp.app.domain.usecase.GetDashboardUseCase
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val getDashboard: GetDashboardUseCase = mockk()
    private val authRepository: AuthRepository = mockk {
        every { session } returns flowOf(TestFixtures.session)
        every { events } returns emptyFlow()
    }

    private val data = DashboardData(
        kpis = DashboardKpis(totalItems = 120, pendingOrders = 7, revenue = 15000.0, currency = "USD", dispatched = 12, periodLabel = "Sep 2026"),
        recentActivity = emptyList(),
    )

    @Test
    fun `loads kpis and greets the user by first name`() = runTest {
        coEvery { getDashboard() } returns AppResult.Success(data)

        val vm = DashboardViewModel(getDashboard, authRepository)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Eren", state.greetingName)
        assertEquals(120, state.data?.kpis?.totalItems)
        assertNull(state.error)
    }

    @Test
    fun `surfaces errors and recovers on refresh`() = runTest {
        coEvery { getDashboard() } returns AppResult.Failure(AppError.Network("offline")) andThen AppResult.Success(data)

        val vm = DashboardViewModel(getDashboard, authRepository)
        assertEquals("offline", vm.uiState.value.error)
        assertNull(vm.uiState.value.data)

        vm.refresh()

        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.data)
        assertFalse(vm.uiState.value.isRefreshing)
    }
}
