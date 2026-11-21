package app.leafreader

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.text.TextPaint
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
class TypographyTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private fun chapter() = runBlocking { TestReadingSource().chapter("fixture-layout-1", "layout-01") }

    @Test fun paragraphGapChangesOnlyParagraphBoundaryAndLineSpacingOnlyWrappedLines() {
        val text = "标题\n\n　　" + "甲乙丙丁戊己庚辛壬癸".repeat(6) + "\n\n　　另一个短段。\n\n　　最后短段。"
        fun layout(line: Float, gap: Float) = NativePaginator.layout(text, TextPaint().apply { textSize = 20f }, 200, 1000, line, gap)
        val a = layout(1.2f, 0f); val b = layout(1.2f, 30f); val c = layout(2f, 30f)
        val body = a.layout.getLineForOffset(text.indexOf('甲'))
        val next = a.layout.getLineForOffset(text.indexOf('另'))
        val last = a.layout.getLineForOffset(text.indexOf('最'))
        assertEquals(a.lineBaseline(body + 1) - a.lineBaseline(body), b.lineBaseline(body + 1) - b.lineBaseline(body))
        assertTrue(c.lineBaseline(body + 1) - c.lineBaseline(body) > b.lineBaseline(body + 1) - b.lineBaseline(body))
        assertEquals(30, (b.lineBaseline(last) - b.lineBaseline(next)) - (a.lineBaseline(last) - a.lineBaseline(next)))
        assertEquals(b.lineBaseline(last) - b.lineBaseline(next), c.lineBaseline(last) - c.lineBaseline(next))
    }

    @Test fun largestSettingsAndTinyViewportsKeepEveryCharacterAndNeverCreateBlankPages() {
        val text = chapter().text + "\n\n\n\n"
        for ((w, h) in listOf(90 to 120, 160 to 100, 320 to 240, 1080 to 1920, 1920 to 1080)) {
            val view = NativeReaderView(app); view.layout(0, 0, w, h)
            view.bind(text, "测试", 0, 1, ReaderSettings(32f, 2f, 0, 48f, 48f, 64f))
            val data = view.currentLayout!!; val area = view.currentGeometry!!
            assertTrue(area.textWidth > 0 && area.textHeight > 0)
            assertTrue(area.bottom <= area.footerTop)
            assertEquals(area.textWidth, data.layout.width)
            assertEquals(text, data.map.pages.joinToString("") { text.substring(it.start, it.end) })
            data.map.pages.zipWithNext().forEach { (a, b) -> assertEquals(a.end, b.start) }
            data.map.pages.forEach { p ->
                assertTrue(text.substring(p.start, p.end).any { !it.isWhitespace() })
                assertTrue("$w x $h line clipping", data.lineBottom(p.lastContentLine) - data.lineTop(p.firstContentLine) <= area.textHeight)
                assertFalse(Character.isLowSurrogate(text[p.start]))
            }
            view.draw(Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)))
        }
    }

    @Test fun consecutiveAdjustmentsRotationChapterSwitchAndColdReopenKeepCharacterAnchor() {
        val text = chapter().text
        val view = NativeReaderView(app); view.layout(0, 0, 1080, 1920)
        view.bind(text, "测试", 0, 1, ReaderSettings()); view.turn(1)
        val anchor = view.currentOffset; assertTrue(anchor > 0)
        var settings = ReaderSettings()
        repeat(12) { n ->
            settings = settings.copy(lineSpacing = 1.2f + n * .05f, paragraphSpacingDp = n * 3f, horizontalMarginDp = 8f + n * 3f, verticalMarginDp = n * 5f)
            view.bind(text, "测试", 0, 1, settings)
            assertEquals(anchor, view.currentOffset)
            val page = view.currentLayout!!.map.pages[view.currentPage]
            assertTrue(anchor >= page.start && anchor < page.end)
        }
        view.layout(0, 0, 1920, 1080); assertEquals(anchor, view.currentOffset)
        val store = ReadingStore(app, "layout-cold"); store.saveSettings(settings); store.save("book", "chapter", anchor)
        val cold = ReadingStore(app, "layout-cold")
        val reopened = NativeReaderView(app); reopened.layout(0, 0, 1920, 1080)
        reopened.bind(text, "测试", cold.position("book")!!.offset, 3, cold.settings())
        assertEquals(anchor, reopened.currentOffset); assertEquals(view.currentPage, reopened.currentPage)
        reopened.bind("新章\n\n　　下一章。", "下一章", 0, 4, settings); assertEquals(0, reopened.currentOffset)
        reopened.bind(text, "测试", anchor, 5, settings); assertEquals(anchor, reopened.currentOffset)
    }

    @Test fun legacyLinePreferenceAndPaletteMigrateWithoutInventingProgress() {
        val id = "legacy-layout-fixture"
        val prefs = app.getSharedPreferences("reader-$id", Context.MODE_PRIVATE)
        prefs.edit().putFloat("spacing", 1.85f).putInt("palette", 3).putFloat("font", 27f).commit()
        val store = ReadingStore(app, id); val migrated = store.settings()
        assertEquals(1.85f, migrated.lineSpacing); assertEquals(3, migrated.palette)
        assertEquals(18f, migrated.paragraphSpacingDp); assertEquals(26f, migrated.horizontalMarginDp); assertEquals(25f, migrated.verticalMarginDp)
        assertNull(store.lastBook())
        val kraft = migrated.copy(palette = 4, paragraphSpacingDp = 37f, horizontalMarginDp = 42f, verticalMarginDp = 57f)
        store.saveSettings(kraft); assertEquals(kraft, ReadingStore(app, id).settings())
    }

    @Test fun kraftAndExistingPapersDoNotReflowOrMoveAnchorAndTextureIsCached() {
        assertEquals(5, papers.size); assertEquals(0xFFF3EBDD.toInt(), papers[0].background)
        val view = NativeReaderView(app); view.layout(0, 0, 1080, 1920)
        view.bind(chapter().text, "测试", 0, 1, ReaderSettings()); view.turn(1)
        val anchor = view.currentOffset; val passes = view.layoutPasses; val pageCount = view.pageCount
        for (palette in listOf(4, 1, 3, 4, 0, 4)) {
            view.bind(chapter().text, "测试", 0, 1, ReaderSettings(palette = palette))
            view.draw(Canvas(Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888)))
            assertEquals(passes, view.layoutPasses); assertEquals(anchor, view.currentOffset); assertEquals(pageCount, view.pageCount)
        }
        assertEquals(1, KraftPaperTexture.generationCount)
    }

    @Test fun statusUsesPageEndWithoutMovingSavedAnchorAndRefreshDoesNotReflow() {
        val text = chapter().text
        val view = NativeReaderView(app); view.layout(0, 0, 1080, 1920)
        view.bind(text, "测试", 0, 1, ReaderSettings())
        val before = view.currentOffset
        val passes = view.layoutPasses
        view.updateProgressContext(1, 3, true, listOf(100, text.length, 200))
        assertTrue(view.displayedProgress.chapterPercent!! > 0)
        assertEquals(before, view.currentOffset)
        repeat(4) { n -> view.updateSystemStatus(ReadingStatusSnapshot("10:0$n", "${80 - n}%")) }
        assertEquals(passes, view.layoutPasses); assertEquals(before, view.currentOffset)
        view.bind(text, "测试", text.lastIndex, 2, ReaderSettings())
        assertEquals(100, view.displayedProgress.chapterPercent)
        view.updateProgressContext(0, 1, true, listOf(text.length))
        assertEquals(100, view.displayedProgress.bookPercent)
        view.updateProgressContext(0, 1, false, null)
        assertNull(view.displayedProgress.bookPercent)
    }
}
