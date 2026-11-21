package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.reader.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** All words, image pixels and structural identifiers here are invented; no imported books. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubRasterRegressionTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private class Wire {
        val manifest=BookHubFixtures.epubManifest()
        var html=BookHubFixtures.epubText
        var imageStatus=200
        var invalidImage=false
        val requests=mutableListOf<String>()
        val bitmap=Bitmap.createBitmap(60,90,Bitmap.Config.ARGB_8888).apply { eraseColor(0xffa84e2b.toInt()) }
        val png=ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        fun imageOnly(svg:Boolean=false) {
            html=if(svg) "<html><body><svg xmlns='http://www.w3.org/2000/svg'><image id='picture' href='/api/v1/books/public%3A1/resources/pic?ticket=old'/></svg></body></html>"
                else "<html><body><img src='/api/v1/books/public%3A1/resources/pic?ticket=old'/></body></html>"
            manifest.put("toc",JSONArray())
            manifest.getJSONArray("resources").put(BookHubFixtures.resource("pic").put("mediaType","image/png"))
        }
        fun source():BookHubSource {
            val c=BookHubClient(ServiceOrigin("https://raster-regression.fixture.invalid"),WireTransport{uri,_,headers,_,_->
                requests+=uri.path
                when {
                    uri.path.endsWith("/bookshelf")->WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
                    uri.path.endsWith("/access")->WireResponse(200,BookHubFixtures.envelope(manifest).toByteArray(),"application/json")
                    uri.path.endsWith("/pic")->{assertNull(headers["Authorization"]);assertEquals("ticket=fixture-ticket",uri.rawQuery)
                        WireResponse(imageStatus,if(invalidImage) "<html>not image</html>".toByteArray() else png,"image/png")}
                    else->{assertNull(headers["Authorization"]);WireResponse(200,html.toByteArray(),"application/xhtml+xml")}
                }
            })
            c.restore(SavedSession(c.origin.value,"synthetic-account","synthetic-device","synthetic-access","synthetic-refresh"));return BookHubSource(c)
        }
    }
    private suspend fun code(action:suspend()->Unit)=try{action();"SUCCESS"}catch(e:SourceProblem){e.diagnostic}
    @Test fun explicitManifestRasterAndSvgImageBothDisplayInsteadOfBeingSkipped()=runBlocking {
        for(svg in listOf(false,true)) {
            val w=Wire().apply { imageOnly(svg) };val s=w.source();val refs=s.chapters("public:1")
            val initial=s.initialReading("public:1",refs)
            assertEquals(refs.first().id,initial.chapter.id);assertNull(initial.notice)
            assertEquals(1,initial.chapter.graphics.size);assertTrue(initial.chapter.paragraphs.isEmpty())
            assertEquals("\uFFFC",initial.chapter.text);assertEquals(60,initial.chapter.graphics.single().bitmap.width)
            val view=NativeReaderView(app);view.layout(0,0,480,800)
            view.bind(initial.chapter.text,"测试封面",0,1,ReaderSettings(),images=initial.chapter.graphics,imageId=initial.chapter.graphics[0].id,onlyImages=true)
            assertEquals(1,view.pageCount);assertEquals("本章 100%",view.displayedProgress.chapterLabel)
            val rendered=Bitmap.createBitmap(480,800,Bitmap.Config.ARGB_8888);view.draw(Canvas(rendered))
            assertEquals(0xffa84e2b.toInt(),rendered.getPixel(240,400))
        }
    }
    @Test fun singleIndependentResourceWithMissingFragmentUsesWholeResourceButKeepsChapterId()=runBlocking {
        val w=Wire();w.manifest.put("toc",JSONArray().put(JSONObject().put("label","独立一章").put("resourceId","a").put("href","/api/v1/books/public%3A1/resources/a#stale")))
        val s=w.source();val ref=s.chapters("public:1").single();val chapter=s.chapter("public:1",ref.id)
        assertEquals(ref.id,chapter.id);assertTrue(chapter.text.contains("走向江边"));assertTrue(chapter.text.contains("到达对岸"))
        assertNull(chapter.navigationNotice)
    }
    @Test fun multipleTargetsAndDuplicateExistingAnchorsRemainStrict()=runBlocking {
        val w=Wire();w.manifest.getJSONArray("toc").getJSONObject(1).put("href","/api/v1/books/public%3A1/resources/a#missing")
        val s=w.source();val refs=s.chapters("public:1");assertEquals("BH_EPUB_ANCHOR",code{s.chapter("public:1",refs[0].id)})
        assertEquals("BH_EPUB_ANCHOR",code{EpubPlainText.extract("<svg><image id='x'/><image id='x'/></svg>","x")})
    }
    @Test fun svgDescendantAnchorIsPreservedButDescriptionIsNeverNovelText()=runBlocking {
        assertEquals("BH_EPUB_IMAGE_ONLY",code{EpubPlainText.extract("<svg><title>metadata</title><desc>description</desc><image id='cover'/></svg>","cover")})
        assertEquals("BH_EPUB_IMAGE_ONLY",code{EpubPlainText.extract("<svg><text>visible unsupported vector text</text></svg>")})
    }
    @Test fun imageErrorsAreNotSwallowedAsAnEmptyPrefaceAndProgressIsUntouched()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val w=Wire().apply{imageOnly();imageStatus=503};val s=w.source();val vm=ReaderViewModel(app,s,sessionVault=MemorySessionVault())
            val book=vm.ui.first{!it.shelfLoading}.shelfBooks.single();vm.open(book)
            vm.ui.first{!it.loading};assertNotNull(vm.ui.value.error);assertNull(vm.store.position(book.id))
            assertEquals(1,w.requests.count{it.endsWith("/pic")})
            w.imageStatus=200;w.invalidImage=true;vm.retry();vm.ui.first{!it.loading};assertTrue(vm.ui.value.error!!.contains("BH_IMAGE_DECODE"));assertNull(vm.store.position(book.id))
        }finally{Dispatchers.resetMain()}
    }
    @Test fun mixedImagePagesKeepCanonicalTextAndPreciseGraphicBookmarkAcrossReflowAndColdStore()=runBlocking {
        val w=Wire().apply { imageOnly();manifest.put("toc",JSONArray());html="<html><body><img src='/api/v1/books/public%3A1/resources/pic'/><h1>正文</h1><p>甲乙丙丁，文字保持原样。🌙</p><img src='/api/v1/books/public%3A1/resources/pic'/></body></html>" }
        val s=w.source();val chapter=s.chapter("public:1",s.chapters("public:1").single().id)
        val expected=Chapter(chapter.id,chapter.title,chapter.paragraphs,chapter.headingOnly).text
        assertEquals(expected,chapter.text);assertEquals(2,chapter.graphics.size)
        for((width,height) in listOf(320 to 480,480 to 240,1080 to 1920)) {
            val view=NativeReaderView(app);view.layout(0,0,width,height)
            view.bind(chapter.text,"混排",0,1,ReaderSettings(fontSp=32f,horizontalMarginDp=48f,verticalMarginDp=64f),images=chapter.graphics,imageId=chapter.graphics.first().id)
            assertEquals(chapter.graphics.first().id,view.currentGraphicId)
            assertEquals(chapter.text,view.visualTextRanges.joinToString(""){chapter.text.substring(it)})
            while(view.currentPage<view.pageCount-1)view.turn(1)
            assertEquals(chapter.graphics.last().id,view.currentGraphicId)
            assertFalse(Character.isLowSurrogate(chapter.text[view.currentOffset]))
            val anchor=view.currentOffset;view.bind(chapter.text,"混排",0,1,ReaderSettings(),images=chapter.graphics)
            assertEquals(chapter.graphics.last().id,view.currentGraphicId);assertEquals(anchor,view.currentOffset)
            val store=ReadingStore(app,"raster-cold");store.save("book",chapter.id,anchor,view.currentGraphicId)
            val saved=ReadingStore(app,"raster-cold").position("book")!!
            val cold=NativeReaderView(app);cold.layout(0,0,height,width)
            cold.bind(chapter.text,"混排",saved.offset,1,ReaderSettings(),images=chapter.graphics,imageId=saved.graphicId)
            assertEquals(chapter.graphics.last().id,cold.currentGraphicId)
        }
    }
    @Test fun legacyTextBookmarkDoesNotBecomeTheNewCoverAtTheSameOffset()=runBlocking {
        val image=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888)
        val chapter=Chapter("old-id","标题",listOf("旧正文"),graphics=listOf(ChapterGraphic("new-cover",0,image)))
        val store=ReadingStore(app,"legacy-raster");store.save("book",chapter.id,0)
        val old=store.position("book")!!;assertNull(old.graphicId)
        val view=NativeReaderView(app);view.layout(0,0,480,800)
        view.bind(chapter.text,chapter.title,old.offset,1,ReaderSettings(),images=chapter.graphics,imageId=old.graphicId)
        assertEquals(1,view.currentPage);assertNull(view.currentGraphicId)
    }
    @Test fun imageEntryReturnsAndNewViewModelRestoreTheGraphicWithoutChangingSessionOrLayout()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val w=Wire().apply{imageOnly(svg=true)}
            val first=ReaderViewModel(app,w.source(),sessionVault=MemorySessionVault())
            val book=first.ui.first{!it.shelfLoading}.shelfBooks.single();first.open(book);first.ui.first{!it.loading}
            assertNull(first.ui.value.error);val graphic=first.ui.value.graphicId;assertNotNull(graphic)
            first.settings(ReaderSettings(fontSp=29f,palette=4));first.backToShelf()
            val second=ReaderViewModel(app,w.source(),sessionVault=MemorySessionVault());second.ui.first{!it.shelfLoading}
            second.open(book);second.ui.first{!it.loading};assertNull(second.ui.value.error)
            assertEquals(graphic,second.ui.value.graphicId);assertEquals(29f,second.ui.value.settings.fontSp);assertEquals(4,second.ui.value.settings.palette)
            val saved=second.store.position(book.id)
            second.backToShelf();w.imageStatus=503;second.open(book);second.ui.first{!it.loading}
            assertNotNull(second.ui.value.error);assertEquals(saved,second.store.position(book.id))
        }finally{Dispatchers.resetMain()}
    }
    @Test fun externalAndUnlistedImagesAreNeverRequested()=runBlocking {
        val w=Wire();w.imageOnly();w.html="<html><body><img src='https://external.invalid/private'/></body></html>"
        val s=w.source();val refs=s.chapters("public:1");assertEquals("BH_EPUB_IMAGE_ONLY",code{s.chapter("public:1",refs[0].id)})
        assertFalse(w.requests.any{it.endsWith("/pic")})
    }
}
