package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.leafreader.data.*
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
import java.io.File

/** Real settings routes, native host touch input; system/IME insets are synthetic, not a phone keyboard. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h640dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SourceDialogSafetyUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private lateinit var gutenberg:GutenbergSource
 private val fixtures=PublicSourceFixtures()
 private val oldOpds=OpdsSubscription("已保存订阅","https://opds.fixture.invalid/old",true)
 private fun rule(name:String="已保存规则")=JSONObject().put("bookSourceName",name).put("bookSourceUrl","https://rules.fixture.invalid")
  .put("searchUrl","/search?q={{key}}")
  .put("ruleSearch",JSONObject().put("bookList",".book").put("name","@text").put("bookUrl","a@href"))
  .put("ruleBookInfo",JSONObject().put("tocUrl","a@href"))
  .put("ruleToc",JSONObject().put("chapterList","a").put("chapterName","@text").put("chapterUrl","@href"))
  .put("ruleContent",JSONObject().put("content","article@text"))
 private val atom="<feed xmlns='http://www.w3.org/2005/Atom'><id>urn:self:test</id><title>自写目录</title></feed>"
 private fun start(font:Float=1f,dark:Boolean=false){RuntimeEnvironment.setFontScale(font);compose.runOnUiThread{
  val app=compose.activity.application
  OpdsSubscriptions(app).save(oldOpds);LegadoSources(app).save(LegadoRules.parse(rule().toString(),true))
  gutenberg=fixtures.source(app)
  val wire=WireTransport{uri,method,headers,body,_->check(uri.host=="opds.fixture.invalid"&&method=="GET"&&body==null&&headers.keys.none{it.equals("Authorization",true)||it.equals("Cookie",true)});WireResponse(200,atom.toByteArray())}
  vm=ReaderViewModel(app,sessionVault=MemorySessionVault(),linkBookHubShelf=false,independentSources=listOf(gutenberg),opdsFactory={OpdsSource(app,it,fixtures.network,wire)})
  compose.activity.setContent{MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFFFFB69E),background=Color(0xFF17221F),surface=Color(0xFF223029),onBackground=Color(0xFFEEF2EB),onSurface=Color(0xFFEEF2EB),surfaceContainer=Color(0xFF2B3B32))else lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
 };await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("tab-settings").performClick()}
 private fun await(test:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();test()}
 private fun owner(v:View):View? {if(v.javaClass.name.contains("AndroidComposeView"))return v;if(v is ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it};return null}
 private fun decor(dialog:Boolean=true)=if(dialog)ShadowDialog.getLatestDialog().window!!.decorView else compose.activity.window.decorView
 private fun insets(bottom:Int=72,ime:Int=0,left:Int=0,right:Int=0,dialog:Boolean=true) {
  compose.runOnIdle{ViewCompat.dispatchApplyWindowInsets(owner(decor(dialog))!!,WindowInsetsCompat.Builder()
   .setInsets(WindowInsetsCompat.Type.statusBars(),Insets.of(0,36,0,0)).setVisible(WindowInsetsCompat.Type.statusBars(),true)
   .setInsets(WindowInsetsCompat.Type.navigationBars(),Insets.of(left,0,right,bottom)).setVisible(WindowInsetsCompat.Type.navigationBars(),true)
   .setInsets(WindowInsetsCompat.Type.ime(),Insets.of(0,0,0,ime)).setVisible(WindowInsetsCompat.Type.ime(),ime>0).build())};compose.waitForIdle()
 }
 private fun action(tag:String,bottom:Int=72,ime:Int=0,left:Int=0,right:Int=0){
  val v=owner(decor())!!;val b=compose.onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  assertTrue("$tag bottom $b exceeds ${v.height-maxOf(bottom,ime)}",b.bottom<=v.height-maxOf(bottom,ime)+1)
  assertTrue("$tag top",b.top>=36);assertTrue("$tag left",b.left>=left);assertTrue("$tag right",b.right<=v.width-right)
  assertTrue("$tag touch height",b.height>=48*compose.activity.resources.displayMetrics.density)
  assertEquals("Dialog must delegate IME layout to Compose",android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,ShadowDialog.getLatestDialog().window!!.attributes.softInputMode and android.view.WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST)
 }
 private fun tap(tag:String){compose.onNodeWithTag(tag).performTouchInput{click()};compose.waitForIdle()}
 private fun open(tag:String){compose.showHomeSetting(tag).performTouchInput{click()};compose.waitForIdle()}
 private fun shot(name:String)=compose.runOnIdle{val v=decor();val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/source119-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun fourSettingsRoutesAndSecondaryFormsKeepReachableActionsWithNavigationBars() {
  start();try{
   ReadingStore(compose.activity,GutenbergSource.SOURCE).save("pg:999","kept-anchor",123)
   open("settings-source");insets();action("confirm-source-target");compose.onNodeWithTag("source-address-editor").performTextInput("https://bookhub.fixture.invalid");tap("confirm-source-target");compose.onNodeWithTag("bookhub-login-submit").assertExists();tap("bookhub-source-dialog-close")
   open("settings-gutenberg");assertEquals("find",vm.ui.value.topTab);assertEquals(GutenbergSource.SOURCE,vm.ui.value.catalogId);compose.onNodeWithTag("tab-settings").performClick()
   open("settings-opds");insets();action("add-opds-subscription");shot("opds-manager-bars");tap("add-opds-subscription")
   insets();compose.onNodeWithTag("opds-name").performTextInput("新自写订阅");compose.onNodeWithTag("opds-url").performTextInput("https://opds.fixture.invalid/new");compose.onNodeWithTag("opds-rights").performScrollTo().performClick();action("opds-add-confirm");shot("opds-add-bars");tap("opds-add-confirm")
   await{!vm.ui.value.addingCatalog&&vm.ui.value.topTab=="find"};assertEquals(2,OpdsSubscriptions(compose.activity).all().size)
   compose.onNodeWithTag("tab-settings").performClick();open("settings-legado");insets();action("import-legado");tap("import-legado")
   insets();compose.onNodeWithTag("legado-json").performTextInput(rule("新自写规则").put("bookSourceUrl","https://second.fixture.invalid").toString());compose.onNodeWithTag("legado-rights").performScrollTo().performClick();action("legado-import-confirm");shot("legado-import-bars");tap("legado-import-confirm")
   await{!vm.ui.value.addingCatalog&&vm.ui.value.topTab=="find"};assertEquals(2,LegadoSources(compose.activity).all().size)
   assertTrue(OpdsSubscriptions(compose.activity).all().contains(oldOpds));assertTrue(fixtures.calls.isEmpty());assertEquals(123,ReadingStore(compose.activity,GutenbergSource.SOURCE).position("pg:999")!!.offset)
  }finally{gutenberg.close()}
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun narrowLargeFontBookHubPrimaryCanBeTouchedAboveKeyboardThenKeyboardCloses() {
  start(2.0f);try{
   open("settings-source");compose.onNodeWithTag("source-address-editor").performScrollTo().performTextInput("https://bookhub.fixture.invalid")
   insets(ime=360);action("confirm-source-target",ime=360)
   val textLayouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
   compose.onNodeWithText("确认目标，返回登录",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(textLayouts)}
   assertTrue(textLayouts.isNotEmpty());assertFalse(textLayouts.single().hasVisualOverflow)
   shot("bookhub-small-keyboard");tap("confirm-source-target")
   insets(ime=360);action("bookhub-login-submit",ime=360);insets(ime=0);action("bookhub-login-submit");shot("bookhub-small-keyboard-closed")
   tap("bookhub-source-dialog-close");compose.onNodeWithTag("settings-page").assertExists();assertEquals(1,OpdsSubscriptions(compose.activity).all().size)
  }finally{gutenberg.close()}
 }
 @Test @Config(qualifiers="w640dp-h320dp-night-hdpi") fun shortLandscapeKeyboardKeepsBothImportActionsTouchableAndDoesNotLoseDraft() {
  start(1.8f,true);try{
   open("settings-legado");insets(bottom=0,left=72);action("import-legado",bottom=0,left=72);tap("import-legado")
   compose.onNodeWithTag("legado-json").performTextInput("{invalid self-authored json}")
   insets(bottom=0,ime=220,left=72);action("legado-import-confirm",bottom=0,ime=220,left=72);action("legado-import-dialog-close",bottom=0,ime=220,left=72);shot("legado-landscape-keyboard")
   insets(bottom=0,ime=0,left=72);compose.onNodeWithTag("legado-json").assertTextContains("{invalid self-authored json}")
   tap("legado-import-dialog-close");open("settings-opds");insets(bottom=0,ime=0,right=72);tap("add-opds-subscription")
   insets(bottom=0,ime=220,right=72);action("opds-add-confirm",bottom=0,ime=220,right=72);tap("opds-add-dialog-close")
   assertEquals(1,OpdsSubscriptions(compose.activity).all().size);assertEquals(1,LegadoSources(compose.activity).all().size)
  }finally{gutenberg.close()}
 }
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun gutenbergDetailPrimaryGrowsAndCanBeTouchedAfterScroll() {
  start(2f);try{
   open("settings-gutenberg");compose.onNodeWithTag("find-search-input").performTextInput("自写");compose.onNodeWithTag("find-search-input").performImeAction()
   await{!vm.ui.value.findLoading&&vm.ui.value.books.isNotEmpty()};val b=vm.ui.value.books.first()
   compose.onNodeWithTag("find-list").performScrollToNode(hasTestTag("find-book-${b.id}"));compose.onNodeWithTag("find-book-${b.id}").performTouchInput{click()}
   compose.onNodeWithTag("detail-read").assertExists();insets(dialog=false)
   compose.onNodeWithTag("detail-read").performScrollTo();compose.waitForIdle()
   val bounds=compose.onNodeWithTag("detail-read").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
   assertTrue(bounds.bottom<=owner(decor(false))!!.height-72+1)
   assertTrue(bounds.height>=48*compose.activity.resources.displayMetrics.density)
   tap("detail-read");await{vm.ui.value.chapter!=null};assertNull(vm.ui.value.error)
  }finally{gutenberg.close()}
 }
 @Test @Config(sdk=[35],qualifiers="w320dp-h480dp-night-hdpi") fun android15ManagerAndAddHonorGestureAndKeyboardInsets() {
  start(1.6f,true);try{
   open("settings-opds");insets(bottom=36);action("add-opds-subscription",bottom=36);tap("add-opds-subscription")
   compose.onNodeWithTag("opds-name").performTextInput("自写键盘验证");insets(bottom=36,ime=330)
   action("opds-add-confirm",bottom=36,ime=330);shot("opds-android15-keyboard")
   insets(bottom=36,ime=0);action("opds-add-confirm",bottom=36);compose.onNodeWithTag("opds-name").assertTextContains("自写键盘验证")
   tap("opds-add-dialog-close");assertEquals(listOf(oldOpds),OpdsSubscriptions(compose.activity).all())
  }finally{gutenberg.close()}
 }
 @Test fun cornerPaddingUsesAllFourCornersAndNeverNegative() {
  assertEquals(SourceCornerPadding(80,60,90,90),sourceCornerPadding(40,60,80,90))
  assertEquals(SourceCornerPadding(),sourceCornerPadding(-1,-1,-1,-1))
 }
}
