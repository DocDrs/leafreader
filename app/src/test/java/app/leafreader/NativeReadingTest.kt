package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextPaint
import android.view.accessibility.AccessibilityNodeInfo
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.reader.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeReadingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun original() = runBlocking { TestReadingSource().chapter("fixture-layout-1", "layout-01") }

    @Test fun chineseLayoutCoversEveryCharacterWithoutSplittingSurrogatesOrClosingPunctuation() {
        val text = original().text + "\n\n　　“你看，船靠岸了！”她说。雨停了……然后呢？🌙"
        for (width in listOf(290, 500, 820)) for (font in listOf(19f, 27f, 32f)) {
            val paint = TextPaint().apply { textSize = font }
            val result = NativePaginator.layout(text, paint, width, 600, 1.55f)
            assertEquals(text, result.map.pages.joinToString("") { text.substring(it.start, it.end) })
            assertEquals(0, result.map.pages.first().start)
            assertEquals(text.length, result.map.pages.last().end)
            result.map.pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            for (page in result.map.pages) {
                assertTrue(result.lineBottom(page.lastContentLine) - result.lineTop(page.firstContentLine) <= 600)
                if (page.start < text.length) assertFalse(Character.isLowSurrogate(text[page.start]))
            }
            val banned = "，。！？；：、）》】”’"
            for (line in 0 until result.layout.lineCount) {
                val at = result.layout.getLineStart(line)
                if (at < text.length) assertFalse("closing punctuation starts line at $at", text[at] in banned)
            }
        }
    }

    @Test fun reflowAndViewRecreationKeepExactCharacterAnchor() {
        val chapter = original()
        val view = NativeReaderView(app)
        view.layout(0, 0, 1080, 1920)
        view.bind(chapter.text, chapter.title, 0, 1, ReaderSettings())
        assertTrue(view.pageCount > 1)
        var saved = -1
        view.onPosition = { saved = it }
        view.turn(1)
        val anchor = view.currentOffset
        assertTrue(anchor > 0); assertEquals(anchor, saved)
        val large = ReaderSettings(fontSp = 29f, lineSpacing = 1.85f)
        view.bind(chapter.text, chapter.title, 0, 1, large)
        assertEquals(anchor, view.currentOffset)
        view.layout(0, 0, 1920, 1080)
        assertEquals(anchor, view.currentOffset)
        val reopened = NativeReaderView(app)
        reopened.layout(0, 0, 1920, 1080)
        reopened.bind(chapter.text, chapter.title, anchor, 1, large)
        assertEquals(view.currentPage, reopened.currentPage)
        assertEquals(anchor, reopened.currentOffset)
        reopened.draw(Canvas(Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)))
    }

    @Test fun previousAndNextBoundariesFireOnceAndAccessibilityCanTurnPages() {
        val chapter = original(); val view = NativeReaderView(app)
        view.layout(0, 0, 1080, 1920)
        view.bind(chapter.text, chapter.title, 0, 1, ReaderSettings())
        val events = mutableListOf<Int>(); view.onBoundary = { events += it }
        view.turn(-1); assertEquals(listOf(-1), events)
        view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD, null)
        assertEquals(1, view.currentPage)
        view.bind(chapter.text, chapter.title, chapter.text.lastIndex, 2, ReaderSettings())
        assertEquals(view.pageCount - 1, view.currentPage)
        view.turn(1); assertEquals(listOf(-1, 1), events)
    }

    @Test fun slowHorizontalSwipeAndEdgeTapTurnExactlyOnePage() {
        val chapter = original(); val view = NativeReaderView(app)
        view.layout(0, 0, 1080, 1920)
        view.bind(chapter.text, chapter.title, 0, 1, ReaderSettings())
        fun event(action: Int, time: Long, x: Float) {
            val e = MotionEvent.obtain(0, time, action, x, 800f, 0)
            view.onTouchEvent(e); e.recycle()
        }
        event(MotionEvent.ACTION_DOWN, 0, 900f)
        event(MotionEvent.ACTION_MOVE, 600, 600f)
        event(MotionEvent.ACTION_UP, 1200, 300f)
        assertEquals(1, view.currentPage)
        val down = MotionEvent.obtain(2000, 2000, MotionEvent.ACTION_DOWN, 10f, 800f, 0)
        val up = MotionEvent.obtain(2000, 2050, MotionEvent.ACTION_UP, 10f, 800f, 0)
        view.onTouchEvent(down); view.onTouchEvent(up); down.recycle(); up.recycle()
        assertEquals(0, view.currentPage)
    }

    @Test fun coldStoreReopenRetainsBookChapterOffsetAndSettings() {
        val store = ReadingStore(app, "test-cold-start")
        store.save("novel", "chapter-b", 318)
        store.saveSettings(ReaderSettings(27f, 1.85f, 3))
        val cold = ReadingStore(app, "test-cold-start")
        assertEquals("novel", cold.lastBook())
        assertEquals("chapter-b", cold.position("novel")!!.chapterId)
        assertEquals(318, cold.position("novel")!!.offset)
        assertEquals(ReaderSettings(27f, 1.85f, 3), cold.settings())
    }

    @Test fun testOnlyLayoutSourceHasConsistentChaptersAndExplicitlyBlockedSourceFails() = runBlocking {
        val source = TestReadingSource()
        for (book in source.books()) {
            val refs = source.chapters(book.id)
            assertEquals(book.chapterCount, refs.size)
            assertEquals(refs.size, refs.map { it.id }.toSet().size)
            for (ref in refs) assertTrue(source.chapter(book.id, ref.id).text.length > 500)
        }
        try { UnverifiedBookHubSource().books(); fail("Unverified data must not silently become demo data") }
        catch (e: java.io.IOException) { assertTrue(e.message!!.contains("尚未核验")) }
    }
}
