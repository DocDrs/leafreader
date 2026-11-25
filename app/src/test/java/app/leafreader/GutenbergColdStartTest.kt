package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.ConnectionState
import app.leafreader.offline.AlwaysOnline
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GutenbergColdStartTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private val offline=object:ConnectionState{override val online=false;override val wifi=false}
 @Test fun freshInstallOfflineSearchAndBrowseUseOnlyBibliographicSnapshot()=runBlocking {
  var calls=0;val source=GutenbergSource(app,WireTransport{_,_,_,_,_->calls++;error("Offline HTTP forbidden")},offline)
  assertEquals(listOf("pg:23839"),withTimeout(2000){source.browse("论语")}.items.map{it.id})
  assertEquals(source.browse("论语").items,source.browse("論語").items)
  assertEquals(source.browse("红楼梦").items,source.browse("紅樓夢").items)
  assertTrue(source.browse("红楼梦").items.any{it.id=="pg:24264"})
  for(q in listOf("孔子","Confucius"))assertTrue(source.browse(q).items.any{it.id=="pg:23839"})
  for(q in listOf("曹雪芹","Cao Xueqin"))assertTrue(source.browse(q).items.any{it.id=="pg:24264"})
  val all=mutableSetOf<String>();var cursor:String?=null
  do {val page=source.browse("",cursor);assertNotNull(page.catalogNote);all+=page.items.map{it.id};cursor=page.nextCursor}while(cursor!=null)
  assertEquals(443,all.size);assertEquals(0,calls)
  assertTrue(source.browse("确实不存在的自写书名").items.isEmpty());source.close()
 }
 @Test fun indefinitelySlowUpdateNeverBlocksSearchAndOnlyOneAttemptIsMade()=runBlocking {
  val entered=CompletableDeferred<Unit>();var calls=0
  val source=GutenbergSource(app,WireTransport{_,_,_,_,_->calls++;entered.complete(Unit);awaitCancellation()},AlwaysOnline)
  try {
   val page=withTimeout(2000){source.browse("论语")};assertEquals("pg:23839",page.items.single().id)
   withTimeout(2000){entered.await()};repeat(3){assertTrue(withTimeout(2000){source.browse("红楼梦")}.items.isNotEmpty())}
   assertEquals(1,calls)
  }finally{source.close()}
 }
 @Test fun failedUpdateAndDamagedDiskCatalogKeepUsableSnapshotAcrossColdStart()=runBlocking {
  File(app.noBackupFilesDir,"gutenberg-public-v1").apply{mkdirs()}.resolve("catalog.json").writeText("truncated")
  val failed=CompletableDeferred<Unit>();var calls=0
  val wire=WireTransport{_,_,_,_,_->calls++;failed.complete(Unit);throw java.net.SocketTimeoutException("Synthetic timeout")}
  val source=GutenbergSource(app,wire,AlwaysOnline)
  try{assertEquals("pg:23839",source.browse("论语").items.single().id);withTimeout(2000){failed.await()}}finally{source.close()}
  val cold=GutenbergSource(app,wire,AlwaysOnline)
  try{assertTrue(cold.browse("红楼梦").items.isNotEmpty());assertEquals(1,calls)}finally{cold.close()}
 }
}
