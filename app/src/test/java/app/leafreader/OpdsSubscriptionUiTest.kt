package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.reader.NativeReaderView
import app.leafreader.sync.ShelfSyncRuntime
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OpdsSubscriptionUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private lateinit var hub:ShelfSyncFixtures
 private lateinit var vault:MemorySessionVault
 private fun await(test:()->Boolean)=compose.waitUntil(20000){if(!compose.mainClock.autoAdvance)compose.mainClock.advanceTimeByFrame();compose.waitForIdle();test()}
 private fun replace(){compose.activity.viewModelStore.clear();vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory{
  @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(c:Class<T>):T {val app=compose.activity.application;val downloads=OfflineDownloads(app,hub.network,vault,false).apply{setAutomaticWifi(false)};return ReaderViewModel(app,connectionGate=hub.gate(),sessionVault=vault,offlineRuntime=downloads,automaticShelfSync=ShelfSyncRuntime(app,vault,hub.gate(),downloads,scheduleJobs=false),opdsFactory={OpdsSource(app,it,AlwaysOnline)})as T}
 })[ReaderViewModel::class.java];compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
 private fun reader(v:View):NativeReaderView?{if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it};return null}
 private fun shot(n:String)=compose.runOnIdle{val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/opds115-previews").apply{mkdirs()}.resolve("$n.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun addInSettingsBrowseNavigationAcquireAddShelfAndColdResumeThroughRealTouch() {
  val server=MockWebServer();val fixture=PublicSourceFixtures();server.start();val base=server.url("").toString().removeSuffix("/")
  server.dispatcher=object:Dispatcher(){override fun dispatch(r:RecordedRequest):MockResponse {
   assertNull(r.getHeader("Authorization"));val xml=when(r.path?.substringBefore('?')) {
    "/opds"->"""<feed xmlns="http://www.w3.org/2005/Atom"><title>自写订阅</title><id>urn:test</id><entry><id>urn:author</id><title>自写作者目录</title><link rel="subsection" type="application/atom+xml" href="$base/books"/></entry></feed>"""
    "/books"->"""<feed xmlns="http://www.w3.org/2005/Atom"><title>书目</title><id>urn:books</id><entry><id>urn:book:one</id><title>自写 OPDS 中文书</title><author><name>自写作者</name></author><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="$base/book.epub"/></entry></feed>"""
    "/book.epub"->return MockResponse().setBody(Buffer().write(fixture.epub)).setHeader("Content-Type","application/epub+zip")
    else->return MockResponse().setResponseCode(404)
   };return MockResponse().setBody(xml).setHeader("Content-Type","application/atom+xml")
  }}
  try {
   compose.runOnUiThread{hub=ShelfSyncFixtures();vault=MemorySessionVault().apply{value=hub.client().snapshot()};replace()};await{!vm.ui.value.shelfLoading}
   compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-opds").performScrollTo().performClick();compose.onNodeWithTag("source-manager-opds").assertIsDisplayed();compose.onNodeWithTag("add-opds-subscription").performClick()
   compose.mainClock.autoAdvance=false;compose.mainClock.advanceTimeBy(250)
   compose.onNodeWithTag("opds-name").performTextInput("自写订阅");compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("opds-url").performTextInput("$base/opds");compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("opds-rights").performScrollTo().performClick();compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("opds-add-confirm").performClick()
   await{!vm.ui.value.addingCatalog && vm.ui.value.publicCatalog};compose.mainClock.autoAdvance=true;assertTrue(vm.bookHubConnected());assertEquals("自写订阅",vm.ui.value.catalogLabel)
   compose.onNodeWithTag("browse-gutenberg").performClick();await{vm.ui.value.catalogSections.isNotEmpty()};shot("navigation")
   compose.onNodeWithTag("catalog-section-自写作者目录").performClick();await{vm.ui.value.books.isNotEmpty()};shot("books")
   val book=vm.ui.value.books.single();compose.onNodeWithTag("add-shelf-${book.id}").performClick();compose.onNodeWithTag("find-book-${book.id}").assertDoesNotExist()
   compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{vm.ui.value.chapter!=null};assertTrue(vm.ui.value.chapter!!.graphics.isNotEmpty())
   compose.runOnIdle{vm.jump(vm.ui.value.chapters[1].id)};await{vm.ui.value.chapter?.title=="自写第一章"};compose.runOnIdle{reader(compose.activity.window.decorView)!!.turn(1)};await{vm.ui.value.offset>0};val anchor=vm.ui.value.offset
   compose.runOnIdle{vm.backToShelf()};val requests=server.requestCount;server.shutdown()
   compose.runOnUiThread{replace()};await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.any{it.key==book.key}};compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{vm.ui.value.chapter?.title=="自写第一章"};assertEquals(anchor,vm.ui.value.offset);assertEquals(requests,server.requestCount);assertEquals(0,hub.mutationCount)
  }finally{server.shutdown()}
 }
}
