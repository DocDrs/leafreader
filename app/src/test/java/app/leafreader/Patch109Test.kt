package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Patch109Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun book(owner:String,id:String="public:1")=Book(id,"自写缓存验证","自写作者","","",4,0,"EPUB",owner)
 private fun gate(w:WireTransport)=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)})
 private fun seed(runtime:OfflineDownloads,w:OfflineFixtures,owner:String,id:String="public:1") {
  val cache=runtime.cache(owner);val m=w.manifest().put("bookId",id);val e=cache.generation(id);cache.saveManifest(id,m,e)
  w.bytes().forEach{(rid,raw)->cache.put(id,w.version,rid,raw,e)};assertTrue(cache.complete(id,m,e))
 }
 private suspend fun cleared(vm:ReaderViewModel,b:Book) {withContext(Dispatchers.IO){withTimeout(10000){vm.ui.first{b.key !in it.clearingBooks && !vm.cleanupPending(b)}}}}
 @Test fun clearAndRemoveDeleteOnlyExactBookProgressAndCopiesAndReaddStartsFresh()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val a=w.client();val owner=BookHubSource.ownerId(a);val other=BookHubSource.ownerId(w.client("other","https://other.fixture.invalid"))
   val vault=MemorySessionVault().apply{value=a.snapshot()};val rt=OfflineDownloads(app,w.network,vault,false);val b=book(owner);val ten=book(owner,"public:10");val foreign=book(other)
   for(v in listOf(b,ten,foreign)){val st=ReadingStore(app,v.sourceId);st.addToShelf(v);st.save(v.id,"old",31);st.preserveRecovery(v.id);st.markEntered(v,500);seed(rt,w,v.sourceId,v.id)}
   val st=ReadingStore(app,owner);val foreignPos=ReadingStore(app,other).position(b.id);val tenPos=st.position(ten.id);val settings=ReaderSettings(fontSp=27f,palette=4);st.saveSettings(settings)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading};val session=vault.value
   val epoch=rt.cache(owner).generation(b.id);rt.enqueue(owner,b);val old=rt.request(rt.key(owner,b.id))!!
   vm.clearDownload(b);assertNull(st.position(b.id));assertNull(st.preservedPosition(b.id));cleared(vm,b)
   assertTrue(vm.isOnShelf(b));assertNull(rt.cache(owner).manifest(b.id));assertFalse(rt.cache(owner).put(b.id,w.version,"a","late".toByteArray(),epoch));rt.update(old,"COMPLETE","late");assertEquals("CANCELLED",rt.request(old.key)!!.state)
   assertEquals(tenPos,st.position(ten.id));assertEquals(foreignPos,ReadingStore(app,other).position(b.id));assertTrue(rt.cache(other).status(b.id,true).complete);assertTrue(rt.cache(owner).status(ten.id,true).complete);assertEquals(settings,st.settings());assertEquals(session,vault.value)
   st.save(b.id,"new",9);seed(rt,w,owner);vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);cleared(vm,b)
   assertFalse(vm.isOnShelf(b));assertNull(st.position(b.id));assertNull(rt.cache(owner).manifest(b.id));vm.syncShelfFromBookHub();vm.ui.first{!it.syncingShelf};assertFalse(vm.isOnShelf(b));assertNull(st.position(b.id))
   val cold=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);cold.ui.first{!it.shelfLoading};assertFalse(cold.isOnShelf(b));assertTrue(cold.addToShelf(b));assertNull(cold.positionOf(b));assertNull(st.preservedPosition(b.id));assertNotNull(rt.request(old.key))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun interruptedExplicitCleanupResumesAfterColdStartButOldHiddenCopiesAreNeverSwept()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val rt=OfflineDownloads(app,w.network,vault,false);val st=ReadingStore(app,owner);val b=book(owner);val legacy=book(owner,"public:2")
   for(v in listOf(b,legacy)){st.addToShelf(v);st.save(v.id,"old",13);seed(rt,w,owner,v.id)}
   st.hideFromShelf(setOf(legacy.id));rt.activate(c);rt.enqueue(owner,b);val queued=rt.request(rt.key(owner,b.id))!!;assertTrue(st.requestCleanup(b.id,true));assertFalse(rt.valid(queued,c));assertFalse(rt.enqueue(owner,b))
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);cleared(vm,b)
   assertNull(rt.cache(owner).manifest(b.id));assertNull(st.position(b.id));assertFalse(vm.isOnShelf(b));assertTrue(rt.cache(owner).status(legacy.id,true).complete);assertEquals(13,st.position(legacy.id)!!.offset)
   assertTrue(rt.enqueue(owner,b));assertNotEquals(queued.nonce,rt.request(queued.key)!!.nonce)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun actualChapterChecksRequireBoundaryAndInlineImageResourcesAndNeverSendHttpOrWriteProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val rt=OfflineDownloads(app,w.network,vault,false);val cache=rt.cache(owner);val b=book(owner);val st=ReadingStore(app,owner);st.addToShelf(b);st.save(b.id,"kept",17);val pos=st.position(b.id)
   val m=w.manifest().put("toc",JSONArray());val e=cache.generation(b.id);cache.saveManifest(b.id,m,e);for(id in listOf("cover","a"))cache.put(b.id,w.version,id,w.bytes().getValue(id),e)
   w.network.online=false;val local=BookHubSource(c,cache,w.network);val refs=local.chapters(b.id);val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading}
   assertEquals("可离线读",vm.chapterOfflineStatus(b,refs[1].id));assertEquals("未缓存完整",vm.chapterOfflineStatus(b,refs[0].id));assertEquals("未缓存完整",vm.chapterOfflineStatus(b,refs[2].id));assertTrue(w.calls.isEmpty());assertEquals(pos,st.position(b.id))
   cache.put(b.id,w.version,"pic",w.bytes().getValue("pic"),e);assertEquals("可离线读",vm.chapterOfflineStatus(b,refs[0].id));assertFalse(vm.inspectDownload(b).complete)
   // A navigation chapter needs the next document to establish its end, even when start text exists.
   cache.clear(b.id);val next=cache.generation(b.id);cache.saveManifest(b.id,w.manifest(),next);cache.put(b.id,w.version,"a",w.bytes().getValue("a"),next)
   val nav=BookHubSource(c,cache,w.network).chapters(b.id);assertEquals("未缓存完整",vm.chapterOfflineStatus(b,nav[1].id))
   cache.put(b.id,w.version,"b",w.bytes().getValue("b"),next);assertEquals("可离线读",vm.chapterOfflineStatus(b,nav[1].id));assertEquals(pos,st.position(b.id));assertTrue(w.calls.isEmpty())
   vm.signOut();assertEquals("需原账号授权",vm.chapterOfflineStatus(b,nav[1].id));assertTrue(w.calls.isEmpty())
  }finally{Dispatchers.resetMain()}
 }
 @Test fun delayedWholeDownloadCannotRestoreRemovedBookAndNewExplicitDownloadStillWorks()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()};val rt=OfflineDownloads(app,w.network,vault,false);val b=book(owner);ReadingStore(app,owner).addToShelf(b)
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading};rt.activate(c);rt.enqueue(owner,b);val request=rt.request(rt.key(owner,b.id))!!
   val start=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.onResponse={path->if(path.endsWith("/content")){start.complete(Unit);release.await()}}
   val task=async{runCatching{BookDownloader(c,rt.cache(owner),w.network).download(b.id,false,{rt.valid(request,c)})}}
   start.await();vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);cleared(vm,b);release.complete(Unit);assertTrue(task.await().exceptionOrNull() is DownloadStopped);rt.update(request,"COMPLETE","late")
   assertNull(rt.cache(owner).manifest(b.id));assertNull(vm.positionOf(b));assertFalse(vm.isOnShelf(b))
   val cold=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=rt,linkBookHubShelf=false);cold.ui.first{!it.shelfLoading};assertFalse(cold.isOnShelf(b));assertNull(rt.cache(owner).manifest(b.id));w.onResponse=null
   rt.activate(c);assertTrue(rt.enqueue(owner,b));val retry=rt.request(request.key)!!;BookDownloader(c,rt.cache(owner),w.network).download(b.id,false,{rt.valid(retry,c)});assertTrue(rt.cache(owner).status(b.id,true).complete)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun shelfUpdatePullsAllMetadataPagesRecordsResultAndCannotRestoreClearedProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val origin="https://sync.fixture.invalid";val session=SavedSession(origin,"sync-account","d","access","refresh");val c=BookHubClient(ServiceOrigin(origin)).apply{restore(session)};val owner=BookHubSource.ownerId(c);val st=ReadingStore(app,owner);val b=book(owner);st.addToShelf(b);st.save(b.id,"old",17);st.requestCleanup(b.id);st.finishCleanup(b.id);st.hideFromShelf(setOf(b.id))
   var fail=false;var expired=false;val calls=mutableListOf<String>()
   val wire=WireTransport{uri,method,_,_,_->calls+="$method ${uri.path}";if(expired)WireResponse(401,"{\"code\":\"SESSION_REVOKED\"}".toByteArray()) else if(fail)throw SourceProblem(FailureKind.NETWORK,"synthetic offline") else {
    val data=if(uri.rawQuery.contains("cursor="))BookHubFixtures.page("public:2") else BookHubFixtures.page("public:1").put("hasMore",true).put("nextCursor","second")
    WireResponse(200,BookHubFixtures.envelope(data).toByteArray())}}
   val vault=MemorySessionVault().apply{value=session};val vm=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault,linkBookHubShelf=false);vm.ui.first{!it.shelfLoading};assertEquals("NONE",vm.shelfUpdateRecord().state)
   vm.syncShelfFromBookHub();vm.ui.first{!it.syncingShelf};assertEquals("SUCCESS",vm.shelfUpdateRecord().state);assertEquals(2,vm.shelfUpdateRecord().count);assertNull(st.position(b.id));assertFalse(vm.isOnShelf(b));assertTrue(calls.all{it=="GET /api/v1/me/bookshelf"})
   fail=true;vm.syncShelfFromBookHub();vm.ui.first{!it.syncingShelf};assertEquals("FAILED",vm.shelfUpdateRecord().state);assertEquals("NETWORK",vm.shelfUpdateRecord().failure);assertTrue(vm.shelfUpdateRecord().successAt>0);assertNull(st.position(b.id))
   fail=false;expired=true;vm.syncShelfFromBookHub();vm.ui.first{!it.syncingShelf};assertTrue(vm.bookHubConnectionLabel().contains("已失效"));assertNull(vault.value)
   val cold=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault,linkBookHubShelf=false);cold.ui.first{!it.shelfLoading};assertTrue(cold.bookHubConnectionLabel().contains("已失效"));assertNull(st.position(b.id));assertFalse(cold.isOnShelf(b));cold.signOut();assertEquals("已退出 BookHub",cold.bookHubConnectionLabel())
  }finally{Dispatchers.resetMain()}
 }
 @Test fun olderCleanupTokenCannotEraseANewDownloadOrFinishANewerIntent() {
  val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val rt=OfflineDownloads(app,w.network,MemorySessionVault(),false);val st=ReadingStore(app,owner);val b=book(owner)
  st.addToShelf(b);st.requestCleanup(b.id);val old=st.cleanupToken(b.id)!!
  rt.eraseAndCancel(owner,b.id,old);assertTrue(rt.finishErase(owner,b.id,old));seed(rt,w,owner)
  rt.eraseAndCancel(owner,b.id,old);assertTrue(rt.cache(owner).status(b.id,true).complete)
  st.requestCleanup(b.id);val next=st.cleanupToken(b.id)!!;assertNotEquals(old,next);assertFalse(rt.finishErase(owner,b.id,old));assertTrue(st.cleanupPending(b.id))
  rt.eraseAndCancel(owner,b.id,next);assertTrue(rt.finishErase(owner,b.id,next));assertNull(rt.cache(owner).manifest(b.id))
 }

 @Test fun cleanupDeletesBookSymlinksWithoutTouchingTheirTargets() {
  val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val rt=OfflineDownloads(app,w.network,MemorySessionVault(),false);val b=book(owner);val st=ReadingStore(app,owner);seed(rt,w,owner)
  val outside=java.io.File(app.filesDir,"outside-cleanup").apply{mkdirs()};val keep=java.io.File(outside,"keep.txt").apply{writeText("自写且不属于目标书的文件")}
  val dir=java.io.File(app.noBackupFilesDir,"offline-v1/"+sha(owner.toByteArray())+"/"+sha(b.id.toByteArray()))
  java.nio.file.Files.createSymbolicLink(java.io.File(dir,"external-link").toPath(),outside.toPath())
  st.requestCleanup(b.id);val token=st.cleanupToken(b.id)!!;rt.eraseAndCancel(owner,b.id,token);assertTrue(rt.finishErase(owner,b.id,token));assertTrue(keep.isFile);assertNull(rt.cache(owner).manifest(b.id));assertFalse(java.io.File(dir,"external-link").exists())
 }

}
