package com.wmserp.app.presentation.stocktaking

import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.testutil.StocktakingFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** *Include Zero Stock*: zero rows nobody counted are optional and hidden until the counter shows them. */
class CountingZeroStockTest {

    private val zero = StocktakingFixtures.paracetamolB1.copy(name = "z1", batchNo = "PCT-ZERO", erpQty = 0.0, zeroStock = true, isMine = true)
    private val zeroCounted = zero.copy(name = "z2", batchNo = "PCT-FOUND", status = CountItemStatus.COUNTED, count1 = 10.0, finalQty = 10.0)
    private val stocked = StocktakingFixtures.paracetamolB2.copy(isMine = true)
    private val state = CountingUiState(
        isLoading = false,
        session = StocktakingFixtures.session,
        items = listOf(zero, zeroCounted, stocked),
        query = "paracetamol",
        userId = StocktakingFixtures.USER,
    )

    @Test
    fun `uncounted zero rows are optional, counted ones are not`() {
        assertTrue(zero.isOptionalZero)
        assertFalse(zeroCounted.isOptionalZero)
        assertFalse(stocked.isOptionalZero)
        assertEquals(1, state.optionalZeroCount)
    }

    @Test
    fun `search hides optional zero rows until the filter is on`() {
        assertEquals(listOf("z2", stocked.name), state.searchResults.map { it.name })
        assertEquals(listOf("z1", "z2", stocked.name), state.copy(showZeroStock = true).searchResults.map { it.name })
    }

    @Test
    fun `a scanned item with only zero rows still offers them`() {
        assertEquals(listOf("z1"), state.visibleRows(listOf(zero), keepWhenAllHidden = true).map { it.name })
        assertEquals(emptyList<String>(), state.visibleRows(listOf(zero)).map { it.name })
    }

    @Test
    fun `optional zero rows are not part of my open work`() {
        assertEquals(2, state.myTotal)
        assertEquals(1, state.myOpen)
    }
}
