package com.wmserp.app.presentation.dashboard

import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.core.update.AppUpdateManager
import com.wmserp.app.domain.model.InstalledVersion
import com.wmserp.app.domain.model.UpdateState
import com.wmserp.app.domain.repository.AppUpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.DashboardKpis
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.DashboardData
import com.wmserp.app.domain.model.PickerKpis
import com.wmserp.app.domain.usecase.GetDashboardUseCase
import com.wmserp.app.domain.usecase.GetMyStocktakingSessionsUseCase
import com.wmserp.app.domain.usecase.GetPickerKpisUseCase
import com.wmserp.app.presentation.common.UiText
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
    private val getPickerKpis: GetPickerKpisUseCase = mockk {
        coEvery { this@mockk.invoke() } returns AppResult.Failure(AppError.NotFound("wmserp_picking not installed"))
    }
    private val getMyStocktaking: GetMyStocktakingSessionsUseCase = mockk {
        coEvery { this@mockk.invoke() } returns AppResult.Failure(AppError.NotFound("wmserp_picking not installed"))
    }
    private val authRepository: AuthRepository = mockk {
        every { session } returns flowOf(TestFixtures.session)
        every { events } returns emptyFlow()
    }

    private val updateRepository: AppUpdateRepository = mockk {
        coEvery { getLatestRelease() } returns AppResult.Success(null)
    }
    private val updateManager = AppUpdateManager(
        updateRepository,
        object : InstalledVersion {
            override val versionName = "1.1.0-dev"
            override val versionCode = 1L
        },
        CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    )

    private val data = DashboardData(
        kpis = DashboardKpis(totalItems = 120, pendingOrders = 7, receipts = 9, dispatched = 12, periodLabel = "Sep 2026"),
        recentActivity = emptyList(),
    )

    @Test
    fun `loads kpis and greets the user by first name`() = runTest {
        coEvery { getDashboard() } returns AppResult.Success(data)

        val vm = DashboardViewModel(getDashboard, getPickerKpis, getMyStocktaking, authRepository, updateManager)

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals("Eren", state.greetingName)
        assertEquals(120, state.data?.kpis?.totalItems)
        assertNull(state.error)
        // The updater is asked for the latest GitHub release as soon as the dashboard exists.
        assertEquals(UpdateState.NoRelease, vm.updateState.value)
    }

    @Test
    fun `surfaces errors and recovers on refresh`() = runTest {
        coEvery { getDashboard() } returns AppResult.Failure(AppError.Network("offline")) andThen AppResult.Success(data)

        val vm = DashboardViewModel(getDashboard, getPickerKpis, getMyStocktaking, authRepository, updateManager)
        assertEquals(UiText.Res(R.string.error_network_unreachable), vm.uiState.value.error)
        assertNull(vm.uiState.value.data)

        vm.refresh()

        assertNull(vm.uiState.value.error)
        assertNotNull(vm.uiState.value.data)
        assertFalse(vm.uiState.value.isRefreshing)
    }

    @Test
    fun `picker KPIs are shown when the picking app answers and hidden otherwise`() = runTest {
        coEvery { getDashboard() } returns AppResult.Success(data)
        coEvery { getPickerKpis() } returns AppResult.Success(PickerKpis(date = "2026-09-28", rowsPicked = 7, avgSecondsPerRow = 95.0))

        val vm = DashboardViewModel(getDashboard, getPickerKpis, getMyStocktaking, authRepository, updateManager)

        assertEquals(7, vm.uiState.value.pickerKpis?.rowsPicked)

        coEvery { getPickerKpis() } returns AppResult.Failure(AppError.NotFound("gone"))
        val without = DashboardViewModel(getDashboard, getPickerKpis, getMyStocktaking, authRepository, updateManager)
        assertNull(without.uiState.value.pickerKpis)
    }
}
