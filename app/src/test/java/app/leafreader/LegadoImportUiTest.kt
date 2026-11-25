package app.leafreader
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
@RunWith(RobolectricTestRunner::class) @Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
class LegadoImportUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private lateinit var hub:ShelfSyncFixtures
 private lateinit var vault:MemorySessionVault
 private lateinit var fixture:LegadoFixtures
 private val network=PublicSourceFixtures.Network()
 private var offlineHttp=0
 private fun await(test:()->Boolean)=compose.waitUntil(20000){if(!compose.mainClock.autoAdvance)compose.mainClock.advanceTimeByFrame();compose.waitForIdle();test()}
 private fun replace(){compose.activity.viewModelStore.clear();vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory{
  @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(c:Class<T>):T {val app=compose.activity.application;val downloads=OfflineDownloads(app,hub.network,vault,false).apply{setAutomaticWifi(false)}
  return ReaderViewModel(app,connectionGate=hub.gate(),sessionVault=vault,offlineRuntime=downloads,automaticShelfSync=ShelfSyncRuntime(app,vault,hub.gate(),downloads,scheduleJobs=false),independentSources=listOf(PublicSourceFixtures().source(app)),legadoFactory={d->val http=AnonymousHttp(CatalogOrigin(d.origin));LegadoSource(app,d,network,WireTransport{u,m,h,b,cap->if(!network.up){offlineHttp++;error("Offline HTTP forbidden")};http.execute(u,m,h,b,cap)})})as T}
 })[ReaderViewModel::class.java];compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
 private fun start(){fixture=LegadoFixtures();compose.runOnUiThread{hub=ShelfSyncFixtures();vault=MemorySessionVault().apply{value=hub.client().snapshot()};replace()};await{!vm.ui.value.shelfLoading}}
 private fun openImport(){compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-legado").performScrollTo();compose.waitForIdle()
  val nav=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot
  if(compose.onNodeWithTag("settings-legado").fetchSemanticsNode().boundsInRoot.bottom>nav.top){compose.onNodeWithTag("settings-page").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.55f),androidx.compose.ui.geometry.Offset(width*.4f,height*.15f),300)};compose.waitForIdle()}
  compose.onNodeWithTag("settings-legado").performClick();compose.onNodeWithTag("source-manager-legado").assertIsDisplayed();compose.onNodeWithTag("import-legado").performClick();compose.mainClock.autoAdvance=false;compose.mainClock.advanceTimeBy(250)}
 private fun confirm(){compose.onNodeWithTag("legado-rights").performScrollTo().performClick();compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("legado-import-confirm").performClick();await{!vm.ui.value.addingCatalog&&vm.ui.value.publicCatalog};assertNull(vm.ui.value.catalogAddError);compose.mainClock.autoAdvance=true}
 private fun reader(v:View):NativeReaderView?{if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it};return null}
 private fun shot(name:String)=compose.runOnIdle{val v=if(name in setOf("import","mixed")){val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();check(dialog!=null&&dialog.isShowing);dialog.window!!.decorView}else compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/legado116-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun pasteImportFindDetailAddReadPartialDirectoryAndColdOfflineResume() {
  start();fixture.traditional=true;try{val oldKeys=vm.ui.value.shelfBooks.map{it.key};openImport();compose.onNodeWithTag("legado-json").performTextInput(fixture.rules().toString());compose.mainClock.advanceTimeBy(250);shot("import");confirm();assertEquals("自写规则源",vm.ui.value.catalogLabel);assertTrue(fixture.calls.isEmpty())
   compose.onNodeWithTag("browse-gutenberg").assertDoesNotExist();compose.onNodeWithTag("find-search-input").performTextInput("自写");compose.onNodeWithTag("find-search-input").performImeAction();await{!vm.ui.value.findLoading&&vm.ui.value.books.isNotEmpty()};val book=vm.ui.value.books.single();shot("search")
   compose.onNodeWithTag("find-book-${book.id}").performClick();await{compose.onAllNodesWithText("自写详情书").fetchSemanticsNodes().isNotEmpty()};compose.onNodeWithText("详情规则取得的简介").assertIsDisplayed();shot("details");compose.onNodeWithText("←  返回").performClick()
   compose.onNodeWithTag("add-shelf-${book.id}").performClick();compose.onNodeWithTag("find-book-${book.id}").assertDoesNotExist();compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null};assertNull(vm.ui.value.error);assertEquals("第一章",vm.ui.value.chapter!!.title);assertTrue(vm.ui.value.chapter!!.text.contains("這段自寫"));compose.runOnIdle{assertTrue(reader(compose.activity.window.decorView)!!.currentLayout!!.layout.text.toString().contains("这段自写"));assertFalse(reader(compose.activity.window.decorView)!!.currentLayout!!.layout.text.toString().contains("這段自寫"))}
   compose.runOnIdle{reader(compose.activity.window.decorView)!!.turn(1)};await{vm.ui.value.offset>0};val anchor=vm.ui.value.offset;val chapter=vm.ui.value.chapter!!.id;shot("reader")
   val refs=vm.ui.value.chapters;assertEquals("可离线读",kotlinx.coroutines.runBlocking{vm.chapterOfflineStatus(book,refs[0].id)});assertEquals("未缓存",kotlinx.coroutines.runBlocking{vm.chapterOfflineStatus(book,refs[1].id)})
   compose.runOnIdle{vm.backToShelf()};val count=fixture.calls.size;network.up=false;fixture.server.shutdown();compose.runOnUiThread{replace()};await{!vm.ui.value.shelfLoading&&vm.ui.value.shelfBooks.any{it.key==book.key}};assertTrue(vm.ui.value.shelfBooks.map{it.key}.containsAll(oldKeys))
   compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null};assertEquals(chapter,vm.ui.value.chapter!!.id);assertEquals(anchor,vm.ui.value.offset);assertEquals(0,offlineHttp);assertEquals(count,fixture.calls.size);assertTrue(vm.bookHubConnected());assertEquals(0,hub.mutationCount)
   compose.runOnIdle{vm.backToShelf();vm.clearDownload(book)};await{!vm.cleanupPending(book)&&vm.positionOf(book)==null};assertTrue(vm.isOnShelf(book));assertEquals(0,kotlinx.coroutines.runBlocking{vm.inspectDownload(book)}.saved);assertTrue(vm.ui.value.shelfBooks.map{it.key}.containsAll(oldKeys))
  }finally{fixture.server.shutdown()}
 }
 @Test fun localJsonThroughDocumentContractAndMalformedImportAreExplicit() {
  start();try{openImport();compose.onNodeWithTag("legado-pick-file").performClick();val launch=shadowOf(compose.activity).nextStartedActivityForResult;assertEquals(Intent.ACTION_OPEN_DOCUMENT,launch.intent.action)
   val file=File(compose.activity.cacheDir,"own-source.json").apply{writeText(fixture.rules(true).toString())};val result=Intent().setData(Uri.fromFile(file))
   compose.runOnUiThread{compose.activity.activityResultRegistry.dispatchResult(launch.requestCode,Activity.RESULT_OK,result)};await{compose.onNodeWithTag("legado-json").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text.contains("JSON 自写书源")};confirm();assertTrue(fixture.calls.isEmpty())
   compose.onNodeWithTag("find-search-input").performTextInput("JSON");compose.onNodeWithTag("find-search-input").performImeAction();await{!vm.ui.value.findLoading&&vm.ui.value.books.isNotEmpty()};val b=vm.ui.value.books.single();compose.onNodeWithTag("find-read-${b.id}").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null};assertNull(vm.ui.value.error);assertTrue(vm.ui.value.chapter!!.text.contains("第二段自写正文"))
   compose.runOnIdle{vm.backToShelf()};openImport();compose.onNodeWithTag("legado-json").performTextInput("{\"bookSourceName\":\"禁止脚本\",\"loginUrl\":\"https://account.invalid\"}");compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("legado-rights").performScrollTo().performClick();compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("legado-import-confirm").performClick();await{vm.ui.value.catalogImportErrors.isNotEmpty()};compose.mainClock.advanceTimeBy(150);compose.onNodeWithTag("legado-rejection-0").performScrollTo().assertTextContains("不兼容规则",substring=true);assertEquals(1,LegadoSources(compose.activity).all().size);assertTrue(vm.bookHubConnected());assertEquals(0,hub.mutationCount)
  }finally{fixture.server.shutdown()}
 }
 @Test @Config(qualifiers="w320dp-h480dp-night-hdpi") fun importEditorScrollsAtLargeFontAndStaysInsideSafeBounds() {
  org.robolectric.RuntimeEnvironment.setFontScale(1.7f);start();try{openImport();compose.mainClock.autoAdvance=true
   compose.onNodeWithTag("legado-json").assertExists()
   compose.onNodeWithTag("legado-import-scroll").performTouchInput{swipeUp()};compose.waitForIdle();compose.onNodeWithTag("legado-rights").assertIsDisplayed()
   compose.onNodeWithTag("legado-import-confirm").assertIsDisplayed().assertIsNotEnabled();val bounds=compose.onNodeWithTag("legado-import-confirm").fetchSemanticsNode().boundsInRoot;assertTrue(bounds.left>=0&&bounds.right<=compose.activity.window.decorView.width);assertTrue(bounds.height>=48*compose.activity.resources.displayMetrics.density)
   compose.onNodeWithTag("legado-import-scroll").performTouchInput{swipeDown()};compose.waitForIdle();compose.onNodeWithTag("legado-pick-file").assertIsDisplayed()
  }finally{fixture.server.shutdown()}
 }

 @Test fun mixedAndroidExportImportsValidOnlyAndShowsSourceFieldReasonsThenReadsLiteralPlus() {
  start();try{val good=fixture.rules().put("bookSourceName","有效基础源");val js=fixture.rules().put("bookSourceName","需要 JS 的源");js.getJSONObject("ruleContent").put("content","@js:java.ajax(baseUrl)")
   val old=fixture.rules().put("bookSourceName","旧式多级源");old.getJSONObject("ruleSearch").put("bookList","div.book@a@text")
   val batch=org.json.JSONArray().put(good).put(js).put(old);openImport();compose.onNodeWithTag("legado-json").performTextInput(batch.toString());compose.mainClock.advanceTimeBy(250);compose.onNodeWithTag("legado-rights").performScrollTo().performClick();compose.mainClock.advanceTimeByFrame();compose.onNodeWithTag("legado-import-confirm").performClick()
   await{!vm.ui.value.addingCatalog&&vm.ui.value.catalogImportErrors.size==2};compose.mainClock.autoAdvance=true
   assertEquals(1,vm.ui.value.catalogImportedCount);assertEquals(listOf("有效基础源"),LegadoSources(compose.activity).all().map{it.name});assertTrue(fixture.calls.isEmpty())
   compose.onNodeWithTag("legado-import-summary").performScrollTo().assertTextContains("已导入 1",substring=true)
   compose.onNodeWithTag("legado-rejection-0").performScrollTo().assertTextContains("需要 JS 的源",substring=true).assertTextContains("ruleContent.content",substring=true).assertTextContains("JS",substring=true)
   compose.onNodeWithTag("legado-rejection-1").performScrollTo().assertTextContains("旧式多级源",substring=true).assertTextContains("ruleSearch.bookList",substring=true).assertTextContains("多级 @",substring=true)
   shot("mixed");compose.onNodeWithTag("legado-import-confirm").assertTextEquals("完成").performClick();assertEquals("有效基础源",vm.ui.value.catalogLabel)
   val query="中文 + 测试";compose.onNodeWithTag("find-search-input").performTextInput(query);compose.onNodeWithTag("find-search-input").performImeAction();await{!vm.ui.value.findLoading&&vm.ui.value.books.isNotEmpty()}
   val request=fixture.server.takeRequest(2,java.util.concurrent.TimeUnit.SECONDS)!!;assertEquals(query,request.requestUrl!!.queryParameter("q"));assertTrue(request.path!!.contains("%2B"))
   val book=vm.ui.value.books.single();compose.onNodeWithTag("add-shelf-${book.id}").performClick();compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null};assertNull(vm.ui.value.error);assertTrue(fixture.calls.any{it.substringBefore('?')=="/book/one"});assertTrue(fixture.calls.any{it=="/chapter/1"});assertTrue(vm.bookHubConnected());assertEquals(0,hub.mutationCount)
  }finally{fixture.server.shutdown()}
 }

}
