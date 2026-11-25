package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.ConnectionState
import app.leafreader.offline.AutomaticSourceDownloads
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceManagementUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel;private lateinit var gutenberg:GutenbergSource
 private val opds=OpdsSubscription("傳統訂閱","https://opds.fixture.invalid/catalog",true)
 private lateinit var legado:LegadoDefinition
 private val offline=object:ConnectionState{override val online=false;override val wifi=false}
 private var calls=0
 private fun start(dark:Boolean=false){compose.runOnUiThread{
  val app=compose.activity.application;assertTrue(OpdsSubscriptions(app).save(opds))
  val rules=JSONObject().put("bookSourceName","傳統規則").put("bookSourceUrl","https://rules.fixture.invalid").put("searchUrl","/search?q={{key}}")
   .put("ruleSearch",JSONObject().put("bookList",".book").put("name","@text").put("bookUrl","a@href"))
   .put("ruleBookInfo",JSONObject().put("tocUrl","a@href")).put("ruleToc",JSONObject().put("chapterList","a").put("chapterName","@text").put("chapterUrl","@href"))
   .put("ruleContent",JSONObject().put("content","article@text"))
  legado=LegadoRules.parse(rules.toString(),true).single();assertTrue(LegadoSources(app).save(listOf(legado)))
  val wire=WireTransport{_,_,_,_,_->calls++;error("Settings must not request content")}
  gutenberg=GutenbergSource(app,wire,offline)
  vm=ReaderViewModel(app,sessionVault=MemorySessionVault(),independentSources=listOf(gutenberg),linkBookHubShelf=false,opdsFactory={OpdsSource(app,it,offline,wire)},legadoFactory={LegadoSource(app,it,offline,wire)})
  compose.activity.setContent{if(dark)MaterialTheme(colorScheme=darkColorScheme()){ReaderApp(vm)}else MaterialTheme{ReaderApp(vm)}}
 };compose.waitUntil(10000){compose.waitForIdle();!vm.ui.value.shelfLoading};compose.onNodeWithTag("tab-settings").performClick()}
 private fun clickSetting(tag:String){compose.onNodeWithTag(tag).performScrollTo();compose.waitForIdle()
  val bottom=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot.top
  if(compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.bottom>bottom){compose.onNodeWithTag("settings-page").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.6f),androidx.compose.ui.geometry.Offset(width*.4f,height*.2f),300)};compose.waitForIdle()}
  compose.onNodeWithTag(tag).assertIsDisplayed().performClick()
 }
 private fun shot(name:String,dialog:Boolean=false)=compose.runOnIdle{
  val v=if(dialog)org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
  val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/simplified118-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }
 @Test fun fourUniformEntriesNavigateToDistinctManagersAndKeepExistingConfigurations() {
  start();try{
   val tags=listOf("settings-source","settings-gutenberg","settings-opds","settings-legado")
   val rights=tags.map{tag->compose.onNodeWithTag(tag).performScrollTo();compose.onNodeWithTag("$tag-arrow",useUnmergedTree=true).fetchSemanticsNode().boundsInRoot.right}
   assertTrue(rights.all{kotlin.math.abs(it-rights.first())<1f})
   compose.onNodeWithTag("settings-source-${opds.id}").assertDoesNotExist();compose.onNodeWithTag("settings-source-${legado.id}").assertDoesNotExist();compose.waitUntil(10000){compose.waitForIdle();compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()};shot("settings-four-sources")
   clickSetting("settings-source");compose.onNodeWithTag("source-address-editor").assertExists();compose.onNodeWithText("关闭").performClick()
   clickSetting("settings-gutenberg");assertEquals(GutenbergSource.SOURCE,vm.ui.value.catalogId);assertEquals("find",vm.ui.value.topTab)
   compose.onNodeWithTag("tab-settings").performClick();clickSetting("settings-opds")
   compose.onNodeWithTag("source-manager-opds").assertIsDisplayed();compose.onNodeWithTag("settings-source-${opds.id}").assertExists();compose.onNodeWithTag("settings-source-${legado.id}").assertDoesNotExist();shot("opds-manager",true)
   compose.onNodeWithTag("settings-source-${opds.id}").performClick();assertEquals(opds.id,vm.ui.value.catalogId);compose.onNodeWithTag("tab-settings").performClick();clickSetting("settings-opds")
   compose.onNodeWithTag("add-opds-subscription").performClick();compose.onNodeWithTag("opds-name").assertExists();compose.onNodeWithText("关闭").performClick()
   clickSetting("settings-legado");compose.onNodeWithTag("source-manager-legado").assertIsDisplayed();compose.onNodeWithTag("settings-source-${legado.id}").assertExists();compose.onNodeWithTag("settings-source-${opds.id}").assertDoesNotExist()
   compose.onNodeWithTag("import-legado").performClick();compose.onNodeWithTag("legado-json").assertExists();compose.onNodeWithText("关闭").performClick()
   assertEquals(listOf(opds),OpdsSubscriptions(compose.activity.application).all());assertEquals(listOf(legado),LegadoSources(compose.activity.application).all());assertEquals(0,calls)
  }finally{gutenberg.close()}
 }
 @Test @Config(qualifiers="w320dp-h480dp-night-hdpi") fun narrowLargeTypeKeepsFourRoutesAndManagerActionsAccessible() {
  RuntimeEnvironment.setFontScale(1.8f);start(true);try{
   for(tag in listOf("settings-source","settings-gutenberg","settings-opds","settings-legado")) {
    compose.onNodeWithTag(tag).performScrollTo();val bounds=compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    assertTrue(bounds.left>=0&&bounds.right<=compose.activity.window.decorView.width)
   }
   clickSetting("settings-opds");compose.onNodeWithTag("settings-source-${opds.id}").assertIsDisplayed();compose.onNodeWithTag("add-opds-subscription").assertIsDisplayed();shot("opds-narrow-large",true)
   compose.onNodeWithText("关闭").performClick();clickSetting("settings-legado");compose.onNodeWithTag("settings-source-${legado.id}").assertIsDisplayed();compose.onNodeWithTag("import-legado").assertIsDisplayed();shot("legado-narrow-large",true)
   assertEquals(0,calls)
  }finally{gutenberg.close()}
 }
 @Test fun guestCanSeeAllSourceBytesAndOptInOnlyQueuesBooksAddedAfterEnabling() {
  val payload=File(compose.activity.application.noBackupFilesDir,"gutenberg-public-v1/fixture-stats.bin").apply{parentFile!!.mkdirs();writeBytes(ByteArray(450))}
  start();try{
   assertFalse(vm.bookHubConnected());assertFalse(vm.ui.value.automaticWifi)
   assertEquals(450L,kotlinx.coroutines.runBlocking{vm.offlineUsage()}.totalBytes)
   compose.waitUntil(10000){compose.waitForIdle();compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()}
   compose.onNodeWithTag("offline-total-bytes").assertTextContains("450",substring=true)
   compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("source-gutenberg").performClick()
   compose.onNodeWithTag("find-search-input").performTextInput("论语");compose.onNodeWithTag("find-search-input").performImeAction()
   compose.waitUntil(10000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.any{it.id=="pg:23839"}}
   compose.onNodeWithTag("add-shelf-pg:23839").performClick()
   val app=compose.activity.application;val queue=AutomaticSourceDownloads.get(app)
   ReadingStore(app,GutenbergSource.SOURCE).save("pg:23839","synthetic-kept",777)
   assertTrue(queue.requests().isEmpty())
   compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("automatic-wifi").performScrollTo().performClick();assertTrue(vm.ui.value.automaticWifi);assertTrue(queue.requests().isEmpty())
   compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("find-search-input").performTextReplacement("红楼梦");compose.onNodeWithTag("find-search-input").performImeAction()
   compose.waitUntil(10000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.any{it.id=="pg:24264"}}
   compose.onNodeWithTag("add-shelf-pg:24264").performClick()
   compose.waitUntil(10000){compose.waitForIdle();queue.requests().size==1}
   assertNull(queue.request(GutenbergSource.SOURCE,"pg:23839"));assertNotNull(queue.request(GutenbergSource.SOURCE,"pg:24264"))
   compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("automatic-wifi").performScrollTo().performClick();assertFalse(vm.ui.value.automaticWifi)
   assertEquals("CANCELLED",queue.request(GutenbergSource.SOURCE,"pg:24264")!!.state)
   assertEquals(777,ReadingStore(app,GutenbergSource.SOURCE).position("pg:23839")!!.offset);assertEquals(450L,payload.length());assertEquals(0,calls)
  }finally{gutenberg.close()}
 }

}
