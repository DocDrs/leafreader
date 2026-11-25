package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.sync.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual ReaderApp entry and touch/IME navigation; every book/account is synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WarmHomePreviewTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private lateinit var wire:ShelfSyncFixtures
 private fun await(test:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();test()}
 private fun start(dark:Boolean=false,font:Float=1f) {
  RuntimeEnvironment.setFontScale(font)
  compose.runOnUiThread {
   wire=ShelfSyncFixtures().apply{members.clear();(1..18).filter{it!=2}.forEach{members+="public:$it"}}
   val vault=MemorySessionVault().apply{value=wire.client().snapshot()}
   val dl=OfflineDownloads(compose.activity,wire.network,vault,false).apply{setAutomaticWifi(false)}
   vm=ReaderViewModel(compose.activity.application,connectionGate=wire.gate(),sessionVault=vault,offlineRuntime=dl,
    automaticShelfSync=ShelfSyncRuntime(compose.activity.application,vault,wire.gate(),dl,scheduleJobs=false))
   compose.activity.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,font)) {
    MaterialTheme(colorScheme=if(dark)darkColorScheme(surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB),primary=Color(0xFFFFB69E))else lightColorScheme(surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A),primary=Color(0xFFA94435))){ReaderApp(vm)}
   }}
  }
  await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==17}
 }
 private fun shot(name:String)=compose.runOnIdle {
  val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
  File("build/warm113-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
 }
 private fun scenes(dark:Boolean) {
  start(dark);val prefix=if(dark)"dark"else"light"
  compose.onNodeWithTag("shelf-book-public:1").assertIsDisplayed();shot("$prefix-shelf")
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed();shot("$prefix-find")
  compose.onNodeWithTag("find-search-input").performTextInput("自写");compose.onNodeWithTag("find-search-input").performImeAction()
  await{!vm.ui.value.findLoading && vm.ui.value.query=="自写"};compose.onNodeWithTag("find-unsubmitted").assertDoesNotExist();shot("$prefix-results")
  compose.onNodeWithTag("find-search-clear").performClick();compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed()
  compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-page").assertIsDisplayed();shot("$prefix-settings")
  compose.onNodeWithTag("tab-shelf").performClick();assertEquals(17,vm.ui.value.shelfBooks.size)
  assertEquals(0,wire.mutationCount)
 }
 @Test fun lightHomeThroughRealTabsSearchAndClear()=scenes(false)
 @Test fun darkHomeRemainsComparableThroughTheSameEntry()=scenes(true)
 @Test @Config(qualifiers="w320dp-h480dp-hdpi") fun narrowLargeFontKeepsInputAndTabsUsable() {
  start(false,1.6f);compose.onNodeWithTag("tab-find").performClick()
  compose.onNodeWithTag("find-search-input").assertIsDisplayed();compose.onNodeWithTag("find-search-submit").assertIsDisplayed();shot("light-narrow-large-find")
  for(t in listOf("shelf","find","settings")){val r=compose.onNodeWithTag("tab-$t").assertIsDisplayed().fetchSemanticsNode().boundsInRoot;assertTrue(r.height>=48*compose.activity.resources.displayMetrics.density)}
 }
}
