package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch109UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel;private lateinit var rt:OfflineDownloads;private lateinit var w:OfflineFixtures;private lateinit var b:Book
 private fun await(check:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();check()}
 private fun setup(complete:Boolean) {
  compose.runOnUiThread {
   w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};rt=OfflineDownloads(compose.activity,w.network,vault,false)
   b=Book("public:1","雨后沿河的漫长旅程","自写测试作者","","",4,0,"EPUB",owner);ReadingStore(compose.activity,owner).addToShelf(b)
   val cache=rt.cache(owner);val m=w.manifest().put("toc",JSONArray());val e=cache.generation(b.id);cache.saveManifest(b.id,m,e)
   w.bytes().filterKeys{complete || it=="a"}.forEach{(id,raw)->cache.put(b.id,w.version,id,raw,e)}
   if(complete)cache.complete(b.id,m,e)
   w.network.online=false
   vm=ReaderViewModel(compose.activity.application,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)}),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false)
   compose.activity.setContent { MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))) { ReaderApp(vm) } }
  }
  await{!vm.ui.value.shelfLoading}
 }
 private fun capture(name:String,dialog:Boolean=false)=compose.runOnIdle {
  val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);view.draw(canvas)
  if(dialog){val overlay=ShadowDialog.getLatestDialog().window!!.decorView;val pos=IntArray(2);overlay.getLocationOnScreen(pos);canvas.save();canvas.translate(pos[0].toFloat(),pos[1].toFloat());overlay.draw(canvas);canvas.restore()}
  File("build/patch109-previews").apply{mkdirs()}.resolve(name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
 }
 @Test fun partialMenuIsPlainDirectoryShowsActualLocalChaptersAndSettingsExplainsOneWayUpdate() {
  setup(false)
  compose.onNodeWithTag("shelf-book-${b.id}").performTouchInput{longClick()}
  await{compose.onAllNodesWithText("部分缓存").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("verify-download-${b.id}").assertDoesNotExist();capture("01-partial-menu",true);compose.onNodeWithText("关闭").performClick()
  compose.onNodeWithTag("shelf-book-${b.id}").performClick();await{vm.ui.value.error!=null}
  compose.onNodeWithText("主动打开目录").performClick()
  await{compose.onAllNodesWithText("可离线读").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("未缓存完整").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("directory-cache-state").assertTextContains("各章状态",substring=true);capture("02-directory-partial",true)
  compose.runOnIdle{vm.backToShelf()};compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
  compose.onNodeWithTag("tab-settings").performClick()
  compose.onNodeWithTag("shelf-update-submit").assertDoesNotExist();compose.onNodeWithTag("shelf-update-result").assertDoesNotExist()
  compose.runOnIdle{vm.refreshShelf()};await{!vm.ui.value.syncingShelf};assertEquals("NETWORK",vm.shelfUpdateRecord().failure);capture("03-settings-update")
  assertTrue(w.calls.isEmpty());assertNull(vm.positionOf(b))
 }
 @Test fun completeCacheHasNoVerifyOrRedownloadAndClearConfirmationRemovesProgressButKeepsShelf() {
  setup(true);compose.runOnIdle{vm.store.save(b.id,"saved",21);vm.store.preserveRecovery(b.id)}
  compose.onNodeWithTag("shelf-book-${b.id}").performTouchInput{longClick()}
  await{compose.onAllNodesWithText("完整缓存").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("verify-download-${b.id}").assertDoesNotExist();compose.onNodeWithTag("download-book-${b.id}").assertDoesNotExist();capture("04-complete-menu",true)
  compose.onNodeWithTag("clear-download-${b.id}").performClick();compose.onNodeWithText("取消").performClick();assertNotNull(vm.positionOf(b))
  compose.onNodeWithTag("clear-download-${b.id}").performClick();compose.onNodeWithText("当前阅读进度和历史恢复位置",substring=true).assertExists();compose.onNodeWithText("清除缓存与进度").performClick()
  await{!vm.cleanupPending(b) && vm.ui.value.clearingBooks.isEmpty()}
  assertNull(vm.positionOf(b));assertNull(vm.store.preservedPosition(b.id));assertTrue(vm.isOnShelf(b));assertFalse(rt.cache(b.sourceId).status(b.id,true).complete);assertTrue(w.calls.isEmpty())
 }
}
