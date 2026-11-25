package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import app.leafreader.offline.*
import app.leafreader.sync.*
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.leafreader.data.*
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch113UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private var observedDarkBars:Boolean?=null
 private val source=object:BookSource {
  override val id="compact-dialog-fixture"
  override suspend fun books()=(1..2).map{Book("compact:$it","自写测试书目 $it","自写作者","","",1,0,sourceId=id)}
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("c","自写章节"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter("c","自写章节",listOf("这段自写文字只用于测试。"))
 }
 private fun await(check:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();check()}
 private fun setup(fontScale:Float=1f) {
  RuntimeEnvironment.setFontScale(fontScale)
  compose.runOnUiThread {
   vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false)
   compose.activity.setContent {val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)) {
    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}
   }}
  }
  await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==2}
  compose.runOnIdle{vm.store.save("compact:1","c",7)}
 }
 private fun open() {compose.onNodeWithTag("shelf-book-compact:1").performTouchInput{longClick()};compose.onNodeWithTag("remove-book-compact:1").performScrollTo().performClick()}
 private fun texts(node:SemanticsNode):Set<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map{it.text}.toSet()+node.children.flatMap{ following->texts(following)}
 private fun assertMinimal(maxHeightDp:Float) {
  val node=compose.onNodeWithTag("remove-shelf-confirm").fetchSemanticsNode()
  assertEquals(setOf("移出书架？","取消","移除"),texts(node))
  compose.onNodeWithText("同时清除本机下载和阅读进度").assertDoesNotExist()
  compose.onNodeWithText("移除并同步").assertDoesNotExist()
  compose.onNodeWithText("取消").assertIsDisplayed();compose.onNodeWithTag("confirm-remove-shelf").assertIsDisplayed().assertIsEnabled()
  compose.runOnIdle {val view=ShadowDialog.getLatestDialog().window!!.decorView
   assertTrue("No reserved body: ${view.height}px",view.height<=maxHeightDp*view.resources.displayMetrics.density)
  }
 }
 private fun capture(name:String)=compose.runOnIdle {
  val v=ShadowDialog.getLatestDialog().window!!.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
  val dir=File("build/patch113-previews").apply{mkdirs()};dir.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
  dir.resolve("$name.json").writeText(JSONObject().put("width",v.width).put("height",v.height).put("density",v.resources.displayMetrics.density).put("fontScale",v.resources.configuration.fontScale).toString())
 }
 @Test fun singleAndBatchContainOnlyTitleAndActionsWithoutBodySpaceAndKeepCleanupBehavior() {
  setup();open();assertMinimal(180f);capture("single");compose.onNodeWithText("取消").performClick()
  assertEquals(2,vm.ui.value.shelfBooks.size);assertEquals(7,vm.store.position("compact:1")!!.offset)
  compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-select-all").performClick();compose.onNodeWithTag("shelf-batch-remove").performClick();assertMinimal(180f);capture("batch")
  compose.onNodeWithTag("confirm-remove-shelf").performClick();await{vm.ui.value.shelfBooks.isEmpty() && vm.ui.value.clearingBooks.isEmpty()};assertNull(vm.store.position("compact:1"))
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun narrowLargeFontDialogKeepsBothActionsAndBackDismissesWithoutRemoving() {
  setup(1.6f);open();assertMinimal(240f);capture("narrow-large-font")
  compose.runOnIdle{(ShadowDialog.getLatestDialog() as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()}
  compose.onNodeWithTag("remove-shelf-confirm").assertDoesNotExist();assertEquals(2,vm.ui.value.shelfBooks.size);assertEquals(7,vm.store.position("compact:1")!!.offset)
 }
 private fun remoteSetup(dark:Boolean=false,fontScale:Float=1f) {
  RuntimeEnvironment.setFontScale(fontScale)
  compose.runOnUiThread {
   val w=ShelfSyncFixtures().apply{members+=(2..6).map{"public:$it"}};val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val dl=OfflineDownloads(compose.activity,w.network,vault,false).apply{setAutomaticWifi(false)}
   val cache=dl.cache(BookHubSource.ownerId(c));val m=w.content.manifest();val epoch=cache.generation("public:1");cache.saveManifest("public:1",m,epoch);w.content.bytes().forEach{(id,bytes)->cache.put("public:1",w.content.version,id,bytes,epoch)};cache.complete("public:1",m,epoch)
   vm=ReaderViewModel(compose.activity.application,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=dl,automaticShelfSync=ShelfSyncRuntime(compose.activity.application,vault,w.gate(),dl,scheduleJobs=false))
   compose.activity.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)) {
    MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFFFFB69E),surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB)) else lightColorScheme(primary=Color(0xFFA94435),surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A))){ReaderApp(vm){_,darkBars->observedDarkBars=darkBars}}
   }}
  }
  await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==6}
 }
 private fun captureView(name:String,v:View)=compose.runOnIdle {
  val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));val d=File("build/patch113-previews").apply{mkdirs()};d.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }
 private fun owner(v:View):View? {if(v.javaClass.name.contains("AndroidComposeView"))return v;if(v is ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it};return null}
 @Test fun floatingNavigationRespectsSyntheticBottomInsetAndCompleteMenuUsesSharedMaterial() {
  remoteSetup();compose.runOnIdle{androidx.core.view.ViewCompat.dispatchApplyWindowInsets(owner(compose.activity.window.decorView)!!,androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),androidx.core.graphics.Insets.of(0,0,0,48)).setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),true).build())}
  compose.waitForIdle();compose.onNodeWithTag("tab-shelf").assertIsSelected()
  val nav=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot
  assertEquals(compose.activity.window.decorView.height.toFloat(),nav.bottom,1f);assertTrue(compose.onNodeWithTag("tab-shelf").fetchSemanticsNode().boundsInRoot.bottom<=nav.bottom-48f)
  for(id in listOf("shelf","find","settings"))assertTrue(compose.onNodeWithTag("tab-$id").fetchSemanticsNode().boundsInRoot.height>=48*compose.activity.resources.displayMetrics.density)
  captureView("shelf-light",compose.activity.window.decorView)
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("tab-find").assertIsSelected();compose.onNodeWithTag("find-search-input").assertExists();compose.onNodeWithTag("tab-shelf").performClick()
  compose.onNodeWithTag("shelf-book-public:1").performTouchInput{longClick()};await{compose.onAllNodesWithText("完整缓存").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("download-book-public:1").assertDoesNotExist();captureView("more-light",ShadowDialog.getLatestDialog().window!!.decorView)
  compose.onNodeWithTag("remove-book-public:1").performScrollTo().performClick();assertMinimal(180f);capture("remove-glass")
  compose.onNodeWithText("取消").performClick();assertEquals(6,vm.ui.value.shelfBooks.size)
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun darkLargeFontSurfacesRemainReadableAndMoreActionsCanScroll() {
  remoteSetup(true,1.6f);assertEquals(true,observedDarkBars);compose.onNodeWithTag("tab-settings").assertIsDisplayed()
  val homeLayouts=mutableListOf<TextLayoutResult>();compose.onNodeWithText("自写书目 1",useUnmergedTree=true).performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(homeLayouts)}
  assertTrue("Dark shelf title must inherit a light foreground",homeLayouts.single().layoutInput.style.color.red>.8f)
  captureView("shelf-dark-large",compose.activity.window.decorView)
  compose.onNodeWithTag("shelf-book-public:1").performTouchInput{longClick()};await{compose.onAllNodesWithText("完整缓存").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("remove-book-public:1").performScrollTo().assertIsDisplayed();captureView("more-dark-large",ShadowDialog.getLatestDialog().window!!.decorView)
  compose.onNodeWithTag("remove-book-public:1").performClick();assertMinimal(240f);capture("remove-dark-large")
  val layouts=mutableListOf<TextLayoutResult>();compose.onNodeWithText("移出书架？").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)}
  assertEquals(1.6f,layouts.single().layoutInput.density.fontScale)
  val ink=layouts.single().layoutInput.style.color
  assertTrue(androidx.core.graphics.ColorUtils.calculateContrast(android.graphics.Color.rgb((ink.red*255).toInt(),(ink.green*255).toInt(),(ink.blue*255).toInt()),android.graphics.Color.rgb(40,55,48))>=7.0)
  compose.onNodeWithText("取消").performClick();assertEquals(6,vm.ui.value.shelfBooks.size)
 }

}
