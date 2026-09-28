package com.wmserp.app.presentation

import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.SessionEvent
import com.wmserp.app.domain.model.UserSession
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.usecase.RestoreSessionUseCase
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MainViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val restore: RestoreSessionUseCase = mockk()
    private val sessionFlow = MutableStateFlow<UserSession?>(null)
    private val sessionEvents = MutableSharedFlow<SessionEvent>()
    private val authRepository: AuthRepository = mockk {
        every { session } returns sessionFlow
        every { events } returns sessionEvents
    }

    @Test
    fun `restored session signs the user in`() = runTest {
        coEvery { restore() } returns AppResult.Success(TestFixtures.session)
        val vm = MainViewModel(restore, authRepository)
        assertTrue(vm.uiState.value.status is SessionStatus.SignedIn)
    }

    @Test
    fun `no stored session shows login, later login signs in`() = runTest {
        coEvery { restore() } returns AppResult.Success(null)
        val vm = MainViewModel(restore, authRepository)
        assertEquals(SessionStatus.SignedOut(), vm.uiState.value.status)

        sessionFlow.value = TestFixtures.session
        assertTrue(vm.uiState.value.status is SessionStatus.SignedIn)
    }

    @Test
    fun `expiry without remembered credentials signs out with a message`() = runTest {
        coEvery { restore() } returns AppResult.Success(TestFixtures.session) andThen AppResult.Success(null)
        val vm = MainViewModel(restore, authRepository)

        sessionEvents.emit(SessionEvent.Expired)

        val status = vm.uiState.value.status as SessionStatus.SignedOut
        assertEquals(MainViewModel.SESSION_EXPIRED_MESSAGE, status.message)
        vm.consumeMessage()
        assertEquals(SessionStatus.SignedOut(), vm.uiState.value.status)
    }

    @Test
    fun `logout event signs out silently`() = runTest {
        coEvery { restore() } returns AppResult.Success(TestFixtures.session)
        val vm = MainViewModel(restore, authRepository)

        sessionEvents.emit(SessionEvent.LoggedOut)

        assertEquals(SessionStatus.SignedOut(), vm.uiState.value.status)
    }
}
