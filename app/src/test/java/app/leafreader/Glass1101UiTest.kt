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
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.leafreader.data.*
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Glass1101UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private val source=object:BookSource {
  override val id="self-authored-glass-1101"
  override suspend fun books()=(1..30).map{Book("local:$it","自写书目 $it","自写作者","仅供界面验证","",1,0,sourceId=id)}
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("c","自写章节"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter("c","自写章节",listOf("这一段是测试自写正文，用于原位续读。".repeat(100)))
 }
 private fun await(test:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();test()}
 private fun setup(dark:Boolean=false,font:Float=1f) {
  compose.runOnUiThread {
   vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false)
   compose.activity.setContent{val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,font)){
    MaterialTheme(colorScheme=if(dark)darkColorScheme(surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB),primary=Color(0xFFFFB69E))else lightColorScheme(surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A),primary=Color(0xFFA94435))){ReaderApp(vm)}
   }}
  };await{!vm.ui.value.shelfLoading&&vm.ui.value.shelfBooks.size==30}
 }
 private fun owner(v:View):View? {if(v.javaClass.name.contains("AndroidComposeView"))return v;if(v is ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it};return null}
 private fun insets(left:Int=0,right:Int=0,bottom:Int) {
  compose.runOnIdle{ViewCompat.dispatchApplyWindowInsets(owner(compose.activity.window.decorView)!!,WindowInsetsCompat.Builder().setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(left,0,right,bottom)).setVisible(WindowInsetsCompat.Type.navigationBars(),true).build())};compose.waitForIdle()
 }
 private fun capture(v:View):Bitmap {lateinit var b:Bitmap;compose.runOnIdle{b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))};return b}
 private fun save(name:String,b:Bitmap){File("build/glass1101-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}
 private fun geometry(left:Int=0,right:Int=0,bottom:Int) {
  val bg=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot
  assertEquals(0f,bg.left,1f);assertEquals(compose.activity.window.decorView.width.toFloat(),bg.right,1f);assertEquals(compose.activity.window.decorView.height.toFloat(),bg.bottom,1f)
  val tabs=listOf("shelf","find","settings").map{compose.onNodeWithTag("tab-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot}
  for(t in tabs){assertTrue(t.left>=left);assertTrue(t.right<=bg.right-right);assertTrue(t.bottom<=bg.bottom-bottom);assertTrue(t.height>=48*compose.activity.resources.displayMetrics.density)}
  assertEquals(tabs[0].width,tabs[1].width,1.1f);assertEquals(tabs[1].width,tabs[2].width,1.1f)
  compose.onNodeWithTag("floating-home-nav").assert(SemanticsMatcher.expectValue(NavRenderBackend,"static"))
 }
 @Test fun gestureInsetBackgroundIsEdgeToEdgeAndLastRowIsNotObscured() {
  setup();insets(bottom=48);geometry(bottom=48);val b=capture(compose.activity.window.decorView);save("full-width-light",b)
  for(x in listOf(1,b.width-2))assertEquals(255,android.graphics.Color.alpha(b.getPixel(x,b.height-2)));b.recycle()
  compose.onNodeWithTag("shelf-list").performTouchInput{swipeUp()};val after=capture(compose.activity.window.decorView);save("full-width-scrolled",after);after.recycle()
  compose.onNodeWithTag("shelf-list").performScrollToNode(hasTestTag("shelf-book-local:30"))
  assertTrue(compose.onNodeWithTag("shelf-book-local:30").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom<=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot.top)
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("tab-find").assertIsSelected();compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("tab-settings").assertIsSelected();compose.onNodeWithTag("tab-shelf").performClick();assertEquals(30,vm.ui.value.shelfBooks.size)
 }
 @Test @Config(qualifiers="w760dp-h360dp-hdpi") fun landscapeThreeButtonSideInsetsAndLargeTextKeepEqualTargets() {
  setup(true,1.6f);insets(left=36,right=48,bottom=24);geometry(36,48,24)
  val b=capture(compose.activity.window.decorView);save("full-width-landscape-dark-large",b);b.recycle()
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun smallScreenThreeButtonInsetCanDisableEffectsWithoutChangingGeometry() {
  setup(false,1.6f);insets(bottom=72);geometry(bottom=72)
  compose.onNodeWithTag("tab-settings").performClick()
  compose.waitUntil(15000){compose.waitForIdle();compose.onAllNodesWithText("正在统计本机占用…").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("nav-refraction-toggle").performScrollTo();compose.waitForIdle()
  // The new version footer makes this control no longer the last child. Bring it above the
  // transparent navigation overlay with the same physical upward scroll a user performs.
  compose.onNodeWithTag("settings-page").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.55f),androidx.compose.ui.geometry.Offset(width*.4f,height*.15f),300)}
  assertTrue(compose.onNodeWithTag("nav-refraction-toggle").assertIsDisplayed().fetchSemanticsNode().boundsInRoot.bottom<=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot.top)
  compose.onNodeWithTag("nav-refraction-toggle").performClick().assertIsOff();geometry(bottom=72)
  compose.onNodeWithTag("tab-shelf").performClick();val b=capture(compose.activity.window.decorView);save("full-width-small-large",b);b.recycle()
 }
 @Test fun menuAndMinimalConfirmationNeverContaminateTheActivityBackplate() {
  setup();insets(bottom=48);val before=capture(compose.activity.window.decorView)
  compose.onNodeWithTag("shelf-book-local:1").performTouchInput{longClick()};compose.onNodeWithTag("book-actions-glass").assert(SemanticsMatcher.expectValue(NavRenderBackend,"static"))
  val behindMenu=capture(compose.activity.window.decorView);assertTrue("The source stays the activity, not the modal's scrim or own labels",before.sameAs(behindMenu));save("actual-home-backplate",before)
  val menu=ShadowDialog.getLatestDialog().window!!.decorView;val m=capture(menu);save("menu-static-window",m);m.recycle()
  compose.onNodeWithTag("remove-book-local:1").performClick();compose.onNodeWithTag("remove-shelf-confirm").assert(SemanticsMatcher.expectValue(NavRenderBackend,"static"));compose.onNodeWithText("移出书架？").assertIsDisplayed();compose.onNodeWithText("取消").assertIsDisplayed();compose.onNodeWithText("移除",substring=false).assertIsDisplayed()
  val behindConfirm=capture(compose.activity.window.decorView);assertTrue(before.sameAs(behindConfirm));val c=capture(ShadowDialog.getLatestDialog().window!!.decorView);save("confirm-static-window",c)
  compose.onNodeWithText("取消").performClick();assertEquals(30,vm.ui.value.shelfBooks.size)
  listOf(before,behindMenu,behindConfirm,c).forEach{it.recycle()}
 }
}
