package com.wmserp.app.presentation.stocktaking

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountOutcome
import com.wmserp.app.domain.model.CountRejection
import com.wmserp.app.domain.model.CountResult
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountType
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.model.StocktakingItemsPage
import com.wmserp.app.domain.model.StocktakingLookup
import com.wmserp.app.domain.model.StocktakingSession
import com.wmserp.app.domain.model.StocktakingStatus
import com.wmserp.app.domain.model.StocktakingTotals
import com.wmserp.app.domain.model.SyncOutcome
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.repository.StocktakingRepository
import com.wmserp.app.domain.usecase.CountEvaluator
import com.wmserp.app.domain.usecase.LoadStocktakingSessionUseCase
import com.wmserp.app.domain.usecase.LookupStocktakingScanUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.PendingCountQueue
import com.wmserp.app.domain.usecase.SyncPendingCountsUseCase
import com.wmserp.app.domain.usecase.UpdateCachedStocktakingUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.InMemoryStocktakingLocalStore
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.StocktakingFixtures
import com.wmserp.app.testutil.StocktakingFixtures.USER
import com.wmserp.app.testutil.TestFixtures
import com.wmserp.app.testutil.qrLabel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CountingViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: StocktakingRepository = mockk()
    private val store = InMemoryStocktakingLocalStore()
    private val scanner = FakeScannerController(hasHardwareScanner = true)
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } returns flowOf(ScannerSettings()) }
    private val authRepository: AuthRepository = mockk {
        every { session } returns flowOf(TestFixtures.session)
        every { events } returns emptyFlow()
    }
    private val name = StocktakingFixtures.session.name
    private var refCounter = 0

    /** A fake server that applies the same rules as the device and remembers its rows between calls. */
    private val serverRows = StocktakingFixtures.items.associateBy { it.name }.toMutableMap()

    private fun serverAgrees() {
        coEvery { repository.syncCounts(name, any()) } answers {
            val queued = secondArg<List<CountSubmission>>()
            val results = queued.map { s ->
                val row = serverRows[s.itemName] ?: serverRows.values.first { it.itemCode == s.itemCode && it.batchNo == s.batchNo }
                val evaluated = CountEvaluator.evaluate(row, s.qty, StocktakingFixtures.session, USER, s.deviceTime)
                serverRows[row.name] = evaluated.item
                CountResult(evaluated.outcome, evaluated.item, StocktakingTotals(total = 4, counted = 2, uncounted = 2), StocktakingStatus.COUNTING, clientRef = s.clientRef)
            }
            AppResult.Success(SyncOutcome(results, StocktakingTotals(total = 4, counted = 2, uncounted = 2), StocktakingStatus.COUNTING))
        }
    }

    private fun createViewModel(online: Boolean = true, session: StocktakingSession = StocktakingFixtures.session): CountingViewModel {
        if (online) {
            coEvery { repository.getSession(name) } returns AppResult.Success(session)
            coEvery { repository.getItems(name, 0, any(), true) } returns AppResult.Success(StocktakingItemsPage(StocktakingFixtures.items, StocktakingFixtures.barcodes, 0, 1000, 4))
        } else {
            coEvery { repository.getSession(name) } returns AppResult.Failure(AppError.Network("offline"))
            coEvery { repository.syncCounts(name, any()) } returns AppResult.Failure(AppError.Network("offline"))
        }
        val vm = CountingViewModel(
            LoadStocktakingSessionUseCase(repository, store),
            SyncPendingCountsUseCase(repository, store),
            PendingCountQueue(store),
            UpdateCachedStocktakingUseCase(store),
            LookupStocktakingScanUseCase(repository),
            observeSettings,
            authRepository,
            scanner,
            SavedStateHandle(mapOf(CountingViewModel.ARG_NAME to name)),
        )
        vm.autoRetry = false
        vm.clock = { 1_700_000_000_000L }
        vm.newClientRef = { "ref-${++refCounter}" }
        return vm
    }

    private fun CountingViewModel.item(rowName: String) = uiState.value.items.first { it.name == rowName }
    private fun CountingViewModel.scan(code: String) = onScanned(ScannedCode(code, ScanSource.HARDWARE_KEYBOARD))

    @Test
    fun `loads the session and its rows and waits for a scan`() = runTest {
        val vm = createViewModel()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(4, state.items.size)
        assertTrue(state.canScan)
        assertTrue(state.phase is CountingPhase.Scanning)
        assertEquals(3, state.myTotal)
        assertEquals(3, state.myOpen)
        assertFalse(state.fromCache)
        assertEquals(USER, state.userId)
        assertNotNull(store.sessions[name])
    }

    @Test
    fun `scan, count, submit, ready for the next scan - and the count reaches the server`() = runTest {
        serverAgrees()
        val vm = createViewModel()

        vm.scan(qrLabel("PCT-500", "PCT-250901"))
        val counting = vm.uiState.value.counting
        assertNotNull(counting)
        assertEquals("r1", counting!!.item.name)
        assertEquals(CountType.COUNT_1, counting.countType)
        assertEquals(100.0, counting.item.erpQty)
        assertEquals(1, scanner.feedbackCalls.size)

        vm.useErpQty()
        assertEquals("100", vm.uiState.value.counting?.qtyText)
        vm.submitCount()

        val state = vm.uiState.value
        assertTrue(state.phase is CountingPhase.Scanning)
        assertEquals(FeedbackKind.SUCCESS, state.feedback?.kind)
        assertEquals(UiText.Res(R.string.st_accepted), state.feedback?.title)
        assertEquals(CountItemStatus.COUNTED, vm.item("r1").status)
        assertEquals(100.0, vm.item("r1").finalQty)
        assertEquals(2, state.totals.counted)
        assertTrue(state.pending.isEmpty())
        assertFalse(state.isSyncing)
        assertEquals("r1", state.lastCount?.name)
        coVerify(exactly = 1) { repository.syncCounts(name, match { it.size == 1 && it[0].clientRef == "ref-1" && it[0].qty == 100.0 && it[0].itemName == "r1" }) }
        assertTrue(store.readPending(name).isEmpty())
        assertEquals(CountItemStatus.COUNTED, store.sessions[name]!!.items.first { it.name == "r1" }.status)
    }

    @Test
    fun `a mismatch asks for a second count and a differing second count goes to the manager`() = runTest {
        serverAgrees()
        val vm = createViewModel()

        vm.scan(qrLabel("PCT-500", "PCT-250901"))
        vm.setQtyText("95")
        vm.submitCount()

        var counting = vm.uiState.value.counting
        assertNotNull(counting)
        assertTrue(counting!!.secondCount)
        assertEquals(CountType.COUNT_2, counting.countType)
        assertEquals("", counting.qtyText)
        assertEquals(95.0, counting.item.count1)
        assertEquals(CountItemStatus.RECOUNT_REQUIRED, vm.item("r1").status)
        assertNull(vm.uiState.value.feedback)

        vm.setQtyText("97")
        vm.submitCount()

        val state = vm.uiState.value
        assertTrue(state.phase is CountingPhase.Scanning)
        assertEquals(FeedbackKind.INFO, state.feedback?.kind)
        assertEquals(UiText.Res(R.string.st_sent_review), state.feedback?.title)
        assertEquals(UiText.Res(R.string.st_sent_review_detail, listOf("95", "97")), state.feedback?.detail)
        assertEquals(CountItemStatus.MANAGER_REVIEW, vm.item("r1").status)
        assertEquals(97.0, vm.item("r1").finalQty)
        coVerify(exactly = 2) { repository.syncCounts(name, any()) }
        counting = vm.uiState.value.counting
        assertNull(counting)
    }

    @Test
    fun `offline counts are saved locally, shown as pending and synced later`() = runTest {
        coEvery { repository.syncCounts(name, any()) } returns AppResult.Failure(AppError.Network("offline"))
        val vm = createViewModel()

        vm.scan(qrLabel("GZ-10"))
        vm.setQtyText("50")
        vm.submitCount()

        var state = vm.uiState.value
        assertTrue(state.phase is CountingPhase.Scanning)
        assertEquals(1, state.pendingCount)
        assertEquals(CountItemStatus.COUNTED, vm.item("r3").status)
        assertEquals(FeedbackKind.SUCCESS, state.feedback?.kind)
        assertEquals(1, store.readPending(name).size)

        // The network comes back: the queue drains and the server's row replaces the local guess.
        serverAgrees()
        vm.syncNow()

        state = vm.uiState.value
        assertEquals(0, state.pendingCount)
        assertEquals(CountItemStatus.COUNTED, vm.item("r3").status)
        assertEquals(2, state.totals.counted)
        assertTrue(store.readPending(name).isEmpty())
    }

    @Test
    fun `without a network the device copy is used and a JSON label unknown to it can still be counted`() = runTest {
        store.writeSession(StocktakingFixtures.cache())
        val vm = createViewModel(online = false)

        val state = vm.uiState.value
        assertTrue(state.fromCache)
        assertEquals(4, state.items.size)
        assertTrue(state.canScan)

        vm.scan(qrLabel("NEW-1", "B-9"))
        val counting = vm.uiState.value.counting
        assertNotNull(counting)
        assertTrue(counting!!.isNew)
        assertEquals("NEW-1", counting.item.itemCode)
        vm.setQtyText("3")
        vm.submitCount()

        val pending = store.readPending(name)
        assertEquals(1, pending.size)
        assertNull(pending.first().itemName)
        assertEquals("NEW-1", pending.first().itemCode)
        assertEquals("B-9", pending.first().batchNo)
        assertEquals("Main - C", pending.first().warehouse)
        assertEquals(5, vm.uiState.value.items.size)

        vm.scan("0000000")
        assertEquals(FeedbackKind.ERROR, vm.uiState.value.feedback?.kind)
        assertEquals(UiText.Res(R.string.st_unknown_offline), vm.uiState.value.feedback?.title)
    }

    @Test
    fun `rows counted by someone else or assigned to someone else are refused with the name`() = runTest {
        val vm = createViewModel()

        vm.scan(qrLabel("ASP-100", "ASP-1"))

        var feedback = vm.uiState.value.feedback
        assertEquals(FeedbackKind.ERROR, feedback?.kind)
        assertEquals(UiText.Res(R.string.st_already_counted, listOf("Reza")), feedback?.title)
        assertTrue(vm.uiState.value.phase is CountingPhase.Scanning)
        assertEquals(1, scanner.errorFeedbackCalls)

        val reassigned = createViewModel().also { other ->
            coEvery { repository.getItems(name, 0, any(), true) } returns AppResult.Success(
                StocktakingItemsPage(listOf(StocktakingFixtures.gauze.copy(counter = "reza@example.com", counterName = "Reza", isMine = false)), StocktakingFixtures.barcodes, 0, 1000, 1)
            )
            other.load()
        }
        reassigned.scan("8690000000017")
        feedback = reassigned.uiState.value.feedback
        assertEquals(UiText.Res(R.string.st_assigned_to_other, listOf("Reza")), feedback?.title)
    }

    @Test
    fun `a plain item code of a batch item asks for the batch and the choice opens the count`() = runTest {
        val vm = createViewModel()

        vm.scan("PCT-500")

        val phase = vm.uiState.value.phase
        assertTrue(phase is CountingPhase.ChoosingBatch)
        assertEquals(listOf("r1", "r2"), (phase as CountingPhase.ChoosingBatch).rows.map { it.name })

        vm.selectRow("r2")

        assertEquals("r2", vm.uiState.value.counting?.item?.name)
        assertEquals(20.0, vm.uiState.value.counting?.item?.erpQty)
        vm.cancelCounting()
        assertTrue(vm.uiState.value.phase is CountingPhase.Scanning)
    }

    @Test
    fun `an unknown barcode is looked up on the server and a new row can be counted`() = runTest {
        serverAgrees()
        coEvery { repository.lookup(name, "8690000000099") } returns AppResult.Success(
            StocktakingLookup(found = true, raw = "8690000000099", itemCode = "NEW-1", itemName = "New product", uom = "Nos", canAdd = true, inSession = false)
        )
        val vm = createViewModel()

        vm.scan("8690000000099")

        val counting = vm.uiState.value.counting
        assertNotNull(counting)
        assertTrue(counting!!.isNew)
        assertEquals("New product", counting.item.itemName)
        assertNull(counting.item.erpQty)

        coEvery { repository.lookup(name, "0000000") } returns AppResult.Success(StocktakingLookup(found = false, raw = "0000000"))
        vm.cancelCounting()
        vm.scan("0000000")
        assertEquals(UiText.Res(R.string.st_unknown_scan, listOf("0000000")), vm.uiState.value.feedback?.title)
    }

    @Test
    fun `a count the server refuses while syncing is dropped and reported`() = runTest {
        coEvery { repository.syncCounts(name, any()) } answers {
            val queued = secondArg<List<CountSubmission>>()
            AppResult.Success(
                SyncOutcome(
                    queued.map { s ->
                        CountResult(CountOutcome.REJECTED, StocktakingFixtures.gauze.copy(status = CountItemStatus.COUNTED, countedBy = "reza@example.com", countedByName = "Reza"), rejection = CountRejection.ALREADY_COUNTED, message = "GZ-10 has already been counted by Reza.", countedByName = "Reza", clientRef = s.clientRef)
                    }
                )
            )
        }
        val vm = createViewModel()

        vm.scan("8690000000017")
        vm.setQtyText("49")
        vm.submitCount()

        val state = vm.uiState.value
        assertEquals(0, state.pendingCount)
        assertEquals(FeedbackKind.ERROR, state.feedback?.kind)
        assertEquals(UiText.Res(R.string.st_sync_refused, listOf("1", "GZ-10 has already been counted by Reza.")), state.feedback?.title)
        assertEquals(UiText.Res(R.string.st_already_counted, listOf("Reza")), state.feedback?.detail)
        assertEquals("Reza", vm.item("r3").countedByName)
        assertEquals(1, scanner.errorFeedbackCalls)
    }

    @Test
    fun `scans are ignored while a count is open and once counting is closed`() = runTest {
        val vm = createViewModel()
        vm.scan(qrLabel("PCT-500", "PCT-250901"))
        vm.scan(qrLabel("GZ-10"))
        assertEquals("r1", vm.uiState.value.counting?.item?.name)

        val closed = createViewModel(session = StocktakingFixtures.session.copy(status = StocktakingStatus.MANAGER_REVIEW))
        assertFalse(closed.uiState.value.canScan)
        assertFalse(closed.uiState.value.sessionOpen)
    }
}
