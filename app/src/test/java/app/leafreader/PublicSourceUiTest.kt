package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import org.robolectric.RuntimeEnvironment
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.reader.NativeReaderView
import app.leafreader.sync.ShelfSyncRuntime
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlinx.coroutines.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublicSourceUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private lateinit var public:PublicSourceFixtures
 private lateinit var hub:ShelfSyncFixtures
 private lateinit var vault:MemorySessionVault
 private lateinit var downloads:OfflineDownloads
 private var fontScale=1f
 private fun await(test:()->Boolean)=compose.waitUntil(20000){compose.waitForIdle();test()}
 private fun start() {
  compose.runOnUiThread {
   public=PublicSourceFixtures();hub=ShelfSyncFixtures();vault=MemorySessionVault().apply{value=hub.client().snapshot()}
   downloads=OfflineDownloads(compose.activity,hub.network,vault,false).apply{setAutomaticWifi(false)}
   replace()
  };await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.isNotEmpty()}
 }
 private fun replace(){vm=ReaderViewModel(compose.activity.application,connectionGate=hub.gate(),sessionVault=vault,offlineRuntime=downloads,automaticShelfSync=ShelfSyncRuntime(compose.activity.application,vault,hub.gate(),downloads,scheduleJobs=false),independentSources=listOf(public.source(compose.activity)));compose.activity.setContent{val d=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(d.density,fontScale)){MaterialTheme(colorScheme=if(compose.activity.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK==android.content.res.Configuration.UI_MODE_NIGHT_YES)darkColorScheme(primary=Color(0xFFFFB69E),surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB))else lightColorScheme(primary=Color(0xFFA94435),surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}}}
 private fun shot(name:String)=compose.runOnIdle{val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/public114-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 private fun reader(v:View):NativeReaderView?{if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it};return null}
 @Test fun sourceSwitchBrowseAddHideOpenAndOfflineColdResumeKeepBookHubSession() {
  start();val revision=vm.ui.value.connectionRevision
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("source-gutenberg").performClick();compose.onNodeWithTag("browse-gutenberg").performClick()
  await{!vm.ui.value.findLoading && vm.ui.value.books.size==20};shot("public-browse")
  compose.onNodeWithTag("find-book-pg:1").performClick();compose.onNodeWithText("古登堡计划 · 公开源").assertIsDisplayed();compose.onNodeWithText("←  返回").performClick();assertFalse(public.calls.any{it.endsWith(".epub")})
  compose.onNodeWithTag("add-shelf-pg:1").performClick();compose.onNodeWithTag("find-book-pg:1").assertDoesNotExist()
  compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("shelf-book-pg:1").performClick()
  await{vm.ui.value.chapter!=null && !vm.ui.value.loading};assertEquals("封面",vm.ui.value.chapter!!.title)
  compose.runOnIdle{val ch=vm.ui.value.chapters[1].id;vm.jump(ch)};await{vm.ui.value.chapter?.title=="自写第一章"}
  compose.runOnIdle{reader(compose.activity.window.decorView)!!.turn(1)};await{vm.ui.value.offset>0};val anchor=vm.ui.value.offset
  compose.runOnIdle{vm.backToShelf()};assertEquals(anchor,vm.positionOf(vm.ui.value.shelfBooks.first{it.id=="pg:1"})!!.offset)
  val count=public.calls.size;public.network.up=false;compose.runOnUiThread{replace()};await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.any{it.id=="pg:1"}}
  compose.onNodeWithTag("shelf-book-pg:1").performClick();await{vm.ui.value.chapter?.title=="自写第一章"};assertEquals(anchor,vm.ui.value.offset);assertEquals(count,public.calls.size)
  assertTrue(vm.bookHubConnected());assertEquals(0,hub.mutationCount);assertNotNull(vault.value)
  compose.runOnIdle{vm.backToShelf()}
  compose.onNodeWithTag("tab-settings").performClick()
  compose.onNodeWithTag("settings-gutenberg").performScrollTo().assertIsDisplayed();shot("sources-settings")
  // Wait for the async usage block to settle, then scroll the footer above the overlay as a user does.
  compose.waitUntil(15000){compose.waitForIdle();compose.onAllNodesWithText("正在统计本机占用…").fetchSemanticsNodes().isEmpty()}
  compose.onNodeWithTag("settings-version-value").performScrollTo();compose.waitForIdle()
  compose.onNodeWithTag("settings-page").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.4f,height*.55f),androidx.compose.ui.geometry.Offset(width*.4f,height*.15f),300)}
  val versionBounds=compose.onNodeWithTag("settings-version-value").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
  assertTrue(versionBounds.bottom<=compose.onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot.top)
  shot("installed-version")
 }
 @Test @Config(qualifiers="w320dp-h480dp-night-hdpi") fun sourceChoicesWrapAtLargeFontAndRemainAccessible() {
  fontScale=1.8f;RuntimeEnvironment.setFontScale(fontScale);start();compose.onNodeWithTag("tab-find").performClick()
  compose.onNodeWithTag("source-gutenberg").performScrollTo().assertIsDisplayed().performClick()
  val bounds=compose.onNodeWithTag("source-gutenberg").fetchSemanticsNode().boundsInRoot
  assertTrue(bounds.right<=compose.activity.window.decorView.width);assertTrue(bounds.left>=0)
  compose.onNodeWithTag("browse-gutenberg").performScrollTo().assertIsDisplayed();shot("public-narrow-large")
 }
 @Test fun cancelledDownloadCannotOverwriteTheNewRetryStatus() {
  start();compose.runOnIdle{vm.selectCatalog(GutenbergSource.SOURCE);vm.browsePublic()};await{vm.ui.value.books.isNotEmpty()}
  val b=vm.ui.value.books.first();val entered=CompletableDeferred<Unit>();var held=false
  public.before={path->if(path.endsWith(".epub") && !held){held=true;entered.complete(Unit);awaitCancellation()}}
  compose.runOnIdle{vm.addToShelf(b);vm.download(b)};await{entered.isCompleted}
  compose.runOnIdle{vm.cancelDownload(b);vm.download(b)};await{!vm.downloadBusy(b)}
  assertTrue(runBlocking{vm.inspectDownload(b)}.complete);assertNull(vm.downloadTaskLabel(b));assertEquals(0,hub.mutationCount)
 }
 @Test fun settingsVersionComesFromInstalledMetadataInsteadOfAConstant() {
  start();val pm=compose.activity.packageManager;val info=pm.getPackageInfo(compose.activity.packageName,0)
  val previous=info.versionName;info.versionName="9.8.7-synthetic";org.robolectric.Shadows.shadowOf(pm).installPackage(info)
  try{compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-version-value").performScrollTo().assertTextEquals("9.8.7-synthetic")}finally{info.versionName=previous;org.robolectric.Shadows.shadowOf(pm).installPackage(info)}
 }
 @Test fun manualClearAndRemovalArePerSourceAndDoNotWriteBookHub() {
  start();compose.runOnIdle{vm.selectCatalog(GutenbergSource.SOURCE);vm.browsePublic()};await{vm.ui.value.books.isNotEmpty()}
  val b=vm.ui.value.books.first();compose.runOnIdle{assertTrue(vm.addToShelf(b));vm.open(b)};await{vm.ui.value.chapter!=null};compose.runOnIdle{vm.backToShelf()}
  assertNotNull(vm.positionOf(b));compose.runOnIdle{vm.clearDownload(b)};await{b.key !in vm.ui.value.clearingBooks && !vm.cleanupPending(b) && vm.positionOf(b)==null};assertNull(vm.positionOf(b));assertTrue(vm.isOnShelf(b))
  val hubBook=vm.ui.value.shelfBooks.first{it.sourceId.startsWith("bookhub-v1-")};assertTrue(vm.isOnShelf(hubBook));compose.runOnIdle{vm.store.save(hubBook.id,"synthetic-preserved",43)}
  compose.runOnIdle{vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision)};await{!vm.isOnShelf(b) && !vm.cleanupPending(b)};assertTrue(vm.isOnShelf(hubBook));assertEquals(0,hub.mutationCount);assertEquals(43,vm.positionOf(hubBook)!!.offset)
  compose.runOnIdle{vm.browsePublic()};await{!vm.ui.value.findLoading && vm.ui.value.books.any{it.id==b.id}};compose.runOnIdle{vm.addToShelf(b)};assertTrue(vm.isOnShelf(b));assertNull(vm.positionOf(b))
  compose.runOnIdle{vm.selectCatalog("bookhub")};assertTrue(vm.bookHubConnected());assertNotNull(vault.value);assertFalse(vm.ui.value.showShelfChoice)
 }
}
