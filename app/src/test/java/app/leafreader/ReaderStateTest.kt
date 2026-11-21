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
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReaderStateTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val book = Book("state-book", "测试书", "自写", "测试", "测试", 2, 0)
    private class Source : BookSource {
        override val id = "state-test"
        var failChapter = false
        override suspend fun books() = listOf(Book("state-book", "测试书", "自写", "测试", "测试", 2, 0))
        override suspend fun chapters(bookId: String) = listOf(ChapterRef("a", "甲"), ChapterRef("b", "乙"))
        override suspend fun chapter(bookId: String, chapterId: String): Chapter {
            if (failChapter) throw IOException("offline")
            return Chapter(chapterId, chapterId, listOf("原创测试正文。".repeat(50)))
        }
    }

    @Test fun crossChapterDirectoryAndColdViewModelResume() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = ReaderViewModel(app, source)
            advanceUntilIdle(); vm.open(book, "a"); advanceUntilIdle()
            vm.boundary(1); advanceUntilIdle(); assertEquals("b", vm.ui.value.chapter!!.id)
            assertEquals(0, vm.ui.value.offset)
            vm.boundary(-1); advanceUntilIdle(); assertEquals("a", vm.ui.value.chapter!!.id)
            assertEquals(vm.ui.value.chapter!!.text.lastIndex, vm.ui.value.offset)
            vm.jump("b"); advanceUntilIdle(); assertEquals(0, vm.ui.value.offset)
            vm.position(123); vm.backToShelf()
            val cold = ReaderViewModel(app, source); advanceUntilIdle()
            cold.open(book); advanceUntilIdle()
            assertEquals("b", cold.ui.value.chapter!!.id); assertEquals(123, cold.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun failedChapterDoesNotOverwriteProgressAndRetryReturnsToRequestedChapter() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val source = Source(); val vm = ReaderViewModel(app, source)
            advanceUntilIdle(); vm.open(book, "a"); advanceUntilIdle(); vm.position(80)
            source.failChapter = true; vm.jump("b"); advanceUntilIdle()
            assertNotNull(vm.ui.value.error)
            assertEquals("a", vm.store.position(book.id)!!.chapterId)
            assertEquals(80, vm.store.position(book.id)!!.offset)
            source.failChapter = false; vm.retry(); advanceUntilIdle()
            assertNull(vm.ui.value.error); assertEquals("b", vm.ui.value.chapter!!.id)
            assertEquals(0, vm.store.position(book.id)!!.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun cursorPaginationRetainsLoadedBooksOnFailureAndRejectsLoops() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            var failing = false
            val paged = object : BookSource {
                override val id = "paged-state-fixture"
                override suspend fun books() = emptyList<Book>()
                override suspend fun browse(query: String, cursor: String?): BookPage {
                    if(query.isBlank())return BookPage(emptyList())
                    if (failing) throw SourceProblem(FailureKind.NETWORK, "fixture offline")
                    return when (cursor) {
                        null -> BookPage(listOf(book), "a", true)
                        "a" -> BookPage(listOf(book.copy(id = "second")), "b", true)
                        else -> BookPage(listOf(book.copy(id = "third")), "a", true)
                    }
                }
                override suspend fun chapters(bookId: String) = emptyList<ChapterRef>()
                override suspend fun chapter(bookId: String, chapterId: String) = error("unused")
            }
            val vm = ReaderViewModel(app, paged); advanceUntilIdle();vm.search("灯");advanceUntilIdle()
            vm.loadMore(); advanceUntilIdle(); assertEquals(2, vm.ui.value.books.size)
            failing = true; vm.loadMore(); advanceUntilIdle()
            assertEquals(2, vm.ui.value.books.size); assertNotNull(vm.ui.value.error)
            failing = false; vm.retrySearch(); advanceUntilIdle()
            assertEquals(2, vm.ui.value.books.size); assertTrue(vm.ui.value.error!!.contains("循环"))
            vm.loadShelf("新查询"); advanceUntilIdle()
            assertEquals("新查询", vm.ui.value.query); assertEquals(1, vm.ui.value.books.size)
        } finally { Dispatchers.resetMain() }
    }
}
