package app.leafreader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.reader.NativeReaderView
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
class Patch102UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private class Source:BookSource {
  override val id="ui102"
  var failure=false
  override suspend fun books()=listOf(Book("one","自写恢复测试","作者","","",2,0))
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("a","第一章"),ChapterRef("b","第二章"))
  override suspend fun chapter(bookId:String,chapterId:String):Chapter {
   if(chapterId=="b" && failure)throw SourceProblem(FailureKind.NETWORK,"合成网络失败")
   return Chapter(chapterId,chapterId,List(30){"只使用自写文本验证分页与错误返回。".repeat(8)})
  }
 }
 private fun reader(v:View=compose.activity.window.decorView):NativeReaderView? {
  if(v is NativeReaderView)return v
  if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it}
  return null
 }
 @Test fun failedNextChapterHasWorkingReturnAndRetryActions() {
  lateinit var vm:ReaderViewModel;val source=Source()
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault());compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading}
  compose.onNodeWithTag("shelf-book-one").performClick();compose.waitUntil(10000){reader()!=null}
  compose.runOnIdle{reader()!!.turn(1);source.failure=true;reader()!!.performClick()}
  val anchor=vm.ui.value.offset
  compose.onNodeWithText("下一章").performClick();compose.waitUntil(10000){vm.ui.value.error!=null}
  compose.onNodeWithTag("return-readable-page").assertIsDisplayed().performClick()
  compose.waitUntil(10000){reader()!=null};compose.runOnIdle{assertEquals(anchor,reader()!!.currentOffset);reader()!!.turn(1);assertTrue(vm.ui.value.offset>anchor)}
  compose.runOnIdle{vm.boundary(1)};compose.waitUntil(10000){vm.ui.value.error!=null}
  source.failure=false;compose.onNodeWithText("重试").performClick();compose.waitUntil(10000){vm.ui.value.chapter?.id=="b" && vm.ui.value.error==null}
 }
 @Test fun invalidSavedChapterOffersExplicitRecoveryAndKeepsOldRecord() {
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,Source(),sessionVault=MemorySessionVault());vm.store.save("one","old-missing",91);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};compose.onNodeWithTag("shelf-book-one").performClick()
  compose.waitUntil(10000){vm.ui.value.error!=null};assertEquals("old-missing",vm.store.position("one")!!.chapterId)
  compose.onNodeWithText("主动打开目录").assertIsDisplayed()
  compose.onNodeWithText("从首个可读页恢复（保留旧记录）").performClick();compose.waitUntil(10000){reader()!=null}
  assertEquals("a",vm.ui.value.chapter!!.id)
  assertTrue(compose.activity.getSharedPreferences("reader-ui102",0).all.keys.any{it.startsWith("recovery-one-")})
 }
 @Test fun settingsManagesHiddenCompleteCopyAndVerificationDoesNotDownload() {
  val wire=app.leafreader.OfflineFixtures();val client=wire.client()
  val vault=MemorySessionVault().apply{value=client.snapshot()}
  val runtime=app.leafreader.offline.OfflineDownloads(compose.activity.application,wire.network,vault,false)
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=runtime);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};val book=vm.ui.value.shelfBooks.single()
  compose.runOnIdle{
   val cache=runtime.cache(book.sourceId);val m=wire.manifest();val epoch=cache.generation(book.id)
   cache.saveManifest(book.id,m,epoch);wire.bytes().forEach{(id,bytes)->cache.put(book.id,wire.version,id,bytes,epoch)};cache.complete(book.id,m,epoch)
   vm.store.save(book.id,"kept",37);wire.network.online=false;vm.removeFromShelf(setOf(book.key),vm.ui.value.connectionRevision);vm.selectTab("settings")
  }
  val before=wire.calls.size;val position=vm.store.position(book.id)
  compose.onNodeWithTag("offline-management").performScrollTo().performClick()
  compose.waitUntil(10000){compose.onAllNodesWithText("核验与下载操作").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("核验与下载操作").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("download-status-${book.id}").fetchSemanticsNodes().any{it.config.toString().contains("已下载")}}
  compose.onNodeWithTag("verify-download-${book.id}").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("download-check-result-${book.id}").fetchSemanticsNodes().isNotEmpty()}
  assertEquals(before,wire.calls.size);assertNull(runtime.request(runtime.key(book.sourceId,book.id)))
  compose.onNodeWithTag("clear-download-${book.id}").performClick();compose.onNodeWithText("清除副本").performClick()
  compose.waitUntil(10000){runtime.cache(book.sourceId).status(book.id).saved==0}
  assertEquals(position,vm.store.position(book.id));assertEquals(before,wire.calls.size)
 }
}
