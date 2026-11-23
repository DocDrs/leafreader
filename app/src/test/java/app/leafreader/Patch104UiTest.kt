package app.leafreader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.sha
import app.leafreader.reader.NativeReaderView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch104UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private fun node(book:Book)=compose.onNode(SemanticsMatcher.expectValue(SavedBookIdentity,book.key))
 private fun reader(v:View=compose.activity.window.decorView):NativeReaderView? {
  if(v is NativeReaderView)return v
  if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it}
  return null
 }
 @Test fun signedOutAndOtherAccountKeepBothRowsAndOnlyClickedOldBookShowsAuthError() {
  val calls=mutableListOf<String>();val vault=MemorySessionVault()
  val wire=WireTransport{uri,_,_,body,_->calls+=uri.path;val payload=when {
   uri.path.endsWith("/auth/login")->BookHubFixtures.envelope(BookHubFixtures.session(org.json.JSONObject(String(body!!)).getString("username")))
   uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page())
   uri.path.endsWith("/access")->BookHubFixtures.envelope(BookHubFixtures.txtManifest())
   uri.path.endsWith("/a")->BookHubFixtures.firstText
   else->BookHubFixtures.secondText
  };WireResponse(200,payload.toByteArray())}
  val gate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)})
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,connectionGate=gate,sessionVault=vault);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}};vm.connect("https://global-ui.fixture.invalid","a","fake")}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.isNotEmpty()};val a=vm.ui.value.shelfBooks.single()
  compose.onNodeWithTag("tab-shelf").performClick();node(a).performClick();compose.waitUntil(10000){reader()!=null};compose.runOnIdle{reader()!!.turn(1);reader()!!.performClick()}
  val position=vm.positionOf(a);assertTrue(position!!.offset>0)
  compose.onNodeWithText("←  书架").performClick();compose.runOnIdle{vm.signOut()};compose.onNodeWithText("知道了").performClick();compose.onNodeWithTag("tab-shelf").performClick();node(a).assertExists()
  val before=calls.size;node(a).performClick();compose.waitUntil(10000){vm.ui.value.error!=null};assertEquals(before,calls.size);assertEquals(position,vm.positionOf(a));compose.onNodeWithText("返回书架").performClick()
  compose.runOnIdle{vm.connect("https://global-ui.fixture.invalid","b","fake")};compose.waitUntil(10000){!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==2};compose.onNodeWithTag("tab-shelf").performClick()
  val b=vm.ui.value.shelfBooks.single{it.sourceId==vm.store.sourceId};node(a).assertExists();node(b).assertExists();assertNull(vm.positionOf(b))
  compose.runOnUiThread{val cold=ReaderViewModel(compose.activity.application,connectionGate=gate,sessionVault=vault);compose.activity.setContent{MaterialTheme{ReaderApp(cold)}}}
  compose.waitUntil(10000){compose.onAllNodes(SemanticsMatcher.expectValue(SavedBookIdentity,a.key)).fetchSemanticsNodes().isNotEmpty()};node(a).assertExists();node(b).assertExists()
 }
 @Test fun independentlyOwnedFileNativePagesResumeWithNoLoginAndNoShippedDemo() {
  val file=File(compose.activity.filesDir,"own-ui.txt").apply{parentFile!!.mkdirs();writeText("自写且永久离线授权的 UI 验证正文，翻页后保留字符位置。".repeat(300))};val expected=sha(file)
  val provider=object:BookSource {
   override val id="owned-local-ui104";override val independentOffline=true
   override suspend fun books()=emptyList<Book>();override suspend fun chapters(bookId:String)=listOf(ChapterRef("own-1","自有章节"))
   override suspend fun chapter(bookId:String,chapterId:String):Chapter{check(sha(file)==expected);return Chapter(chapterId,"自有章节",listOf(file.readText()))}
  }
  val book=Book("owned","自有合成资料","自写作者","","",1,0,sourceId=provider.id);ReadingStore(compose.activity,provider.id).addToShelf(book)
  lateinit var vm:ReaderViewModel
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,sessionVault=MemorySessionVault(),independentSources=listOf(provider));compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  compose.waitUntil(10000){!vm.ui.value.shelfLoading};node(book).performClick();compose.waitUntil(10000){reader()!=null}
  compose.runOnIdle{assertTrue(reader()!!.pageCount>2);reader()!!.turn(1)};val position=vm.positionOf(book)!!
  compose.runOnUiThread{val cold=ReaderViewModel(compose.activity.application,sessionVault=MemorySessionVault(),independentSources=listOf(provider));compose.activity.setContent{MaterialTheme{ReaderApp(cold)}}}
  node(book).performClick();compose.waitUntil(10000){reader()!=null};compose.runOnIdle{assertEquals(position.offset,reader()!!.currentOffset)}
 }
}
