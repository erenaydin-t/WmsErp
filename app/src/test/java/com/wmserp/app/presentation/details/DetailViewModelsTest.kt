package com.wmserp.app.presentation.details

import com.wmserp.app.R
import com.wmserp.app.domain.common.AppError
import com.wmserp.app.domain.common.AppResult
import com.wmserp.app.domain.model.ItemDetail
import com.wmserp.app.domain.model.OrderDoctype
import com.wmserp.app.domain.model.OrderDocument
import com.wmserp.app.domain.model.OrderLine
import com.wmserp.app.domain.usecase.GetDocumentUseCase
import com.wmserp.app.domain.usecase.GetItemDetailUseCase
import com.wmserp.app.presentation.common.UiText
import com.wmserp.app.testutil.MainDispatcherRule
import com.wmserp.app.testutil.TestFixtures
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class DetailViewModelsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `item sheet opens with the item and its stock, closes empty, and reports failures`() = runTest {
        val getItemDetail: GetItemDetailUseCase = mockk()
        coEvery { getItemDetail("ITEM-001") } returns AppResult.Success(ItemDetail(TestFixtures.item, TestFixtures.stock))
        coEvery { getItemDetail("GONE") } returns AppResult.Failure(AppError.NotFound("gone"))
        val vm = ItemDetailViewModel(getItemDetail)
        assertFalse(vm.uiState.value.isOpen)

        vm.open(" ITEM-001 ")

        val state = vm.uiState.value
        assertTrue(state.isOpen)
        assertFalse(state.isLoading)
        assertEquals(150.0, state.detail!!.totalQty, 0.0)
        assertEquals(20.0, state.detail!!.reservedQty, 0.0)

        vm.open("GONE")
        assertEquals(UiText.Res(R.string.error_not_found), vm.uiState.value.error)
        assertNull(vm.uiState.value.detail)

        vm.close()
        assertFalse(vm.uiState.value.isOpen)
    }

    @Test
    fun `document sheet shows the list entry at once and replaces it with the loaded rows`() = runTest {
        val preview = OrderDocument(OrderDoctype.SALES_ORDER, "SO-1", partyName = "Globex", itemCount = 2, totalQty = 7.0)
        val loaded = preview.copy(items = listOf(OrderLine("r1", 1, "A", "Alpha", 7.0, doneQty = 2.0)))
        val getDocument: GetDocumentUseCase = mockk()
        coEvery { getDocument(OrderDoctype.SALES_ORDER, "SO-1") } returns AppResult.Success(loaded)
        val vm = DocumentDetailViewModel(getDocument)

        vm.open(preview)

        val state = vm.uiState.value
        assertEquals(DocumentRef(OrderDoctype.SALES_ORDER, "SO-1"), state.ref)
        assertEquals(1, state.document!!.items.size)
        assertEquals(5.0, state.document!!.items.first().remainingQty, 0.0)
        assertFalse(state.isLoading)

        coEvery { getDocument(OrderDoctype.PICK_LIST, "PL-1") } returns AppResult.Failure(AppError.Forbidden("no"))
        vm.open(OrderDoctype.PICK_LIST, "PL-1")
        assertEquals(UiText.Res(R.string.error_forbidden), vm.uiState.value.error)
        assertNull(vm.uiState.value.document)
    }
}
