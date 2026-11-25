package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.*
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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AutomaticDownloadSettingsUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel;private lateinit var queue:AutomaticSourceDownloads
 private val mobile=object:ConnectionState{override val online=true;override val wifi=false}
 private val source=object:BookSource {
  override val id="self-authored-settings-ui"
  override suspend fun books()=listOf(Book("self:1","自写测试书","自写作者","","",1,0,sourceId=id))
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("raw","自写章"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter("raw","自写章",listOf("自写正文"))
 }
 private fun replace(dark:Boolean=false){val app=compose.activity.application;queue=AutomaticSourceDownloads(app,mobile,false)
  vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false,automaticSourceDownloads=queue)
  compose.activity.setContent{MaterialTheme(colorScheme=if(dark)darkColorScheme(primary=Color(0xFFFFB69E),background=Color(0xFF17221F),surface=Color(0xFF223029),onBackground=Color(0xFFEEF2EB),onSurface=Color(0xFFEEF2EB),surfaceContainer=Color(0xFF2B3B32))else lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
 }
 private fun start(legacy:Boolean?=null,font:Float=1f,dark:Boolean=false){RuntimeEnvironment.setFontScale(font);compose.runOnUiThread{
  legacy?.let{compose.activity.getSharedPreferences("global-auto-download-v1",0).edit().putBoolean("enabled",it).remove("wifi-only").commit()}
  ReadingStore(compose.activity,source.id).save("self:1","raw",103);replace(dark)
 };await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("tab-settings").performClick()}
 private fun await(test:()->Boolean)=compose.waitUntil(10000){compose.waitForIdle();test()}
 private fun tap(tag:String){compose.showHomeSetting(tag).performTouchInput{click(androidx.compose.ui.geometry.Offset(18f,height/2f))};compose.waitForIdle()}
 private fun shot(name:String){await{compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()};compose.runOnIdle{val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/download1110-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}}
 @Test fun independentSwitchesUseActualRowTouchesAndPersistDisabledChoiceAcrossColdViewModel() {
  start();compose.onNodeWithText("离线下载").assertDoesNotExist()
  compose.showHomeSetting("automatic-wifi").assertIsOff();compose.showHomeSetting("automatic-wifi-only").assertIsOn().assertIsNotEnabled()
  tap("automatic-wifi-only");assertTrue(vm.ui.value.automaticWifiOnly);assertFalse(GlobalDownloadPreferences(compose.activity).networkAllowed(mobile))
  tap("automatic-wifi");compose.onNodeWithTag("automatic-wifi").assertIsOn();compose.onNodeWithTag("automatic-wifi-only").assertIsEnabled();tap("automatic-wifi-only")
  compose.onNodeWithTag("automatic-wifi-only").assertIsOff();assertTrue(GlobalDownloadPreferences(compose.activity).networkAllowed(mobile));shot("enabled-data-allowed")
  tap("automatic-wifi");compose.onNodeWithTag("automatic-wifi-only").assertIsOff().assertIsNotEnabled();assertFalse(GlobalDownloadPreferences(compose.activity).networkAllowed(mobile));shot("disabled-choice-kept")
  compose.runOnUiThread{replace()};await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("tab-settings").performClick()
  compose.showHomeSetting("automatic-wifi").assertIsOff();compose.showHomeSetting("automatic-wifi-only").assertIsOff().assertIsNotEnabled()
  tap("automatic-wifi");assertTrue(GlobalDownloadPreferences(compose.activity).networkAllowed(mobile));assertTrue(queue.requests().isEmpty());assertEquals(103,ReadingStore(compose.activity,source.id).position("self:1")!!.offset)
 }
 @Test fun upgradingEnabled119PolicyLeavesBothSwitchesOnAndDoesNotQueueOldBooks() {
  start(legacy=true);compose.showHomeSetting("automatic-wifi").assertIsOn();compose.showHomeSetting("automatic-wifi-only").assertIsOn().assertIsEnabled()
  assertFalse(GlobalDownloadPreferences(compose.activity).networkAllowed(mobile));assertTrue(queue.requests().isEmpty());shot("upgrade-strict-default")
 }
 @Test @Config(qualifiers="w320dp-h480dp-night-hdpi") fun narrowLargeTypeKeepsLabelsAndSwitchesReachableWithoutExtraHeading() {
  start(legacy=true,font=1.8f,dark=true);compose.showHomeSetting("automatic-wifi-only").assertIsOn()
  val b=compose.onNodeWithTag("automatic-wifi-only").fetchSemanticsNode().boundsInRoot;assertTrue(b.left>=0&&b.right<=compose.activity.window.decorView.width)
  val result=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
  compose.onNodeWithText("仅在不计费 Wi-Fi 下自动下载",useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(result)}
  assertFalse(result.single().hasVisualOverflow);tap("automatic-wifi-only");compose.onNodeWithTag("automatic-wifi-only").assertIsOff();shot("narrow-large-dark")
  tap("automatic-wifi");compose.showHomeSetting("automatic-wifi-only").assertIsNotEnabled();assertTrue(queue.requests().isEmpty())
 }
}
