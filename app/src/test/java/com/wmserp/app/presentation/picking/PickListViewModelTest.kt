package com.wmserp.app.presentation.picking

import androidx.lifecycle.SavedStateHandle
import com.wmserp.app.R
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.GeneratedDocument
import com.wmserp.app.domain.model.PickList
import com.wmserp.app.domain.model.PickProgressLine
import com.wmserp.app.domain.model.PickRowStatus
import com.wmserp.app.domain.model.PickScanMatch
import com.wmserp.app.domain.model.PickScanMatchType
import com.wmserp.app.domain.model.PickingStatus
import com.wmserp.app.domain.model.ScanSource
import com.wmserp.app.domain.model.ScannedCode
import com.wmserp.app.domain.model.ScannerSettings
import com.wmserp.app.domain.repository.AuthRepository
import com.wmserp.app.domain.repository.PickListRepository
import com.wmserp.app.domain.usecase.CompletePickingUseCase
import com.wmserp.app.domain.usecase.GeneratePickDocumentUseCase
import com.wmserp.app.domain.usecase.GetPickListUseCase
import com.wmserp.app.domain.usecase.ObserveScannerSettingsUseCase
import com.wmserp.app.domain.usecase.ResolvePickScanUseCase
import com.wmserp.app.domain.usecase.SavePickProgressUseCase
import com.wmserp.app.domain.usecase.StartPickingUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.FakeScannerController
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PickListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository: PickListRepository = mockk()
    private val observeSettings: ObserveScannerSettingsUseCase = mockk { every { this@mockk.invoke() } returns flowOf(ScannerSettings()) }
    private val authRepository: AuthRepository = mockk {
        every { session } returns flowOf(TestFixtures.session)
        every { events } returns emptyFlow()
    }
    private val scanner = FakeScannerController()

    private val name = TestFixtures.pickList.name
    private val picking = TestFixtures.pickList.copy(pickingStatus = PickingStatus.PICKING, pickingStartedBy = "user@example.com")

    private fun createViewModel(initial: PickList = TestFixtures.pickList): PickListViewModel {
        coEvery { repository.getPickList(name) } returns AppResult.Success(initial)
        return PickListViewModel(
            GetPickListUseCase(repository),
            StartPickingUseCase(repository),
            SavePickProgressUseCase(repository),
            CompletePickingUseCase(repository),
            GeneratePickDocumentUseCase(repository),
            ResolvePickScanUseCase(repository),
            observeSettings,
            authRepository,
            scanner,
            SavedStateHandle(mapOf(PickListViewModel.ARG_NAME to name)),
        )
    }

    private fun PickListViewModel.line(rowName: String) = uiState.value.lines.first { it.item.rowName == rowName }

    @Test
    fun `a ready pick list only offers Start Picking`() = runTest {
        val vm = createViewModel()

        val state = vm.uiState.value
        assertFalse(state.isLoading)
        assertEquals(PickingStatus.READY_TO_PICK, state.status)
        assertEquals(2, state.lines.size)
        assertTrue(state.canStart)
        assertFalse(state.canSave)
        assertFalse(state.canComplete)
        assertFalse(state.canGenerate)
        assertEquals("user@example.com", state.currentUser)
    }

    @Test
    fun `start picking moves the list into the Picking state`() = runTest {
        coEvery { repository.startPicking(name) } returns AppResult.Success(picking)
        val vm = createViewModel()

        vm.startPicking()

        assertEquals(PickingStatus.PICKING, vm.uiState.value.status)
        assertEquals(UiText.Res(R.string.pick_started_message), vm.uiState.value.message)
        assertFalse(vm.uiState.value.canStart)
    }

    @Test
    fun `scans increment rows locally and Complete stays disabled until every row is full`() = runTest {
        val vm = createViewModel(picking)

        vm.onScanned(ScannedCode("8690000000017", ScanSource.HARDWARE_KEYBOARD))

        assertEquals("1", vm.line("prow1").qtyText)
        assertTrue(vm.line("prow1").highlighted)
        assertEquals(PickRowStatus.PARTIAL, vm.line("prow1").status)
        assertEquals(UiText.Res(R.string.pick_line_added, listOf("ITEM-001")), vm.uiState.value.message)
        assertEquals(1, scanner.feedbackCalls.size)
        assertFalse(vm.uiState.value.canComplete)

        repeat(9) { vm.onScanned(ScannedCode("ITEM-001", ScanSource.HARDWARE_INTENT)) }
        repeat(5) { vm.onScanned(ScannedCode("b-001", ScanSource.CAMERA)) }

        assertEquals("10", vm.line("prow1").qtyText)
        assertEquals("5", vm.line("prow2").qtyText)
        assertEquals("B-001", vm.line("prow2").batchText)
        assertEquals(PickRowStatus.PICKED, vm.line("prow2").status)
        assertTrue(vm.uiState.value.allRowsComplete)
        assertTrue(vm.uiState.value.canComplete)
        coVerify(exactly = 0) { repository.resolveScan(any(), any()) }

        vm.onScanned(ScannedCode("ITEM-001", ScanSource.MANUAL))

        assertEquals("10", vm.line("prow1").qtyText)
        assertEquals(UiText.Res(R.string.pick_line_complete, listOf("ITEM-001", "10")), vm.uiState.value.message)
    }

    @Test
    fun `codes unknown to the device are resolved by the server`() = runTest {
        coEvery { repository.resolveScan(name, "999") } returns AppResult.Success(PickScanMatch(null, "ITEM-999", null, PickScanMatchType.NOT_ON_LIST))
        coEvery { repository.resolveScan(name, "BATCH-X") } returns AppResult.Success(PickScanMatch("prow1", "ITEM-001", "BATCH-X", PickScanMatchType.BATCH))
        val vm = createViewModel(picking)

        vm.onScanned(ScannedCode("999", ScanSource.CAMERA))
        assertEquals(UiText.Res(R.string.pick_not_on_list, listOf("ITEM-999")), vm.uiState.value.error)

        vm.onScanned(ScannedCode("BATCH-X", ScanSource.CAMERA))
        assertEquals("1", vm.line("prow1").qtyText)
        assertEquals("BATCH-X", vm.line("prow1").batchText)
    }

    @Test
    fun `scans are ignored unless the list is being picked`() = runTest {
        val vm = createViewModel()

        vm.onScanned(ScannedCode("ITEM-001", ScanSource.HARDWARE_KEYBOARD))

        assertEquals("0", vm.line("prow1").qtyText)
        assertTrue(scanner.feedbackCalls.isEmpty())
    }

    @Test
    fun `save progress sends the edited quantities and clears the dirty flag`() = runTest {
        val saved = picking.copy(items = picking.items.map { if (it.rowName == "prow1") it.copy(pickedQty = 4.0) else it })
        coEvery { repository.saveProgress(name, listOf(PickProgressLine("prow1", 4.0, null), PickProgressLine("prow2", 0.0, "B-001"))) } returns AppResult.Success(saved)
        val vm = createViewModel(picking)
        vm.setQty("prow1", "4")
        assertTrue(vm.uiState.value.hasUnsavedChanges)
        assertTrue(vm.uiState.value.canSave)

        vm.saveProgress()

        assertEquals(UiText.Res(R.string.pick_saved), vm.uiState.value.message)
        assertFalse(vm.uiState.value.hasUnsavedChanges)
        assertEquals(4.0, vm.line("prow1").item.pickedQty, 0.0)
    }

    @Test
    fun `complete picking then create the purpose specific document exactly once`() = runTest {
        val picked = picking.copy(
            pickingStatus = PickingStatus.PICKED,
            pickingCompletedBy = "user@example.com",
            items = picking.items.map { it.copy(pickedQty = it.requiredQty) },
        )
        coEvery { repository.completePicking(name, listOf(PickProgressLine("prow1", 10.0, null), PickProgressLine("prow2", 5.0, "B-001"))) } returns AppResult.Success(picked)
        coEvery { repository.generateDocument(name) } returns AppResult.Success(GeneratedDocument("Delivery Note", "MAT-DN-00001"))
        val vm = createViewModel(picking)
        vm.setQty("prow1", "10")
        vm.setQty("prow2", "5")

        vm.completePicking()

        assertEquals(PickingStatus.PICKED, vm.uiState.value.status)
        assertEquals(UiText.Res(R.string.pick_completed_message), vm.uiState.value.message)
        assertTrue(vm.uiState.value.canGenerate)
        assertNull(vm.uiState.value.generatedDocument)

        vm.generateDocument()

        assertEquals("MAT-DN-00001", vm.uiState.value.generatedDocument?.name)
        assertEquals(UiText.Res(R.string.pick_document_created, listOf("Delivery Note", "MAT-DN-00001")), vm.uiState.value.message)
        assertFalse(vm.uiState.value.canGenerate)
        coVerify(exactly = 1) { repository.generateDocument(name) }
    }
}
