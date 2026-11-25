package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch111UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @After fun resetFontScale(){compose.runOnUiThread{RuntimeEnvironment.setFontScale(1f)}}
 private lateinit var vm:ReaderViewModel;private lateinit var w:ShelfSyncFixtures;private lateinit var vault:MemorySessionVault
 private fun await(check:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();check()}
 private fun setup(remote:Set<String> = setOf("public:1"),local:String="public:2",fontScale:Float=1f) {
  compose.runOnUiThread {
   RuntimeEnvironment.setFontScale(fontScale)
   w=ShelfSyncFixtures().apply{members.clear();members.addAll(remote)};vault=MemorySessionVault();val b=w.book(local);ReadingStore(compose.activity,b.sourceId).apply{addToShelf(b);save(b.id,"kept-anchor",37)}
   val rt=OfflineDownloads(compose.activity,w.network,vault,false).apply{setAutomaticWifi(false)}
   vm=ReaderViewModel(compose.activity.application,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=rt)
   compose.activity.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)){MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}}
  }
  await{!vm.ui.value.shelfLoading}
 }
 private fun login(){compose.runOnIdle{vm.connect(w.session.origin,w.session.accountId,"synthetic")};await{!vm.ui.value.connecting && !vm.ui.value.shelfLoading}}
 private fun capture(name:String,dialog:Boolean=false)=compose.runOnIdle {
  val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);view.draw(canvas)
  if(dialog){val overlay=ShadowDialog.getLatestDialog().window!!.decorView;val pos=IntArray(2);overlay.getLocationOnScreen(pos);canvas.save();canvas.translate(pos[0].toFloat(),pos[1].toFloat());overlay.draw(canvas);canvas.restore()}
  File("build/patch111-previews").apply{mkdirs()}.resolve(name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
 }
 @Test fun compactChoiceIsLoginOnlyAndSettingsHasNoSyncControlsOrResults() {
  setup();compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("shelf-update-submit").assertDoesNotExist();compose.onNodeWithTag("shelf-update-result").assertDoesNotExist();compose.onNodeWithText("书架同步与本机数据").assertDoesNotExist();await{compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()};capture("01-settings")
  login();await{vm.ui.value.showShelfChoice};compose.onNodeWithText("书架不一致").assertExists();compose.onNodeWithText("以本机为准").assertExists();compose.onNodeWithText("从 BookHub 补到本机").assertExists();compose.onNodeWithText("暂不").assertExists();compose.onNodeWithText("只比较当前服务",substring=true).assertDoesNotExist();capture("02-choice",true)
  compose.onNodeWithTag("shelf-choice-later").performClick();compose.onNodeWithTag("shelf-difference").assertDoesNotExist();compose.runOnIdle{vm.refreshShelf()};await{!vm.ui.value.syncingShelf};compose.onNodeWithTag("shelf-difference").assertDoesNotExist();assertEquals(0,w.mutationCount)
  compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("shelf-update-submit").assertDoesNotExist();compose.onNodeWithTag("shelf-update-result").assertDoesNotExist()
 }
 @Test fun remoteRemovalHasNamedConfirmationAndBackDoesNotExecuteIt() {
  setup();login();await{vm.ui.value.showShelfChoice};compose.onNodeWithTag("shelf-choice-local").performClick();compose.onNodeWithText("从 BookHub 移出 1 本？").assertExists();compose.onNodeWithText("自写书目 1").assertExists();assertEquals(0,w.mutationCount);capture("03-removal",true)
  compose.onNodeWithText("返回").performClick();compose.onNodeWithTag("shelf-local-confirm").assertDoesNotExist();compose.onNodeWithTag("shelf-difference").assertExists();assertEquals(0,w.mutationCount)
  compose.onNodeWithTag("shelf-choice-merge").performClick();await{!vm.ui.value.shelfChoicePending};assertEquals(0,w.mutationCount);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset);compose.onNodeWithTag("shelf-difference").assertDoesNotExist();assertNull(vm.ui.value.membershipFeedback)
 }
 @Test fun localChoiceWithoutRemoteRemovalRunsWithoutSecondConfirmationOrSuccessPopup() {
  setup(remote=emptySet());login();await{vm.ui.value.showShelfChoice};compose.onNodeWithTag("shelf-choice-local").performClick();await{!vm.ui.value.shelfChoiceBusy && !vm.ui.value.shelfChoicePending}
  compose.onNodeWithTag("shelf-local-confirm").assertDoesNotExist();assertTrue("public:2" in w.members);assertEquals(1,w.mutationCount);assertNull(vm.ui.value.notice);assertNull(vm.ui.value.membershipFeedback);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
 }
 @Test fun noDifferenceIsSilentAndOrdinaryFailureIsShortWithRetryAvailable() {
  setup(remote=setOf("public:2"));login();assertFalse(vm.ui.value.showShelfChoice);compose.onNodeWithTag("shelf-difference").assertDoesNotExist();assertNull(vm.ui.value.notice)
  compose.onNodeWithTag("tab-shelf").performClick();w.failure=409;compose.onNodeWithTag("shelf-book-public:2").performTouchInput{longClick()};compose.onNodeWithTag("remove-book-public:2").performScrollTo().performClick();compose.onNodeWithTag("confirm-remove-shelf").performClick();await{vm.ui.value.notice!=null && !vm.membershipBusy(w.book("public:2"))};compose.onNodeWithText("书架修订冲突，请重试").assertExists();capture("04-failure",true);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
  compose.onNodeWithText("知道了").performClick();compose.onNodeWithTag("shelf-book-public:2").performTouchInput{longClick()};compose.onNodeWithTag("membership-retry").assertDoesNotExist();w.failure=0;compose.runOnIdle{vm.retryShelfOperation(w.book("public:2"))};await{!vm.isOnShelf(w.book("public:2"))};assertNull(vm.ui.value.membershipFeedback)
 }
 @Test @Config(sdk=[34],qualifiers="w320dp-h640dp-hdpi") fun narrowLargeTextChoiceRemainsScrollableAndCanBeDismissed() {
  setup(fontScale=1.5f);login();await{vm.ui.value.showShelfChoice};val layouts=mutableListOf<TextLayoutResult>();compose.onNodeWithText("书架不一致").performSemanticsAction(SemanticsActions.GetTextLayoutResult){it(layouts)};assertEquals(1.5f,layouts.single().layoutInput.density.fontScale);compose.onNodeWithTag("shelf-choice-local").performScrollTo().assertIsDisplayed();compose.onNodeWithTag("shelf-choice-merge").performScrollTo().assertIsDisplayed();capture("05-narrow-large-font",true);compose.onNodeWithTag("shelf-choice-later").performClick();compose.onNodeWithTag("shelf-difference").assertDoesNotExist();assertEquals(0,w.mutationCount)
 }
}
