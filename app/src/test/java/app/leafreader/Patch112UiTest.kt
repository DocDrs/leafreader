package app.leafreader

import android.view.View
import android.graphics.Bitmap
import android.graphics.Canvas
import java.io.File
import org.robolectric.shadows.ShadowDialog
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.reader.NativeReaderView
import app.leafreader.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch112UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private val local=object:BookSource {
  override val id="gesture-ui-fixture";override suspend fun books()=listOf(Book("gesture","自写手势验证","自写作者","","",60,0,sourceId=id))
  override suspend fun chapters(bookId:String)=(1..60).map{ChapterRef("c$it","自写第 $it 章")}
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter(chapterId,"自写 $chapterId",List(40){"这段自写中文用于触摸、分页及续读回归，不含任何真实书籍内容。".repeat(2)})
 }
 private fun await(check:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();check()}
 private fun find(v:View=compose.activity.window.decorView):NativeReaderView? {if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
 private fun reader()=find()!!
 private fun capture(name:String,dialog:Boolean=false)=compose.runOnIdle {
  val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);val canvas=Canvas(bitmap);view.draw(canvas)
  if(dialog){val overlay=ShadowDialog.getLatestDialog().window!!.decorView;val pos=IntArray(2);overlay.getLocationOnScreen(pos);canvas.save();canvas.translate(pos[0].toFloat(),pos[1].toFloat());overlay.draw(canvas);canvas.restore()}
  File("build/patch112-previews").apply{mkdirs()}.resolve(name+".png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
 }
 private fun localSetup() {
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,local,sessionVault=MemorySessionVault(),linkBookHubShelf=false);compose.activity.setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}}
  await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("shelf-book-gesture").performClick();await{find()?.pageCount?.let{it>3}==true}
 }
 private fun swipe(from:Float,to:Float)=compose.onNodeWithTag("native-reader").performTouchInput{swipe(Offset(width*from,height*.5f),Offset(width*to,height*.5f),240)}
 private fun tap(x:Float)=compose.onNodeWithTag("native-reader").performTouchInput{click(Offset(width*x,height*.5f))}
 @Test fun realMotionFromInteriorLeftAndRightTurnsOnceAndPreservesTapsMenuAndResume() {
  localSetup();capture("01-reader");swipe(.22f,.12f);compose.runOnIdle{assertEquals(1,reader().currentPage);assertEquals(reader().currentOffset,vm.store.position("gesture")!!.offset)}
  swipe(.78f,.88f);compose.runOnIdle{assertEquals(0,reader().currentPage)};tap(.84f);compose.runOnIdle{assertEquals(1,reader().currentPage)};tap(.16f);compose.runOnIdle{assertEquals(0,reader().currentPage)}
  tap(.5f);compose.onNodeWithText("阅读设置").assertExists();val old=reader().currentOffset;swipe(.65f,.4f);compose.runOnIdle{assertEquals(old,reader().currentOffset)};tap(.5f);compose.onNodeWithText("阅读设置").assertDoesNotExist()
  swipe(.65f,.45f);val saved=reader().currentOffset;compose.runOnIdle{vm.backToShelf()};compose.onNodeWithTag("shelf-book-gesture").performClick();await{find()!=null};compose.runOnIdle{assertEquals(saved,reader().currentOffset)}
 }
 @Test fun outerEdgesShortAndVerticalTouchSequencesNeverFlipOrOpenTheMenu() {
  localSetup();val before=reader().currentOffset
  swipe(.002f,.4f);swipe(.998f,.6f);compose.onNodeWithText("阅读设置").assertDoesNotExist()
  compose.onNodeWithTag("native-reader").performTouchInput{swipe(Offset(width*.5f,height*.6f),Offset(width*.51f,height*.3f),250)}
  compose.onNodeWithText("阅读设置").assertDoesNotExist()
  val short=android.view.ViewConfiguration.get(reader().context).scaledTouchSlop*1.25f
  compose.onNodeWithTag("native-reader").performTouchInput{swipe(Offset(width*.5f,height*.5f),Offset(width*.5f+short,height*.5f),180)}
  compose.runOnIdle{assertEquals(before,reader().currentOffset);assertTrue(reader().systemGestureExclusionRects.isEmpty())};compose.onNodeWithText("阅读设置").assertDoesNotExist()
 }
 @Test fun swipeChapterBoundariesDirectoryScrollingAndSettingsUseSeparateInputs() {
  localSetup();compose.runOnIdle{repeat(reader().pageCount-1){reader().turn(1)}};swipe(.65f,.4f);await{vm.ui.value.chapter?.id=="c2" && !vm.ui.value.loading && find()!=null};compose.runOnIdle{assertEquals(0,reader().currentPage)}
  swipe(.35f,.6f);await{vm.ui.value.chapter?.id=="c1" && !vm.ui.value.loading && find()!=null};compose.runOnIdle{assertEquals(reader().pageCount-1,reader().currentPage)}
  val old=reader().currentOffset;tap(.5f);compose.onNodeWithText("目录").performClick();compose.onNodeWithTag("directory-list").performTouchInput{swipeUp()};compose.runOnIdle{assertEquals(old,reader().currentOffset);assertEquals("c1",vm.ui.value.chapter!!.id)}
  compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()};await{compose.onAllNodesWithTag("directory-list").fetchSemanticsNodes().isEmpty()};compose.onNodeWithText("阅读设置").performClick()
  compose.onNodeWithContentDescription("行距").performScrollTo().performTouchInput{swipeLeft()};compose.runOnIdle{assertEquals(old,vm.store.position("gesture")!!.offset);assertEquals(old,reader().currentOffset)}
 }
 private lateinit var wire:ShelfSyncFixtures;private lateinit var runtime:ShelfSyncRuntime;private lateinit var books:List<Book>
 private fun connectedSetup() {
  compose.runOnUiThread {
   wire=ShelfSyncFixtures().apply{members+="public:2"};val c=wire.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val dl=OfflineDownloads(compose.activity,wire.network,vault,false).apply{setAutomaticWifi(false)};runtime=ShelfSyncRuntime(compose.activity.application,vault,wire.gate(),dl,scheduleJobs=false)
   books=listOf(wire.book(),wire.book("public:2"));for(b in books)ReadingStore(compose.activity,b.sourceId).apply{addToShelf(b);save(b.id,"saved",31)}
   vm=ReaderViewModel(compose.activity.application,connectionGate=wire.gate(),sessionVault=vault,offlineRuntime=dl,automaticShelfSync=runtime);compose.activity.setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
  }
  await{!vm.ui.value.shelfLoading}
 }
 private fun openRemove(id:String){compose.onNodeWithTag("shelf-book-$id").performTouchInput{longClick()};compose.onNodeWithTag("remove-book-$id").performScrollTo().performClick()}
 private fun assertShortRemove(){compose.onNodeWithText("移出书架？").assertExists();compose.onNodeWithText("同时清除本机下载和阅读进度").assertDoesNotExist();compose.onNodeWithText("移除").assertExists();compose.onNodeWithText("移除并同步").assertDoesNotExist();compose.onNodeWithText("移出并同步").assertDoesNotExist()}
 @Test fun singleAndBatchUseIdenticalShortConfirmationAndSignedOutRemovalIsLocalOnly() {
  connectedSetup();openRemove("public:1");assertShortRemove();capture("02-single-remove",true);compose.onNodeWithText("取消").performClick();assertEquals(0,wire.mutationCount)
  compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-select-all").performClick();compose.onNodeWithTag("shelf-batch-remove").performClick();assertShortRemove();capture("03-batch-remove",true);compose.onNodeWithTag("confirm-remove-shelf").performClick();await{vm.ui.value.shelfBooks.isEmpty()};assertEquals(2,wire.mutationCount);assertNull(vm.ui.value.notice)
 }
 @Test fun transientConflictIsSilentAndRevokedSessionKeepsDataWithAnHonestSettingLabel() {
  connectedSetup();var once=true;wire.responseOverride={c->if(c.method=="DELETE" && once){once=false;wire.revisions["public:1"]=5;WireResponse(409,"{\"code\":\"REVISION_CONFLICT\"}".toByteArray())}else null}
  openRemove("public:1");compose.onNodeWithTag("confirm-remove-shelf").performClick();await{!vm.isOnShelf(books[0])};assertNull(vm.ui.value.notice);compose.onNodeWithTag("membership-retry").assertDoesNotExist()
  wire.responseOverride=null;wire.failure=401;openRemove("public:2");compose.onNodeWithTag("confirm-remove-shelf").performClick();await{!vm.bookHubConnected()};assertTrue(vm.isOnShelf(books[1]));assertEquals(31,vm.positionOf(books[1])!!.offset);assertNull(vm.ui.value.notice)
  compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithText(vm.bookHubConnectionLabel()).assertExists();compose.onNodeWithTag("shelf-update-submit").assertDoesNotExist();compose.onNodeWithTag("membership-retry").assertDoesNotExist()
 }
 @Test fun loggedOutRemovalUsesTheSameLocalDialogAndNeverChangesTheRemoteShelf() {
  connectedSetup();compose.runOnIdle{vm.signOut();vm.dismissNotice()};compose.onNodeWithTag("tab-shelf").performClick();openRemove("public:1");assertShortRemove();compose.onNodeWithTag("confirm-remove-shelf").performClick();await{!vm.isOnShelf(books[0])};assertEquals(0,wire.mutationCount);assertTrue("public:1" in wire.members);assertNull(vm.positionOf(books[0]));assertEquals(31,vm.positionOf(books[1])!!.offset)
 }

}
