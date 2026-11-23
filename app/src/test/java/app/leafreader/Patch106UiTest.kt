package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentDialog
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

/** Real production Compose layout rendered with native Robolectric graphics and synthetic data, not a device capture. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch106UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @After fun restoreFrameworkFontScale(){org.robolectric.RuntimeEnvironment.setFontScale(1f)}
 private lateinit var vm:ReaderViewModel
 private lateinit var w:OfflineFixtures
 private lateinit var runtime:OfflineDownloads
 private lateinit var book:Book
 private var expectedFontScale=1f
 private fun setup(complete:Boolean=true,failed:Boolean=false,largeFont:Boolean=false) {
  expectedFontScale=if(largeFont)1.6f else 1f
  compose.runOnUiThread{org.robolectric.RuntimeEnvironment.setFontScale(expectedFontScale)}
  if(largeFont)compose.activityRule.scenario.recreate()
  val app=compose.activity.application;w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};runtime=OfflineDownloads(app,w.network,vault,false)
  book=Book("public:1",if(largeFont)"很长的自写书名：沿着雨后的街道慢慢走，记住每一页的光与风" else "雨后的书页","栖页 · 自写资料","仅用于本机界面验证","",0,0xFF516D66,"EPUB",owner)
  val st=ReadingStore(app,owner);st.addToShelf(book);st.save(book.id,"kept",37)
  val cache=runtime.cache(owner);val m=w.manifest();cache.saveManifest(book.id,m,0)
  w.bytes().filterKeys{complete || it=="a"}.forEach{(id,raw)->cache.put(book.id,w.version,id,raw,0)}
  if(complete)assertTrue(cache.complete(book.id,m,0))
  w.network.online=false
  compose.runOnUiThread{
   vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)}),sessionVault=vault,offlineRuntime=runtime)
   if(failed){runtime.enqueue(owner,book);runtime.update(runtime.request(runtime.key(owner,book.id))!!,"FAILED","网络暂不可用，已保存内容保留")}
   compose.activity.setContent {
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}
   }
  }
  compose.waitUntil(10000){!vm.ui.value.shelfLoading}
 }
 private fun open() {
  compose.onNode(SemanticsMatcher.expectValue(SavedBookIdentity,book.key)).performTouchInput{longClick()}
  compose.waitUntil(10000){compose.onAllNodesWithTag("shelf-book-actions").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithTag("download-copy-${book.id}").fetchSemanticsNodes().any{!it.config.toString().contains("正在检查")}}
  compose.mainClock.advanceTimeBy(1000);compose.waitForIdle()
 }
 private fun capture(name:String) {
  compose.mainClock.advanceTimeBy(1500);compose.waitForIdle()
  compose.runOnIdle {
  val activity=compose.activity.window.decorView;val overlay=ShadowDialog.getLatestDialog().window!!.decorView
  assertTrue(overlay.width>0 && overlay.height>0)
  assertEquals(expectedFontScale,overlay.resources.configuration.fontScale,.01f)
  val bitmap=Bitmap.createBitmap(activity.width,activity.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap)
  activity.draw(canvas);val location=IntArray(2);overlay.getLocationOnScreen(location);canvas.save();canvas.translate(location[0].toFloat(),location[1].toFloat());overlay.draw(canvas);canvas.restore()
  File("build/patch106-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
 }}
 @Test fun completeSheetIsCompactAndLocalVerificationKeepsTheCopyAndProgress() {
  setup();open();val position=vm.positionOf(book);val count=w.calls.size
  compose.onNodeWithTag("book-actions-title").assertTextEquals(book.title);compose.onNodeWithTag("download-status-${book.id}").assertTextEquals("已下载")
  compose.onNodeWithTag("download-book-${book.id}").assertDoesNotExist();compose.onNodeWithText("目录").assertDoesNotExist()
  val bounds=compose.onNodeWithTag("shelf-book-actions").fetchSemanticsNode().boundsInRoot
  compose.runOnUiThread {
   val overlay=ShadowDialog.getLatestDialog().window!!.decorView
   fun owner(v:android.view.View):android.view.View? {
    if(v.javaClass.name.endsWith("AndroidComposeView"))return v
    if(v is android.view.ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it}
    return null
   }
   val view=owner(overlay)!!
   androidx.core.view.ViewCompat.dispatchApplyWindowInsets(view,androidx.core.view.WindowInsetsCompat.Builder()
    .setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),androidx.core.graphics.Insets.of(0,0,0,48))
    .setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),true).build())
  }
  compose.waitForIdle()
  val bottom=compose.onNodeWithTag("remove-book-${book.id}").fetchSemanticsNode().boundsInRoot.bottom
  assertTrue("bottom action must clear the simulated 48px navigation inset",bottom<=ShadowDialog.getLatestDialog().window!!.decorView.height-48f)
  compose.mainClock.advanceTimeBy(1000);compose.waitForIdle()
  compose.onNodeWithTag("remove-book-${book.id}").assertIsDisplayed()
  capture("01-downloaded-robolectric")
  val titleBounds=compose.onNodeWithTag("book-actions-title").fetchSemanticsNode().boundsInRoot
  println("SHEET_GEOMETRY root=$bounds title=$titleBounds screen=${compose.activity.window.decorView.width}x${compose.activity.window.decorView.height}")
  assertTrue("content-sized sheet should leave backdrop visible",titleBounds.top>compose.activity.window.decorView.height*.2f)
  compose.onNodeWithTag("verify-download-${book.id}").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("download-check-result-${book.id}").fetchSemanticsNodes().isNotEmpty() && compose.onAllNodesWithText("已下载").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(count,w.calls.size);assertEquals(position,vm.positionOf(book));assertTrue(runtime.cache(book.sourceId).status(book.id,true).complete)
  compose.onNodeWithText("关闭").performClick();compose.waitUntil(10000){compose.onAllNodesWithTag("shelf-book-actions").fetchSemanticsNodes().isEmpty()};assertNull(vm.ui.value.book)
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun smallLargeFontFailureScrollsToSeparateDestructiveRowsAndConfirmationKeepsProgress() {
  setup(complete=false,failed=true,largeFont=true);open();val position=vm.positionOf(book)
  compose.onNodeWithTag("download-status-${book.id}").assertTextEquals("下载失败");capture("02-small-failed-160percent-robolectric")
  val request=runtime.request(runtime.key(book.sourceId,book.id))!!
  compose.onNodeWithTag("download-book-${book.id}").performScrollTo().assertIsDisplayed().assertTextContains("重试下载",substring=true).performClick()
  compose.onNodeWithTag("download-wifi").performScrollTo().assertIsSelected()
  compose.onNodeWithTag("download-metered").performScrollTo().assertIsDisplayed();compose.onNodeWithText("取消").performClick()
  assertEquals(request.nonce,runtime.request(request.key)!!.nonce);open()
  compose.onNodeWithTag("remove-book-${book.id}").performScrollTo().assertIsDisplayed();capture("03-small-actions-scrolled-robolectric")
  compose.onNodeWithTag("clear-download-${book.id}").performScrollTo().performClick();compose.onNodeWithText("取消").performClick();assertTrue(runtime.cache(book.sourceId).status(book.id).saved>0)
  compose.onNodeWithTag("clear-download-${book.id}").performScrollTo().performClick();compose.onNodeWithText("清除副本").performClick()
  compose.waitUntil(10000){runtime.cache(book.sourceId).status(book.id).saved==0};assertEquals(position,vm.positionOf(book));assertTrue(vm.isOnShelf(book));assertEquals(0,w.calls.size)
  compose.onNodeWithTag("remove-book-${book.id}").performScrollTo().performClick();compose.onNodeWithText("取消").performClick();assertTrue(vm.isOnShelf(book));assertEquals(position,vm.positionOf(book))
 }
 @Test fun backOutsideTapAndDownwardGestureDismissWithoutOpeningOrChangingTheBook() {
  setup();val position=vm.positionOf(book);open()
  compose.runOnUiThread{(ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed()}
  compose.waitUntil(10000){compose.onAllNodesWithTag("shelf-book-actions").fetchSemanticsNodes().isEmpty()}
  open();compose.onNode(isDialog()).performTouchInput{click(Offset(width*.5f,5f))}
  compose.waitUntil(10000){compose.onAllNodesWithTag("shelf-book-actions").fetchSemanticsNodes().isEmpty()}
  open()
  val title=compose.onNodeWithTag("book-actions-title")
  val distance=(compose.activity.window.decorView.height-title.fetchSemanticsNode().boundsInRoot.bottom)*.85f
  title.performTouchInput{swipe(start=Offset(width*.5f,height*.5f),end=Offset(width*.5f,height*.5f+distance),durationMillis=220)}
  compose.waitUntil(10000){compose.onAllNodesWithTag("shelf-book-actions").fetchSemanticsNodes().isEmpty()}
  assertNull(vm.ui.value.book);assertEquals(position,vm.positionOf(book));assertTrue(vm.isOnShelf(book));assertEquals(0,w.calls.size)
 }
}
