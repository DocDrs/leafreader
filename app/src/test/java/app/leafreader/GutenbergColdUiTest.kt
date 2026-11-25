package app.leafreader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.offline.AlwaysOnline
import kotlinx.coroutines.*
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
class GutenbergColdUiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun firstFindSearchAndBrowseFinishWhileOfficialCatalogResponseNeverArrives() {
  lateinit var vm:ReaderViewModel;lateinit var source:GutenbergSource
  val entered=CompletableDeferred<Unit>()
  compose.runOnUiThread{val app=compose.activity.application;source=GutenbergSource(app,WireTransport{_,_,_,_,_->entered.complete(Unit);awaitCancellation()},AlwaysOnline);vm=ReaderViewModel(app,sessionVault=MemorySessionVault(),independentSources=listOf(source),linkBookHubShelf=false);compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}}
  try {
   compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.shelfLoading}
   compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("source-gutenberg").performClick()
   compose.onNodeWithTag("find-search-input").performTextInput("论语");compose.onNodeWithTag("find-search-input").performImeAction()
   compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.any{it.id=="pg:23839"}}
   compose.onNodeWithTag("find-book-pg:23839").assertIsDisplayed();assertTrue(entered.isCompleted);assertNull(vm.ui.value.findError)
   compose.onNodeWithTag("book-title-pg:23839",useUnmergedTree=true).assertTextEquals("论语")
   assertEquals("論語",vm.ui.value.books.single{it.id=="pg:23839"}.title)
   compose.onNodeWithTag("find-search-input").performTextReplacement("红楼梦");compose.onNodeWithTag("find-search-input").performImeAction();compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.any{it.id=="pg:24264"}}
   val simplifiedKeys=vm.ui.value.books.map{it.key}
   compose.onNodeWithTag("book-title-pg:24264",useUnmergedTree=true).assertTextEquals("红楼梦")
   assertEquals("紅樓夢",vm.ui.value.books.single{it.id=="pg:24264"}.title)
   compose.onNodeWithTag("book-title-pg:25202",useUnmergedTree=true).assertTextEquals("补红楼梦")
   assertEquals("補紅樓夢",vm.ui.value.books.single{it.id=="pg:25202"}.title)
   compose.runOnIdle{
    val view=compose.activity.window.decorView
    val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
    view.draw(android.graphics.Canvas(bitmap))
    java.io.File("build/gutenberg117-previews").apply{mkdirs()}.resolve("simplified-search.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
    bitmap.recycle()
   }
   compose.onNodeWithTag("find-search-input").performTextReplacement("紅樓夢");compose.onNodeWithTag("find-search-input").performImeAction();compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.query=="紅樓夢"&&vm.ui.value.books.any{it.id=="pg:24264"}}
   assertEquals(simplifiedKeys,vm.ui.value.books.map{it.key})
   compose.onNodeWithTag("book-title-pg:24264",useUnmergedTree=true).assertTextEquals("红楼梦")
   // Details also display simplified; the original Book model is unchanged.
   compose.onNodeWithTag("find-book-pg:24264").performClick()
   compose.onNodeWithText("红楼梦",useUnmergedTree=true).assertExists()
   compose.onNodeWithText("←  返回").performClick()
   compose.onNodeWithTag("find-search-clear").performClick();compose.onNodeWithTag("browse-gutenberg").performClick();compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.size==20}
   compose.onNodeWithTag("find-empty").assertDoesNotExist();assertTrue(vm.ui.value.hasMore)
  }finally{source.close()}
 }
 @Test fun sameTitleAndAuthorRemainSeparateAndAddingOneDoesNotHideTheOther() {
  lateinit var vm:ReaderViewModel;lateinit var source:GutenbergSource;var calls=0
  val offline=object:app.leafreader.offline.ConnectionState{override val online=false;override val wifi=false}
  compose.runOnUiThread{
   val app=compose.activity.application
   source=GutenbergSource(app,WireTransport{_,_,_,_,_->calls++;error("No HTTP permitted")},offline)
   vm=ReaderViewModel(app,sessionVault=MemorySessionVault(),independentSources=listOf(source),linkBookHubShelf=false)
   compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
  }
  try {
   compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.shelfLoading}
   compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("source-gutenberg").performClick()
   compose.onNodeWithTag("find-search-input").performTextInput("花月痕");compose.onNodeWithTag("find-search-input").performImeAction()
   compose.waitUntil(5000){compose.waitForIdle();!vm.ui.value.findLoading&&vm.ui.value.books.size==2}
   val first=vm.ui.value.books.single{it.id=="pg:25187"};val second=vm.ui.value.books.single{it.id=="pg:25219"}
   assertEquals(first.title,second.title);assertEquals(first.author,second.author);assertNotEquals(first.key,second.key)
   compose.onNodeWithTag("book-title-pg:25187",useUnmergedTree=true).assertTextEquals("花月痕")
   compose.onNodeWithTag("book-title-pg:25219",useUnmergedTree=true).assertTextEquals("花月痕")
   val store=ReadingStore(compose.activity.application,GutenbergSource.SOURCE)
   store.save(first.id,"synthetic-spine-a",301);store.save(second.id,"synthetic-spine-b",902)
   compose.onNodeWithTag("add-shelf-pg:25187").performClick()
   compose.waitUntil(5000){compose.waitForIdle();vm.isOnShelf(first)&&vm.ui.value.books.none{it.id==first.id}}
   assertEquals(listOf(second.key),vm.ui.value.books.map{it.key})
   compose.onNodeWithTag("find-book-pg:25187").assertDoesNotExist()
   compose.onNodeWithTag("find-book-pg:25219").assertIsDisplayed()
   assertEquals(301,vm.positionOf(first)!!.offset);assertEquals("synthetic-spine-a",vm.positionOf(first)!!.chapterId)
   assertEquals(902,vm.positionOf(second)!!.offset);assertEquals("synthetic-spine-b",vm.positionOf(second)!!.chapterId)
   val cold=ReadingStore(compose.activity.application,GutenbergSource.SOURCE)
   assertEquals(vm.positionOf(first),cold.position(first.id));assertEquals(vm.positionOf(second),cold.position(second.id))
   assertEquals(0,calls)
  }finally{source.close()}
 }

}
