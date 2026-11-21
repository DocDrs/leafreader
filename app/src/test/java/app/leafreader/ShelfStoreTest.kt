package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ShelfStoreTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun book(id: String) = Book(id, id, "自写", "", "测试", 1, 0xFF516D66)

    @Test fun unreadOrderReadRecencyTiesAndColdReopenAreStable() {
        val store = ReadingStore(app, "order-fixture")
        val a = book("a"); val b = book("b"); val c = book("c")
        store.rememberServiceShelf(listOf(a, b, c, a))
        assertEquals(listOf("a", "b", "c"), store.orderedShelf().map { it.id })
        store.markEntered(b, 0)
        assertEquals(listOf("b", "a", "c"), store.orderedShelf().map { it.id })
        store.markEntered(b, 100)
        assertEquals(listOf("b", "a", "c"), store.orderedShelf().map { it.id })
        store.markEntered(a, 100)
        assertEquals(listOf("a", "b", "c"), store.orderedShelf().map { it.id })
        store.markEntered(c, 101); store.save(a.id, "chapter", 12)
        assertEquals(listOf("c", "a", "b"), store.orderedShelf().map { it.id })
        store.addToShelf(a)
        val cold = ReadingStore(app, "order-fixture")
        assertEquals(listOf("c", "a", "b"), cold.orderedShelf().map { it.id })
        assertEquals(100L, cold.lastEnteredAt("a"))
    }

    @Test fun legacyTimeMigratesOnlyWhenAnExistingReadingPositionProvesHistory() {
        val prefs = app.getSharedPreferences("reader-migration-fixture", Application.MODE_PRIVATE)
        prefs.edit().putString("b.chapter", "chapter-b").putInt("b.offset", 25).putLong("b.time", 43)
            .putLong("a.time", 999).commit()
        val store = ReadingStore(app, "migration-fixture")
        store.rememberServiceShelf(listOf(book("a"), book("b")))
        assertEquals(listOf("b", "a"), store.orderedShelf().map { it.id })
        assertEquals(43L, store.lastEnteredAt("b")); assertEquals(0L, store.lastEnteredAt("a"))
        assertEquals(25, store.position("b")!!.offset)
        store.save("b", "chapter-b", 99)
        assertEquals(43L, ReadingStore(app, "migration-fixture").lastEnteredAt("b"))
    }

    @Test fun readingASearchCandidateDoesNotAddItAndNamespacesDoNotMix() {
        val a = ReadingStore(app, "server-and-account-a")
        val b = ReadingStore(app, "server-and-account-b")
        val candidate = book("public:1")
        a.save(candidate.id, "chapter", 27); a.markEntered(candidate, 100)
        assertTrue(a.orderedShelf().isEmpty())
        a.addToShelf(candidate)
        assertEquals(1, a.orderedShelf().size)
        assertNull(b.position(candidate.id)); assertEquals(0L, b.lastEnteredAt(candidate.id)); assertTrue(b.orderedShelf().isEmpty())
    }
}
