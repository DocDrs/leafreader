package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Patch103Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun gate(w:OfflineFixtures)=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)})
 private fun exactFiles(root:File):Long=if(root.exists())root.walkTopDown().filter{it.isFile}.sumOf{it.length()} else 0L
 @Test fun countsAllVersionsPartialReadCacheAndTemporaryFilesWithoutOtherAccounts()=runBlocking {
  val w=OfflineFixtures();val a=w.client("a");val b=w.client("b");val runtime=OfflineDownloads(app,w.network,MemorySessionVault(),false)
  val cache=runtime.cache(BookHubSource.ownerId(a));val m=w.manifest();var epoch=cache.generation("public:1");cache.saveManifest("public:1",m,epoch);cache.put("public:1",w.version,"a",ByteArray(123),epoch)
  w.version="usage-v2";cache.saveManifest("public:1",w.manifest(),epoch);cache.put("public:1",w.version,"b",ByteArray(321),epoch)
  val temp=cache.temporary("public:1","download");temp.writeBytes(ByteArray(77))
  val other=runtime.cache(BookHubSource.ownerId(b));other.saveManifest("public:1",w.manifest(),other.generation("public:1"));other.put("public:1",w.version,"a",ByteArray(5000),other.generation("public:1"))
  val root=File(app.noBackupFilesDir,"offline-v1/"+sha(BookHubSource.ownerId(a).toByteArray()))
  val usage=cache.diskUsage();assertEquals(exactFiles(root),usage.totalBytes);assertEquals(setOf("public:1"),usage.formats.keys)
  assertEquals(exactFiles(File(root,sha("public:1".toByteArray()))),usage.bytesByBook["public:1"])
  val before=usage.totalBytes;cache.finishTemporary(temp);assertEquals(before-77,cache.diskUsage().totalBytes)
  cache.clear("public:1");assertTrue(cache.diskUsage().totalBytes<before);assertFalse("public:1" in cache.diskUsage().payloadBooks)
 }
 @Test fun readOnlyUnlistedCacheGetsAnEntryAndUnknownFilesRemainInTotal()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val v=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,v,false)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=v,offlineRuntime=runtime);vm.ui.first{!it.shelfLoading}
   val cache=runtime.cache(BookHubSource.ownerId(c));val m=w.manifest().put("bookId","public:9");val epoch=cache.generation("public:9")
   cache.saveManifest("public:9",m,epoch);cache.put("public:9",w.version,"a",ByteArray(200),epoch)
   val root=File(app.noBackupFilesDir,"offline-v1/"+sha(BookHubSource.ownerId(c).toByteArray()));File(root,"unknown.bin").writeBytes(ByteArray(31))
   val snap=vm.managementSnapshot();assertTrue(snap.available);assertEquals(listOf("public:9"),snap.books.map{it.id});assertEquals("未入架的阅读缓存",snap.books.single().title)
   assertEquals(exactFiles(root),snap.totalBytes);assertEquals(snap.totalBytes,snap.bytesByBook.values.sum()+snap.otherBytes);assertTrue(snap.otherBytes>=31)
   assertFalse(vm.isOnShelf(snap.books.single()))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun hiddenBookCanClearBytesWithoutLosingProgressAndAnotherAccountStaysInvisible()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client("first");val v=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,v,false)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=v,offlineRuntime=runtime);val book=vm.ui.first{!it.shelfLoading}.shelfBooks.single()
   val cache=runtime.cache(book.sourceId);val m=w.manifest();val epoch=cache.generation(book.id);cache.saveManifest(book.id,m,epoch);w.bytes().forEach{(id,b)->cache.put(book.id,w.version,id,b,epoch)};cache.complete(book.id,m,epoch)
   vm.store.save(book.id,"kept",37);val pos=vm.store.position(book.id);vm.removeFromShelf(setOf(book.key),vm.ui.value.connectionRevision)
   val before=vm.managementSnapshot();assertEquals(book.key,before.books.single().key);assertTrue(before.bytesByBook[book.key]!!>0)
   vm.clearDownload(book);withContext(Dispatchers.IO){withTimeout(5000){while(cache.manifest(book.id)!=null)delay(10)}}
   val after=vm.managementSnapshot();assertTrue(after.totalBytes<before.totalBytes);assertTrue(after.books.isEmpty());assertEquals(pos,vm.store.position(book.id))
   vm.signOut();assertFalse(vm.managementSnapshot().available);assertTrue(vm.managementSnapshot().books.isEmpty())
   val c2=w.client("second");val v2=MemorySessionVault().apply{value=c2.snapshot()};val second=ReaderViewModel(app,connectionGate=gate(w),sessionVault=v2,offlineRuntime=OfflineDownloads(app,w.network,v2,false));second.ui.first{!it.shelfLoading}
   assertTrue(second.managementSnapshot().books.isEmpty());assertEquals(0L,second.managementSnapshot().totalBytes)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun logoutAndRevocationPersistConditionalExplanationAcrossColdStartWithoutNames()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val v=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,v,false)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=v,offlineRuntime=runtime);vm.ui.first{!it.shelfLoading}
   vm.signOut();assertEquals(OfflineRetention.NOTE,vm.ui.value.notice);assertTrue(vm.ui.value.retentionNote!!.contains("完整且版本匹配"));vm.dismissNotice()
   val cold=ReaderViewModel(app,sessionVault=v,offlineRuntime=OfflineDownloads(app,w.network,v,false));assertEquals(OfflineRetention.NOTE,cold.ui.value.retentionNote);assertFalse(cold.managementSnapshot().available)
   val c2=w.client();val activeVault=MemorySessionVault().apply{value=c2.snapshot()};val active=ReaderViewModel(app,connectionGate=gate(w),sessionVault=activeVault,offlineRuntime=OfflineDownloads(app,w.network,activeVault,false));active.ui.first{!it.shelfLoading};assertNull(active.ui.value.retentionNote)
   w.accessStatus=401;active.open(active.ui.value.shelfBooks.single());active.ui.first{!it.loading}
   assertTrue(active.ui.value.notice!!.contains("登录已失效"));assertTrue(active.ui.value.notice!!.contains(OfflineRetention.NOTE));assertFalse(active.ui.value.canReturnPage)
   val after=ReaderViewModel(app,sessionVault=activeVault);assertEquals(OfflineRetention.NOTE,after.ui.value.retentionNote);assertFalse(after.managementSnapshot().available)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun sizesAreLogicalBytesAndDoNotImplyCompleteCopies() {
  assertEquals("0 字节",OfflineUsageFormatting.bytes(0));assertEquals("123 字节",OfflineUsageFormatting.bytes(123));assertTrue(OfflineUsageFormatting.bytes(1024).contains("1.00 KiB"))
  assertEquals("未下载",OfflineStatus().label);assertEquals("部分离线",OfflineStatus(1,5,false).label)
 }
}
