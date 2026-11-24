package app.leafreader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
class Patch105UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun gate(w:WireTransport)=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)})
 private fun row(b:Book)=compose.onNode(SemanticsMatcher.expectValue(SavedBookIdentity,b.key))
 private fun b(id:String,owner:String)=Book(id,"合成书$id","自写作者","","",0,0,"EPUB",owner)
 private fun seed(runtime:OfflineDownloads,owner:String,w:OfflineFixtures) {
  val cache=runtime.cache(owner);val m=w.manifest();val e=cache.generation("public:1");cache.saveManifest("public:1",m,e);w.bytes().forEach{(id,bytes)->cache.put("public:1",w.version,id,bytes,e)};assertTrue(cache.complete("public:1",m,e))
 }
 @Test fun twoThousandBookSettingsShowsOnlyTotalAndHiddenCleanerUsesLazyRows() {
  val app=compose.activity.application;val w=OfflineFixtures();w.network.online=false;val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false)
  val books=(1..2000).map{b("public:$it",owner)};val st=ReadingStore(app,owner);st.rememberServiceShelf(books);st.hideFromShelf(books.take(120).map{it.id}.toSet())
  val cache=runtime.cache(owner);for(book in books.take(120)){val m=w.manifest().put("bookId",book.id);cache.saveManifest(book.id,m,cache.generation(book.id))}
  val other=BookHubSource.ownerId(w.client("other"));val otherBook=b("public:1",other).copy(title="别的账号隐藏书不可见");ReadingStore(app,other).addToShelf(otherBook);ReadingStore(app,other).hideFromShelf(setOf(otherBook.id));runtime.cache(other).saveManifest(otherBook.id,w.manifest(),0)
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);vm.selectTab("settings");compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){compose.onAllNodesWithTag("offline-total-bytes").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithText("管理离线副本与失败任务").assertDoesNotExist();compose.onNodeWithText(books[120].title).assertDoesNotExist();compose.onNodeWithText(books[0].title).assertDoesNotExist()
  compose.onNodeWithTag("offline-management").performScrollTo().performClick()
  compose.waitUntil(15000){compose.onAllNodesWithTag("hidden-clear-public:1").fetchSemanticsNodes().isNotEmpty()}
  val matcher=SemanticsMatcher("hidden cleanup buttons"){it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("hidden-clear-")==true}
  assertTrue(compose.onAllNodes(matcher).fetchSemanticsNodes().size in 1..8)
  compose.onNodeWithTag("hidden-copy-list").performScrollToNode(hasTestTag("hidden-clear-public:120"));compose.onNodeWithTag("hidden-clear-public:120").assertIsDisplayed()
  compose.onNodeWithText(otherBook.title).assertDoesNotExist();assertEquals(0,w.calls.size);assertEquals(1880,vm.ui.value.shelfBooks.size)
 }
 @Test fun completeSingleBookMoreHasNoRedownloadAndClearRequiresConfirmationThenUpdatesMenu() {
  val app=compose.activity.application;val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val book=b("public:1",owner);val st=ReadingStore(app,owner);st.addToShelf(book);st.save(book.id,"kept",17);seed(runtime,owner,w);w.network.online=false
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);runtime.enqueue(owner,book);runtime.update(runtime.request(runtime.key(owner,book.id))!!,"FAILED","合成重新下载失败，旧副本保留");compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};val position=st.position(book.id)
  row(book).performTouchInput{longClick()};compose.waitUntil(10000){compose.onAllNodesWithTag("download-status-${book.id}").fetchSemanticsNodes().any{it.config.toString().contains("完整缓存")}}
  compose.onNodeWithTag("download-status-${book.id}").assertTextContains("完整缓存",substring=true)
  compose.onNodeWithTag("download-copy-${book.id}").assertDoesNotExist()
  compose.onNodeWithTag("download-book-${book.id}").assertDoesNotExist();assertNull(vm.ui.value.book)
  compose.onNodeWithTag("clear-download-${book.id}").performClick();compose.onNodeWithText("取消").performClick();assertTrue(runtime.cache(owner).status(book.id,true).complete)
  compose.onNodeWithTag("clear-download-${book.id}").performClick();compose.onNodeWithText("清除缓存与进度").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("download-book-${book.id}").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("download-book-${book.id}").assertExists();assertNull(st.position(book.id));assertTrue(vm.isOnShelf(book));assertEquals(0,w.calls.size)
 }
 @Test fun failedPartialRetryIsExplicitKeepsBytesAndUsesWifiDefaultAndCancelStaysInMenu() {
  val app=compose.activity.application;val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val book=b("public:1",owner);val st=ReadingStore(app,owner);st.addToShelf(book);st.save(book.id,"kept",29)
  val cache=runtime.cache(owner);val m=w.manifest();cache.saveManifest(book.id,m,0);val bytes=w.bytes().getValue("a");cache.put(book.id,w.version,"a",bytes,0);w.network.online=false
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);runtime.enqueue(owner,book);runtime.update(runtime.request(runtime.key(owner,book.id))!!,"FAILED","合成网络失败");compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};val old=runtime.request(runtime.key(owner,book.id))!!;val position=st.position(book.id)
  row(book).performTouchInput{longClick()};compose.waitUntil(10000){compose.onAllNodesWithTag("download-book-${book.id}").fetchSemanticsNodes().isNotEmpty()}
  compose.onNodeWithTag("download-status-${book.id}").assertTextContains("下载失败",substring=true);compose.onNodeWithTag("download-book-${book.id}").performClick();assertEquals(old.nonce,runtime.request(old.key)!!.nonce)
  compose.onNodeWithText("加入下载队列").performClick();val next=runtime.request(old.key)!!;assertNotEquals(old.nonce,next.nonce);assertFalse(next.allowMetered);assertArrayEquals(bytes,cache.resource(book.id,w.version,"a"));assertEquals(position,st.position(book.id))
  row(book).performTouchInput{longClick()};compose.onNodeWithTag("cancel-download-${book.id}").performClick()
  compose.waitUntil(10000){compose.onAllNodesWithTag("download-status-${book.id}").fetchSemanticsNodes().any{it.config.toString().contains("已取消")}}
  assertEquals(0,w.calls.size);assertEquals(position,st.position(book.id));assertNull(vm.ui.value.book)
 }
}
