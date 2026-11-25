package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.OfflineStatus
import app.leafreader.reader.NativeReaderView
import org.json.JSONArray
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
class UnifiedDisplayUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private lateinit var vm:ReaderViewModel
 private fun await(test:()->Boolean)=compose.waitUntil(20000){compose.waitForIdle();test()}
 private fun reader(v:View=compose.activity.window.decorView):NativeReaderView?{if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it};return null}
 private fun shot(name:String)=compose.runOnIdle{val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b));File("build/simplified118-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
 @Test fun bookHubOriginalMetadataTocAndBodyRenderSimplifiedAndOldAnchorIsNotRewritten() {
  val text="<html><body><h1 id='one'>第一章　啟程</h1><p>"+"這是自寫示例文字，頭髮乾乾淨淨，二噁英資料須謹慎閱讀。".repeat(250)+"</p><h1 id='two'>第二章　歸岸</h1><p>這是第二章的自寫文字。</p></body></html>"
  val manifest=BookHubFixtures.epubManifest().apply{getJSONArray("toc").getJSONObject(0).put("label","第一章　啟程");getJSONArray("toc").getJSONObject(1).put("label","第二章　歸岸")}
  var requests=0
  val client=BookHubClient(ServiceOrigin("https://unified.fixture.invalid"),WireTransport{u,m,_,_,_->
   assertEquals("GET",m);requests++
   val body=when{u.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page().apply{getJSONArray("items").getJSONObject(0).put("title","自寫繁體書").put("author","自寫作者").put("format","EPUB")})
    u.path.endsWith("/access")->BookHubFixtures.envelope(manifest);else->text}
   WireResponse(200,body.toByteArray())
  })
  client.restore(SavedSession(client.origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))
  val source=BookHubSource(client);val refs=kotlinx.coroutines.runBlocking{source.chapters("public:1")};val original=kotlinx.coroutines.runBlocking{source.chapter("public:1",refs.first().id)}
  val oldAnchor=original.text.indexOf("二噁英",100)+2;assertTrue(oldAnchor>100)
  val store=ReadingStore(compose.activity.application,source.id);store.save("public:1",original.id,oldAnchor)
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault(),linkBookHubShelf=false);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  await{!vm.ui.value.shelfLoading&&vm.ui.value.shelfBooks.isNotEmpty()}
  compose.onNodeWithTag("book-title-public:1",useUnmergedTree=true).assertTextEquals("自写繁体书")
  assertEquals("自寫繁體書",vm.ui.value.shelfBooks.single().title)
  compose.onNodeWithTag("shelf-book-public:1").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null}
  assertEquals(oldAnchor,vm.ui.value.offset);assertEquals(original.id,vm.ui.value.chapter!!.id);assertEquals(original.text,vm.ui.value.chapter!!.text)
  compose.runOnIdle{val view=reader()!!;assertEquals(oldAnchor,view.currentOffset);val display=view.currentLayout!!.layout.text.toString();assertTrue(display.contains("这是自写示例文字"));assertFalse(display.contains("這是自寫"));assertNotEquals(display.length,original.text.length)}
  shot("simplified-reader")
  compose.runOnIdle{reader()!!.performClick()};compose.onNodeWithText("目录").performClick()
  compose.onNodeWithText("第一章　启程").assertExists();compose.onNodeWithText("第二章　归岸").assertExists()
  assertEquals(oldAnchor,store.position("public:1")!!.offset);assertTrue(requests>0)
 }
 @Test fun variantFallbackKeepsEffectiveQueryCursorAndSourceFieldsThenShelfDetailAndReaderAreSimplified() {
  val calls=mutableListOf<Pair<String,String?>>()
  val rawChapter=Chapter("章節:raw/1","第一章　啟程",listOf("這是自寫正文，二噁英與頭髮都要正確顯示。".repeat(200)))
  val provider=object:IndependentBookSource {
   override val id="opds-synthetic-display-v1";override val label="受控書源";override val independentOffline=true
   val book=Book("raw-1","紅樓夢","自寫作者","繁體簡介","測試",1,0xFF536B67,sourceId=id)
   override suspend fun books()=emptyList<Book>()
   override suspend fun browse(query:String,cursor:String?):BookPage {calls+=query to cursor
    if(query=="网络")throw SourceProblem(FailureKind.NETWORK,"网络测试失败")
    if(query!="紅樓夢")return BookPage(emptyList())
    return if(cursor==null)BookPage(listOf(book),"游標:raw+2",true)else{assertEquals("游標:raw+2",cursor);BookPage(listOf(book.copy(id="raw-2",title="補紅樓夢")))}
   }
   override suspend fun chapters(bookId:String)=listOf(ChapterRef(rawChapter.id,rawChapter.title))
   override suspend fun chapter(bookId:String,chapterId:String)=rawChapter.also{assertEquals(rawChapter.id,chapterId)}
   override suspend fun thumbnail(book:String):Bitmap?=null
   override suspend fun status(book:String)=OfflineStatus(1,1,true)
   override suspend fun clear(book:String){}
   override suspend fun download(book:String,allowMetered:Boolean){}
  }
  val rawAnchor=rawChapter.text.indexOf("二噁英",100)+2
  ReadingStore(compose.activity.application,provider.id).save("raw-1",rawChapter.id,rawAnchor)
  compose.runOnUiThread{vm=ReaderViewModel(compose.activity.application,sessionVault=MemorySessionVault(),independentSources=listOf(provider),linkBookHubShelf=false);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}};vm.selectCatalog(provider.id);vm.selectTab("find")}
  await{!vm.ui.value.shelfLoading};compose.onNodeWithTag("find-search-input").performTextInput("红楼梦");compose.onNodeWithTag("find-search-input").performImeAction()
  await{!vm.ui.value.findLoading&&vm.ui.value.books.isNotEmpty()};assertEquals(listOf("红楼梦" to null,"紅樓夢" to null),calls);assertEquals("红楼梦",vm.ui.value.query)
  compose.onNodeWithTag("book-title-raw-1",useUnmergedTree=true).assertTextEquals("红楼梦")
  compose.runOnIdle{vm.loadMore()};await{!vm.ui.value.findLoading&&vm.ui.value.books.size==2};assertEquals("紅樓夢" to "游標:raw+2",calls.last())
  compose.onNodeWithTag("find-book-raw-1").performClick();compose.onNodeWithText("红楼梦",useUnmergedTree=true).assertExists();compose.onNodeWithText("繁体简介").assertExists();compose.onNodeWithText("←  返回").performClick()
  compose.onNodeWithTag("add-shelf-raw-1").performClick();compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("book-title-raw-1",useUnmergedTree=true).assertTextEquals("红楼梦")
  compose.onNodeWithTag("shelf-book-raw-1").performClick();await{!vm.ui.value.loading&&vm.ui.value.chapter!=null};assertEquals(rawAnchor,vm.ui.value.offset);assertEquals(rawChapter.text,vm.ui.value.chapter!!.text)
  compose.runOnIdle{assertTrue(reader()!!.currentLayout!!.layout.text.toString().contains("这是自写正文"));assertEquals(rawAnchor,reader()!!.currentOffset);vm.backToShelf()}
  compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("find-search-input").performTextReplacement("网络");compose.onNodeWithTag("find-search-input").performImeAction()
  await{!vm.ui.value.findLoading&&vm.ui.value.findError!=null};assertEquals(1,calls.count{it.first=="网络"});assertEquals(0,calls.count{it.first=="網絡"})
 }
}
