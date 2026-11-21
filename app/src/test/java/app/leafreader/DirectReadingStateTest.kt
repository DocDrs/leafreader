package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectReadingStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val a = Book("a", "甲书", "自写", "", "测试", 2, 0xFF516D66)
    private val b = a.copy(id = "b", title = "乙书")
    private class Source : BookSource {
        override val id = "direct-reading-local"
        var fail = false; var empty = false
        override suspend fun books() = listOf(Book("a", "甲书", "自写", "", "测试", 2, 0), Book("b", "乙书", "自写", "", "测试", 2, 0))
        override suspend fun chapters(bookId: String) = if (empty) emptyList() else listOf(ChapterRef("one", "一"), ChapterRef("two", "二"))
        override suspend fun chapter(bookId: String, chapterId: String): Chapter {
            if (fail) throw SourceProblem(FailureKind.NETWORK, "fixture offline")
            return Chapter(chapterId, chapterId, listOf("中文正文。".repeat(120)))
        }
    }
    @Test fun firstReadResumeExplicitDirectoryAndOrderingCommitOnlyAfterBodySuccess() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var now = 100L; val source = Source(); val vm = ReaderViewModel(app, source) { now }
            advanceUntilIdle()
            assertEquals("shelf", vm.ui.value.topTab)
            assertEquals(listOf("a", "b"), vm.ui.value.shelfBooks.map { it.id })
            vm.chapterList(b.id)
            assertNull(vm.store.position(b.id)); assertEquals(0L, vm.store.lastEnteredAt(b.id))
            vm.open(b); advanceUntilIdle()
            assertEquals("one", vm.ui.value.chapter!!.id); assertEquals(100L, vm.store.lastEnteredAt(b.id))
            vm.position(80); vm.backToShelf()
            assertEquals(listOf("b", "a"), vm.ui.value.shelfBooks.map { it.id })
            now = 200; vm.open(b); advanceUntilIdle(); assertEquals(80, vm.ui.value.offset)
            vm.open(a, "two"); advanceUntilIdle(); assertEquals("two", vm.ui.value.chapter!!.id); assertEquals(0, vm.ui.value.offset)
            vm.backToShelf()
            assertEquals(listOf("a", "b"), vm.ui.value.shelfBooks.map { it.id })
            val cold = ReaderViewModel(app, source) { 300 }; advanceUntilIdle()
            assertEquals(listOf("a", "b"), cold.ui.value.shelfBooks.map { it.id })
            cold.open(b); advanceUntilIdle(); assertEquals("one", cold.ui.value.chapter!!.id); assertEquals(80, cold.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun invalidHistoryEmptyDirectoryAndBodyFailureNeverResetOrCountAsRead() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = ReaderViewModel(app, source) { 100 }; advanceUntilIdle()
            vm.store.save(a.id, "lost", 17)
            val old = vm.store.position(a.id)!!
            vm.open(a); advanceUntilIdle()
            assertNotNull(vm.ui.value.error); assertNull(vm.ui.value.chapter)
            assertEquals(old, vm.store.position(a.id))
            source.empty = true; vm.open(b); advanceUntilIdle()
            assertNull(vm.store.position(b.id)); assertEquals(0L, vm.store.lastEnteredAt(b.id))
            source.empty = false; source.fail = true; vm.open(b); advanceUntilIdle()
            assertNull(vm.store.position(b.id)); assertEquals(0L, vm.store.lastEnteredAt(b.id))
            vm.backToShelf(); assertEquals("shelf", vm.ui.value.topTab)
            source.fail = false; vm.open(a, "one"); advanceUntilIdle()
            assertEquals("one", vm.store.position(a.id)!!.chapterId)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun bookHubFirstReadResumeFailureAndExplicitSelectionUseTheSameDirectRoute() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var denyBody = false
            val transport = WireTransport { uri: URI, _, _, _, _ ->
                when {
                    uri.path.endsWith("/auth/login") -> WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
                    uri.path.endsWith("/bookshelf") -> WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
                    uri.path.endsWith("/access") -> WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.txtManifest()).toByteArray())
                    denyBody -> WireResponse(403, ByteArray(0))
                    uri.path.endsWith("/a") -> WireResponse(200, BookHubFixtures.firstText.toByteArray())
                    else -> WireResponse(200, BookHubFixtures.secondText.toByteArray())
                }
            }
            suspend fun source(): BookHubSource {
                val client = BookHubClient(ServiceOrigin("https://direct.fixture.invalid"), transport); client.login("fixture", "fixture-password")
                return BookHubSource(client)
            }
            val vm = ReaderViewModel(app, source()) { 100 }; advanceUntilIdle(); val book = vm.ui.value.shelfBooks.single()
            vm.chapterList(book.id); assertNull(vm.store.position(book.id))
            val refs = vm.chapterList(book.id)
            denyBody = true; vm.open(book); advanceUntilIdle()
            assertNull(vm.store.position(book.id)); assertEquals(0L, vm.store.lastEnteredAt(book.id))
            denyBody = false; vm.retry(); advanceUntilIdle()
            assertEquals(refs[0].id, vm.ui.value.chapter!!.id)
            vm.position(50); vm.backToShelf()
            val cold = ReaderViewModel(app, source()) { 200 }; advanceUntilIdle(); cold.open(book); advanceUntilIdle()
            assertEquals(50, cold.ui.value.offset)
            cold.open(book, refs[1].id); advanceUntilIdle(); assertEquals(refs[1].id, cold.ui.value.chapter!!.id); assertEquals(0, cold.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun coldPaginatedShelfNeverTreatsServerAbsenceAsALocalDelete() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val removed = a.copy(id = "removed")
            var secondPage = b
            var calls = 0
            val source = object : BookSource {
                override val id = "cold-paginated-shelf"
                override suspend fun books() = emptyList<Book>()
                override suspend fun browse(query: String, cursor: String?): BookPage {
                    calls++
                    return if (cursor == null) BookPage(listOf(a), "next", true) else BookPage(listOf(secondPage))
                }
                override suspend fun chapters(bookId: String) = listOf(ChapterRef("one", "正文"))
                override suspend fun chapter(bookId: String, chapterId: String) = Chapter("one", "正文", listOf("自写中文。".repeat(40)))
            }
            val first = ReaderViewModel(app, source) { 100 }; advanceUntilIdle()
            first.loadMoreShelf(); advanceUntilIdle(); first.open(b); advanceUntilIdle(); first.backToShelf()
            first.store.addToShelf(removed)
            assertEquals(listOf("b", "a", "removed"), first.store.orderedShelf().map { it.id })
            val cold = ReaderViewModel(app, source) { 200 }; advanceUntilIdle()
            assertEquals(listOf("b", "a", "removed"), cold.ui.value.shelfBooks.map { it.id })
            val beforeMore = calls
            advanceUntilIdle(); assertEquals(beforeMore, calls) // no automatic pagination/prefetch
            secondPage = a.copy(id = "new-server-book")
            cold.loadMoreShelf(); advanceUntilIdle()
            assertEquals(listOf("b", "a", "removed", "new-server-book"), cold.ui.value.shelfBooks.map { it.id })
            assertEquals(1, cold.ui.value.shelfBooks.count { it.id == "removed" })
            assertNotNull(cold.store.position("b")) // removal never erases saved progress
        } finally { Dispatchers.resetMain() }
    }
    @Test fun previousChapterEndingInEmojiSavesAValidUtf16AnchorForResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = object : BookSource {
                override val id = "emoji-tail-fixture"
                override suspend fun books() = listOf(a)
                override suspend fun chapters(bookId: String) = listOf(ChapterRef("one", "一"), ChapterRef("two", "二"))
                override suspend fun chapter(bookId: String, chapterId: String) = Chapter(chapterId, chapterId, listOf("中文正文。".repeat(50) + "🌙"))
            }
            val vm = ReaderViewModel(app, source) { 100 }; advanceUntilIdle()
            vm.open(a, "two"); advanceUntilIdle(); vm.boundary(-1); advanceUntilIdle()
            val saved = vm.store.position(a.id)!!
            assertEquals("one", saved.chapterId)
            assertFalse(Character.isLowSurrogate(vm.ui.value.chapter!!.text[saved.offset]))
            vm.backToShelf(); vm.open(a); advanceUntilIdle()
            assertNull(vm.ui.value.error); assertEquals(saved.offset, vm.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }
}
