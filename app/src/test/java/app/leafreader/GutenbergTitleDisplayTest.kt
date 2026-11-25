package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.text.ChineseDisplay
import app.leafreader.offline.ConnectionState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GutenbergTitleDisplayTest {
 private fun book(title:String,source:String=GutenbergSource.SOURCE)=Book("pg:24264",title,"Cao, Xueqin","原始資料","古登堡计划",0,0xFF78664F,"EPUB",source)
 @Test fun catalogTitlesSimplifyWithoutChangingTypographyOrOriginalMetadata() {
  val cases=mapOf("紅樓夢" to "红楼梦","論語" to "论语","補紅樓夢" to "补红楼梦",
   "夢溪筆談, Volume 01-06" to "梦溪笔谈, Volume 01-06","顔氏家訓 — Volume 01 and 02" to "颜氏家训 — Volume 01 and 02",
   "阿Ｑ正傳" to "阿Ｑ正传","粉妝樓1-10回" to "粉妆楼1-10回","鏡花緣" to "镜花缘","聊齋志異" to "聊斋志异",
   "水滸傳" to "水浒传","三國志演義" to "三国志演义","滬語開路 = Conversational Exercises in the Shanghai Dialect" to "沪语开路 = Conversational Exercises in the Shanghai Dialect",
   "Peach Blossom Shangri-la: Tao Hua Yuan Ji" to "Peach Blossom Shangri-la: Tao Hua Yuan Ji")
  cases.forEach{(original,expected)->val b=book(original);assertEquals(expected,ChineseDisplay.text(ApplicationProvider.getApplicationContext(),b.title));assertEquals(original,b.title);assertEquals("gutenberg-public-v1|pg:24264",b.key);assertEquals(expected,ChineseDisplay.text(ApplicationProvider.getApplicationContext(),expected))}
 }
 @Test fun allSourcesDisplaySimplifiedWhileRetainingOriginalTitlesEvenWithTheSameBareBookId() {
  for(source in listOf("","bookhub:synthetic-account","opds-public-synthetic","legado-basic-synthetic")) {
   val b=book("紅樓夢",source);assertEquals("红楼梦",ChineseDisplay.text(ApplicationProvider.getApplicationContext(),b.title));assertEquals("紅樓夢",b.title)
  }
 }
 @Test fun oldShelfProgressSettingsAndDownloadedBytesRemainUnchangedAfterColdSearch()=runBlocking {
  val app=ApplicationProvider.getApplicationContext<Application>();val old=book("紅樓夢")
  val store=ReadingStore(app,GutenbergSource.SOURCE)
  assertTrue(store.addToShelf(old));store.markEntered(old,123456)
  store.save(old.id,"existing-spine-chapter-5",387,"existing-graphic")
  val settings=ReaderSettings(fontSp=27f,lineSpacing=1.73f,palette=4);store.saveSettings(settings)
  val before=store.position(old.id)
  val directory=File(app.noBackupFilesDir,"gutenberg-public-v1/24264").apply{mkdirs()}
  // Synthetic opaque existing download bytes: this display-only path must never inspect/rewrite them.
  val file=File(directory,"retained-download.bin");val bytes="自写繁體測試：原始內文，Ａ + B。".toByteArray();file.writeBytes(bytes)
  var calls=0;val offline=object:ConnectionState{override val online=false;override val wifi=false}
  val source=GutenbergSource(app,WireTransport{_,_,_,_,_->calls++;error("Unexpected network")},offline)
  try {
   val first=source.browse("红楼梦").items;val second=source.browse("紅樓夢").items
   assertEquals(first,second);assertEquals("红楼梦",ChineseDisplay.text(app,first.single{it.id==old.id}.title))
   val cold=ReadingStore(app,GutenbergSource.SOURCE)
   assertEquals(before,cold.position(old.id));assertEquals(settings,cold.settings());assertEquals(old,cold.localShelf().single{it.id==old.id})
   assertArrayEquals(bytes,file.readBytes());assertEquals(0,calls)
  } finally {source.close()}
 }
}
