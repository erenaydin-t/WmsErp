package com.wmserp.app.data.local

import com.wmserp.app.domain.model.CountItemStatus
import com.wmserp.app.domain.model.CountSubmission
import com.wmserp.app.domain.model.CountingMode
import com.wmserp.app.testutil.StocktakingFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileStocktakingLocalStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun store() = FileStocktakingLocalStore(File(folder.root, "stocktaking"))

    @Test
    fun `rows survive a round trip with every field`() = runTest {
        val store = store()
        store.writeSession(StocktakingFixtures.cache())

        val loaded = store().readSession("ST-2026-0001")!!

        assertEquals(StocktakingFixtures.session, loaded.session)
        assertEquals(StocktakingFixtures.items, loaded.items)
        assertEquals(StocktakingFixtures.barcodes, loaded.barcodes)
        assertEquals(1_000L, loaded.cachedAtMillis)
        assertEquals(CountingMode.ASSIGNED, loaded.session.mode)
        assertEquals(CountItemStatus.COUNTED, loaded.items.last().status)
        assertNull(store.readSession("missing"))
    }

    @Test
    fun `pending counts are kept until removed and written atomically`() = runTest {
        val store = store()
        val a = CountSubmission("ref-a", "ST-2026-0001", "r1", "PCT-500", "PCT-250901", "Main - C", 100.0, "2026-09-30 10:00:00", attempts = 2, lastError = "x")
        store.writePending("ST-2026-0001", listOf(a))

        assertEquals(listOf(a), store().readPending("ST-2026-0001"))
        assertTrue(File(folder.root, "stocktaking/ST-2026-0001.pending.json").isFile)
        assertFalse(File(folder.root, "stocktaking/ST-2026-0001.pending.json.tmp").exists())

        store.writePending("ST-2026-0001", emptyList())
        assertTrue(store.readPending("ST-2026-0001").isEmpty())
        assertFalse(File(folder.root, "stocktaking/ST-2026-0001.pending.json").exists())
    }

    @Test
    fun `clear removes both files and odd names are made safe`() = runTest {
        val store = store()
        val cache = StocktakingFixtures.cache(session = StocktakingFixtures.session.copy(name = "ST/2026 0002"))
        store.writeSession(cache)
        store.writePending("ST/2026 0002", listOf(CountSubmission("r", "ST/2026 0002", null, "X", null, null, 1.0, "2026-09-30 10:00:00")))
        assertTrue(File(folder.root, "stocktaking/ST_2026_0002.session.json").isFile)

        store.clear("ST/2026 0002")

        assertNull(store.readSession("ST/2026 0002"))
        assertTrue(store.readPending("ST/2026 0002").isEmpty())
    }

    @Test
    fun `a corrupt file reads as empty instead of crashing`() = runTest {
        File(folder.root, "stocktaking").mkdirs()
        File(folder.root, "stocktaking/ST-2026-0001.pending.json").writeText("{not json")
        File(folder.root, "stocktaking/ST-2026-0001.session.json").writeText("")
        val store = store()
        assertTrue(store.readPending("ST-2026-0001").isEmpty())
        assertNull(store.readSession("ST-2026-0001"))
    }
}
