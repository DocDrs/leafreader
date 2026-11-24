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
class Patch110UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel;private lateinit var wire:ShelfSyncFixtures;private lateinit var b:Book
 private fun await(check:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();check()}
 private fun setup() {
  compose.runOnUiThread {
   wire=ShelfSyncFixtures();val c=wire.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val rt=OfflineDownloads(compose.activity,wire.network,vault,false).apply{setAutomaticWifi(false)}
   b=wire.book();val st=ReadingStore(compose.activity,b.sourceId);st.rememberServiceShelf(listOf(b));st.save(b.id,"saved",27)
   vm=ReaderViewModel(compose.activity.application,connectionGate=wire.gate(),sessionVault=vault,offlineRuntime=rt)
   compose.activity.setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
  }
  await{!vm.ui.value.shelfLoading};compose.runOnIdle{vm.syncShelfFromBookHub()};await{!vm.ui.value.syncingShelf}
 }
 private fun capture(name:String,dialog:Boolean=false)=compose.runOnIdle {
  val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);view.draw(canvas)
  if(dialog){val overlay=ShadowDialog.getLatestDialog().window!!.decorView;val pos=IntArray(2);overlay.getLocationOnScreen(pos);canvas.save();canvas.translate(pos[0].toFloat(),pos[1].toFloat());overlay.draw(canvas);canvas.restore()}
  File("build/patch110-previews").apply{mkdirs()}.resolve(name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
 }
 @Test fun remoteRemovalConfirmationFailureRetryAndLocalCleanupAreExplicit() {
  setup();wire.failure=409
  compose.onNodeWithTag("shelf-book-${b.id}").performTouchInput{longClick()};compose.onNodeWithTag("remove-book-${b.id}").performScrollTo().performClick()
  compose.onNodeWithText("移出书架？").assertExists();compose.onNodeWithText("同时清除本机下载和阅读进度").assertDoesNotExist();capture("01-remote-confirm",true)
  compose.onNodeWithTag("confirm-remove-shelf").performClick();await{vm.ui.value.notice!=null && !vm.membershipBusy(b)}
  compose.onNodeWithText("知道了").performClick();assertTrue(vm.isOnShelf(b));assertEquals(27,vm.positionOf(b)!!.offset)
  compose.onNodeWithTag("shelf-book-${b.id}").performTouchInput{longClick()};compose.onNodeWithTag("membership-retry").assertDoesNotExist();compose.onNodeWithTag("membership-local-remove").assertDoesNotExist();capture("02-failed-retry",true)
  wire.failure=0;compose.runOnIdle{vm.retryShelfOperation(b)};await{!vm.isOnShelf(b) && !vm.cleanupPending(b)};assertNull(vm.positionOf(b));assertFalse(b.id in wire.members)
 }
 @Test fun settingsDescribeMembershipOnlyAndFindAddWaitsForServiceSuccess() {
  setup();compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-source").assertExists()
  compose.onNodeWithTag("shelf-update-submit").assertDoesNotExist();compose.onNodeWithTag("shelf-update-result").assertDoesNotExist();capture("03-settings")
  compose.onNodeWithTag("tab-find").performClick();compose.runOnIdle{vm.search("自写")};await{vm.ui.value.books.isNotEmpty() && !vm.ui.value.findLoading}
  val found=vm.ui.value.books.single();wire.failure=403;compose.onNodeWithTag("add-shelf-${found.id}").performScrollTo().performClick();await{vm.ui.value.notice!=null && !vm.membershipBusy(found)};assertFalse(vm.isOnShelf(found));assertTrue(vm.ui.value.books.any{it.id==found.id})
  compose.onNodeWithText("知道了").performClick();wire.failure=0;compose.onNodeWithTag("add-shelf-${found.id}").performScrollTo().performClick();await{vm.isOnShelf(found)}
  compose.onNodeWithTag("add-shelf-${found.id}").assertDoesNotExist();assertTrue(found.id in wire.members);assertTrue(wire.calls.none{it.path.contains("reading-state")})
 }
 @Test fun loginDirectionChoicesAreExplicitAndRemoteRemovalNeedsSecondConfirmation() {
  setup();compose.runOnIdle{val extra=wire.book("public:2");ReadingStore(compose.activity,b.sourceId).addToShelf(extra);wire.members+="public:3";vm.signOut();vm.connect(wire.session.origin,wire.session.accountId,"synthetic")}
  await{vm.ui.value.shelfDifference!=null};compose.onNodeWithTag("shelf-difference").assertExists();capture("04-login-difference",true)
  compose.onNodeWithTag("shelf-choice-later").performClick();compose.onNodeWithTag("shelf-difference").assertDoesNotExist();assertTrue(vm.ui.value.shelfChoicePending);assertEquals(0,wire.mutationCount)
  compose.runOnIdle{vm.connect(wire.session.origin,wire.session.accountId,"synthetic")};await{vm.ui.value.shelfDifference!=null};compose.onNodeWithTag("shelf-choice-local").performScrollTo().performClick();compose.onNodeWithTag("shelf-local-confirm").assertExists();compose.onNodeWithText("自写书目 3").assertExists();assertEquals(0,wire.mutationCount);capture("05-confirm-direction",true)
  compose.onNodeWithText("返回").performClick();compose.onNodeWithTag("shelf-choice-merge").performScrollTo().performClick();await{!vm.ui.value.shelfChoicePending};assertEquals(0,wire.mutationCount);assertTrue(vm.isOnShelf(wire.book("public:2")));assertTrue(vm.isOnShelf(wire.book("public:3")));assertEquals(27,vm.positionOf(b)!!.offset)
 }

}
