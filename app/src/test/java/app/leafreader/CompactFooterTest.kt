package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CompactFooterTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private val chapter=Chapter("one","自写测试",List(40){"春风吹过窗前，纸页缓缓翻动。中文标点与分页都沿用原文。".repeat(4)})
    private fun draw(v:NativeReaderView){val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));b.recycle()}
    @Test fun actualPageTotalsReflowResumeLastPageAndUnknownStateDoNotInventDenominators() {
        val v=NativeReaderView(app);v.bind(chapter.text,"测试",0,1,ReaderSettings())
        assertEquals("—/— · —",v.compactProgressLabel)
        v.layout(0,0,540,1140);v.updateProgressContext(0,2,true,null);repeat(2){v.turn(1)};draw(v)
        assertTrue(v.pageCount>3);assertEquals("3/${v.pageCount} · ${v.displayedProgress.bookPercent}%",v.footerPlacement!!.leftText)
        val anchor=v.currentOffset;v.bind(chapter.text,"测试",anchor,1,ReaderSettings(fontSp=30f,lineSpacing=1.9f,horizontalMarginDp=45f));draw(v)
        assertEquals(anchor,v.currentOffset);assertEquals("${v.currentPage+1}/${v.pageCount} · ${v.displayedProgress.bookPercent}%",v.footerPlacement!!.leftText)
        v.layout(0,0,1140,540);draw(v);assertEquals(anchor,v.currentOffset)
        val cold=NativeReaderView(app);cold.layout(0,0,1140,540);cold.bind(chapter.text,"测试",anchor,2,ReaderSettings(fontSp=30f,lineSpacing=1.9f,horizontalMarginDp=45f));cold.updateProgressContext(1,2,true,null)
        assertEquals(v.currentPage,cold.currentPage);while(cold.currentPage<cold.pageCount-1)cold.turn(1);draw(cold)
        assertEquals("${cold.pageCount}/${cold.pageCount} · 100%",cold.footerPlacement!!.leftText)
        val info=AccessibilityNodeInfo.obtain();cold.onInitializeAccessibilityNodeInfo(info);assertTrue(info.contentDescription.contains("全书约 100%"));assertTrue(info.contentDescription.contains("共 ${cold.pageCount} 页"))
        cold.bind("","空",0,3,ReaderSettings());assertEquals("—/— · —",cold.compactProgressLabel);assertEquals(0,cold.pageCount)
    }
    @Test fun imageOnlyAndMixedPagesCountImagesAndKeepTextAnchorsIndependent() {
        val pic=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888);val v=NativeReaderView(app);v.layout(0,0,540,1140)
        v.bind("\uFFFC\uFFFC","图片",0,1,ReaderSettings(),images=listOf(ChapterGraphic("a",0,pic),ChapterGraphic("b",1,pic)),imageId="a",onlyImages=true)
        v.updateProgressContext(0,1,true,null);draw(v);assertEquals("1/2 · 50%",v.footerPlacement!!.leftText);v.turn(1);draw(v);assertEquals("2/2 · 100%",v.footerPlacement!!.leftText)
        v.bind("正文。\n\n   ","混排",0,2,ReaderSettings(),images=listOf(ChapterGraphic("tail",3,pic)));while(v.currentPage<v.pageCount-1)v.turn(1);draw(v)
        assertTrue(v.pageCount>=2);assertTrue(v.footerPlacement!!.leftText.endsWith(" · 100%"));assertEquals("tail",v.currentGraphicId)
        v.updateProgressContext(0,0,false,null);draw(v);assertTrue(v.footerPlacement!!.leftText.endsWith(" · —"))
    }
}
