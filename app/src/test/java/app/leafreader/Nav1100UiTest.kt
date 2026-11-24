package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.*
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
import app.leafreader.offline.*
import app.leafreader.sync.*
import java.io.File
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
class Nav1100UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private val source=object:BookSource {
  override val id="nav-self-authored-fixture"
  override suspend fun books()=(1..30).map{Book("simple:$it","自写书目 $it","自写作者","自写简介","",1,0,sourceId=id)}
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("c","自写章节"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter("c","自写章节",listOf("自写正文用于验证未改变阅读路径。".repeat(60)))
 }
 private fun await(test:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();test()}
 private fun content(dark:Boolean=false,font:Float=1f) {
  compose.activity.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,font)) {
   MaterialTheme(colorScheme=if(dark)darkColorScheme(surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB),primary=Color(0xFFFFB69E))else lightColorScheme(surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A),primary=Color(0xFFA94435))){ReaderApp(vm)}
  }}
 }
 private fun local(dark:Boolean=false,font:Float=1f) {RuntimeEnvironment.setFontScale(font);compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);content(dark,font)};await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==30}}
 private fun capture(name:String,v:View)=compose.runOnIdle {val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/nav1100-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private fun owner(v:View):View? {if(v.javaClass.name.contains("AndroidComposeView"))return v;if(v is ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it};return null}
 private fun texts(n:SemanticsNode):Set<String> = n.config.getOrNull(SemanticsProperties.Text).orEmpty().map{it.text}.toSet()+n.children.flatMap{let->texts(let)}
 @Test fun scrollingReallyContinuesUnderTheBarWhileLastBookAndInsetsStayUsable() {
  local();compose.runOnIdle{androidx.core.view.ViewCompat.dispatchApplyWindowInsets(owner(compose.activity.window.decorView)!!,androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),androidx.core.graphics.Insets.of(0,0,0,48)).setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),true).build())};compose.waitForIdle()
  compose.onNodeWithTag("floating-home-nav").assert(SemanticsMatcher.expectValue(NavRenderBackend,"static"))
  val bar=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot;assertTrue(bar.bottom<=compose.activity.window.decorView.height-48)
  val rows=compose.onAllNodes(SemanticsMatcher("book rows"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("shelf-book-simple:")==true}).fetchSemanticsNodes()
  assertTrue("Use real content under the lens, not only a stationary background",rows.any{it.boundsInRoot.bottom>bar.top && it.boundsInRoot.top<bar.bottom})
  capture("fallback-before-scroll",compose.activity.window.decorView)
  compose.onNodeWithTag("shelf-list").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.5f,height*.65f),androidx.compose.ui.geometry.Offset(width*.5f,height*.25f),300)}
  compose.onNodeWithTag("shelf-book-simple:1").assertDoesNotExist();capture("fallback-after-scroll",compose.activity.window.decorView)
  compose.onNodeWithTag("shelf-list").performScrollToNode(hasTestTag("shelf-book-simple:30"));val last=compose.onNodeWithTag("shelf-book-simple:30").assertIsDisplayed().fetchSemanticsNode().boundsInRoot;assertTrue(last.bottom<=bar.top)
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("tab-find").assertIsSelected();compose.onNodeWithTag("tab-shelf").performClick();assertEquals(30,vm.ui.value.shelfBooks.size)
 }
 @Test fun disabledRefractionPreferenceSurvivesNewCompositionWithoutTouchingProgress() {
  local();compose.runOnIdle{vm.store.save("simple:1","c",19)};compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("nav-refraction-toggle").performScrollTo().assertIsOn().performClick().assertIsOff()
  assertFalse(compose.activity.getSharedPreferences("visual-effects-v1",0).getBoolean("nav-refraction",true))
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);content()};await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("nav-refraction-toggle").performScrollTo().assertIsOff();assertEquals(19,vm.store.position("simple:1")!!.offset)
 }
 @Test fun completeMenuHasOnlyNameStatusAndActionsWithoutExtraDescriptions() {
  compose.runOnUiThread {
   val w=ShelfSyncFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val dl=OfflineDownloads(compose.activity,w.network,vault,false).apply{setAutomaticWifi(false)};val cache=dl.cache(BookHubSource.ownerId(c));val m=w.content.manifest();val epoch=cache.generation("public:1");cache.saveManifest("public:1",m,epoch);w.content.bytes().forEach{(id,b)->cache.put("public:1",w.content.version,id,b,epoch)};cache.complete("public:1",m,epoch)
   vm=ReaderViewModel(compose.activity.application,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=dl,automaticShelfSync=ShelfSyncRuntime(compose.activity.application,vault,w.gate(),dl,scheduleJobs=false));content()
  };await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.isNotEmpty()};compose.onNodeWithTag("shelf-book-public:1").performTouchInput{longClick()};await{compose.onAllNodesWithText("完整缓存").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(setOf("自写书目 1","完整缓存","关闭","清除下载","移出书架"),texts(compose.onNodeWithTag("shelf-book-actions").fetchSemanticsNode()))
  compose.onNodeWithTag("download-copy-public:1").assertDoesNotExist();compose.onNodeWithTag("download-book-public:1").assertDoesNotExist();capture("compact-menu",ShadowDialog.getLatestDialog().window!!.decorView)
  compose.onNodeWithTag("remove-book-public:1").performClick();assertEquals(setOf("移出书架？","取消","移除"),texts(compose.onNodeWithTag("remove-shelf-confirm").fetchSemanticsNode()));compose.onNodeWithText("取消").performClick();assertEquals(1,vm.ui.value.shelfBooks.size)
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun darkLargeTextFallbackKeepsNavigationAndBookActionsReachable() {
  local(true,1.6f);compose.onNodeWithTag("floating-home-nav").assert(SemanticsMatcher.expectValue(NavRenderBackend,"static"));for(t in listOf("shelf","find","settings")){val b=compose.onNodeWithTag("tab-$t").assertIsDisplayed().fetchSemanticsNode().boundsInRoot;assertTrue(b.height>=48*compose.activity.resources.displayMetrics.density)}
  capture("fallback-dark-large",compose.activity.window.decorView);compose.onNodeWithTag("shelf-book-simple:1").performTouchInput{longClick()};compose.onNodeWithTag("remove-book-simple:1").performScrollTo().assertIsDisplayed();compose.onNodeWithText("关闭").performClick();assertEquals(30,vm.ui.value.shelfBooks.size)
 }
}
