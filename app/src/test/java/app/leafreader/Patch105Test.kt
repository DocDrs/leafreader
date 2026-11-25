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
class Patch105Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun gate(w:WireTransport)=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)})
 private fun book(id:String,owner:String)=Book(id,"合成书$id","自写作者","","",0,0,"EPUB",owner)
 private fun cacheRoot(owner:String)=File(app.noBackupFilesDir,"offline-v1/"+sha(owner.toByteArray()))
 private fun seed(cache:OfflineStore,w:OfflineFixtures,id:String="public:1") {
  val m=w.manifest().put("bookId",id);val epoch=cache.generation(id);cache.saveManifest(id,m,epoch)
  w.bytes().forEach{(rid,bytes)->cache.put(id,w.version,rid,bytes,epoch)};assertTrue(cache.complete(id,m,epoch))
 }
 @Test fun summaryAndHiddenInventoryReadSizesWithoutIntegritySweepOrOtherAccountNames()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val store=ReadingStore(app,owner)
   store.rememberServiceShelf(listOf(book("public:1",owner),book("public:2",owner)));store.hideFromShelf(setOf("public:2"));val cache=runtime.cache(owner)
   seed(cache,w);seed(cache,w,"public:2");seed(cache,w,"public:3") // Reading cache was never explicitly removed.
   val other=BookHubSource.ownerId(w.client("other"));ReadingStore(app,other).addToShelf(book("public:2",other));ReadingStore(app,other).hideFromShelf(setOf("public:2"));seed(runtime.cache(other),w,"public:2")
   val dir=File(cacheRoot(owner),sha("public:2".toByteArray())+"/"+sha(w.version.toByteArray()));val content=File(dir,sha("a".toByteArray())+".bin");content.writeBytes(ByteArray(content.length().toInt()))
   val index=File(dir,"index.json");val before=index.readBytes();val calls=w.calls.size
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading}
   val usage=vm.offlineUsage();assertTrue(usage.available);assertTrue(usage.books.isEmpty());assertTrue(usage.bytesByBook.isEmpty());assertEquals(GlobalOfflineUsage.bytes(GlobalOfflineUsage.roots(app)),usage.totalBytes)
   val hidden=vm.hiddenCopiesSnapshot();assertEquals(listOf(owner+"|public:2"),hidden.books.map{it.key});assertArrayEquals(before,index.readBytes());assertEquals(calls,w.calls.size)
   assertFalse(cache.status("public:2",verify=true).complete) // The deliberately damaged bytes really fail integrity.
   w.accessStatus=401;try{vm.chapterList(book("public:1",owner),true);fail()}catch(_:SourceProblem){}
   assertTrue(vm.offlineUsage().available);assertTrue(vm.offlineUsage().books.isEmpty());assertFalse(vm.hiddenCopiesSnapshot().available);assertTrue(vm.hiddenCopiesSnapshot().books.isEmpty())
   vm.signOut();assertFalse(vm.hiddenCopiesSnapshot().available);assertTrue(vm.offlineUsage().available)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun hiddenClearIsScopedToEnabledOwnerAndRevisionAndRetainsPositionsAndVisibility()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val a=w.client("a");val ownerA=BookHubSource.ownerId(a);val ownerB=BookHubSource.ownerId(w.client("b","https://other-cleaner.fixture.invalid"));val vault=MemorySessionVault().apply{value=a.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false)
   for(owner in listOf(ownerA,ownerB)){val st=ReadingStore(app,owner);st.addToShelf(book("public:1",owner));st.save("public:1","kept-$owner",37);st.hideFromShelf(setOf("public:1"));seed(runtime.cache(owner),w)}
   val wire=WireTransport{uri,method,headers,body,max->if(uri.path.endsWith("/auth/login"))WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.session("b")).toByteArray()) else w.execute(uri,method,headers,body,max)}
   val vm=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading};val oldRevision=vm.ui.value.connectionRevision
   assertEquals(listOf(ownerA),vm.hiddenCopiesSnapshot().books.map{it.sourceId});vm.clearHiddenDownload(book("public:1",ownerB),oldRevision);assertTrue(runtime.cache(ownerB).status("public:1").complete)
   vm.connect("https://other-cleaner.fixture.invalid","b","fake");vm.ui.first{!it.shelfLoading};vm.clearHiddenDownload(book("public:1",ownerA),oldRevision)
   assertTrue(runtime.cache(ownerA).status("public:1").complete);val position=ReadingStore(app,ownerB).position("public:1")
   vm.clearHiddenDownload(book("public:1",ownerB),vm.ui.value.connectionRevision)
   withContext(Dispatchers.IO){withTimeout(5000){while(runtime.cache(ownerB).manifest("public:1")!=null)delay(10)}}
   assertTrue(runtime.cache(ownerA).status("public:1").complete);assertNull(ReadingStore(app,ownerB).position("public:1"));assertTrue("public:1" in ReadingStore(app,ownerB).hiddenShelfIds())
   val cold=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);cold.ui.first{!it.shelfLoading};assertTrue(cold.hiddenCopiesSnapshot().books.isEmpty());assertNull(ReadingStore(app,ownerB).position("public:1"))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun failedPartialRetryKeepsSavedResourcesAndProgressUntilDownloadCompletes()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val st=ReadingStore(app,owner);val b=book("public:1",owner);st.addToShelf(b);st.save(b.id,"kept",19)
   val cache=runtime.cache(owner);val m=w.manifest();val epoch=cache.generation(b.id);cache.saveManifest(b.id,m,epoch);val saved=w.bytes().getValue("a");cache.put(b.id,w.version,"a",saved,epoch)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading};runtime.enqueue(owner,b);val old=runtime.request(runtime.key(owner,b.id))!!;runtime.update(old,"FAILED","合成断网失败")
   val position=st.position(b.id);vm.download(b);val retry=runtime.request(old.key)!!;assertEquals("QUEUED",retry.state);assertFalse(retry.allowMetered);assertNotEquals(old.nonce,retry.nonce);assertArrayEquals(saved,cache.resource(b.id,w.version,"a"));assertEquals(position,st.position(b.id))
   BookDownloader(c,cache,w.network).download(b.id,false);assertTrue(cache.status(b.id,true).complete);assertEquals(position,st.position(b.id));assertTrue(vm.isOnShelf(b))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun settingsTotalCountsMalformedAndPartialFilesAndNeverFollowsSymlinks() {
  val w=OfflineFixtures();val owner=BookHubSource.ownerId(w.client());val runtime=OfflineDownloads(app,w.network,MemorySessionVault(),false);val cache=runtime.cache(owner);seed(cache,w)
  val root=cacheRoot(owner);File(root,"opaque.part").writeBytes(ByteArray(111));val outside=File(app.filesDir,"outside-not-counted").apply{parentFile!!.mkdirs();writeBytes(ByteArray(9999))}
  java.nio.file.Files.createSymbolicLink(File(root,"link").toPath(),outside.toPath())
  val expected=root.walkTopDown().filter{it.isFile && !java.nio.file.Files.isSymbolicLink(it.toPath())}.sumOf{it.length()}
  assertEquals(expected,cache.totalDiskBytes())
 }
}
