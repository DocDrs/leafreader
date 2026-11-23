package app.leafreader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch103UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun hiddenCopyShowsTotalAndPerBookBytesAndClearUpdatesTotalsPreservingPosition() {
  val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(compose.activity.application,w.network,vault,false)
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)}),sessionVault=vault,offlineRuntime=runtime);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};val b=vm.ui.value.shelfBooks.single()
  compose.runOnIdle{
   val cache=runtime.cache(b.sourceId);val epoch=cache.generation(b.id);val m=w.manifest();cache.saveManifest(b.id,m,epoch);w.bytes().forEach{(id,bytes)->cache.put(b.id,w.version,id,bytes,epoch)};cache.complete(b.id,m,epoch)
   vm.store.save(b.id,"kept",31);w.network.online=false;vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);vm.selectTab("settings")
  }
  val position=vm.store.position(b.id)
  compose.waitUntil(10000){compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("offline-total-bytes").assertTextContains("字节",substring=true)
  compose.onNodeWithTag("offline-management").performScrollTo().performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("hidden-clear-${b.id}").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("offline-book-bytes-${b.id}").assertTextContains("占用",substring=true)
  compose.onNodeWithTag("hidden-clear-${b.id}").performClick();compose.onNodeWithText("清除副本").performClick()
  compose.waitUntil(10000){runtime.cache(b.sourceId).status(b.id).saved==0};assertEquals(position,vm.store.position(b.id))
  compose.waitUntil(10000){compose.onAllNodesWithTag("hidden-copy-empty").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("offline-book-bytes-${b.id}").assertDoesNotExist();compose.onNodeWithText("关闭").performClick();compose.onNodeWithTag("offline-total-bytes").assertTextContains("字节",substring=true)
 }
 @Test fun logoutNoteIsVisibleAfterReopeningWithNoAccountNamesOrSizes() {
  lateinit var vm:ReaderViewModel
  val vault=MemorySessionVault()
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,sessionVault=vault);vm.selectTab("settings");compose.activity.setContent{MaterialTheme{ReaderApp(vm)}};vm.signOut()}
  compose.onNodeWithText("知道了").performClick()
  compose.onNodeWithTag("offline-retention-note").performScrollTo().assertIsDisplayed().assertTextContains("完整且版本匹配",substring=true)
  compose.runOnUiThread{val cold=ReaderViewModel(compose.activity.application,sessionVault=vault);cold.selectTab("settings");compose.activity.setContent{MaterialTheme{ReaderApp(cold)}}}
  compose.onNodeWithTag("offline-retention-note").performScrollTo().assertIsDisplayed()
  compose.onNodeWithTag("offline-management").performScrollTo().assertIsNotEnabled()
  compose.waitUntil(10000){compose.onAllNodesWithText("请登录当前来源后查看占用；未统计不代表副本已删除。").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("offline-total-bytes").assertDoesNotExist()
 }
}
