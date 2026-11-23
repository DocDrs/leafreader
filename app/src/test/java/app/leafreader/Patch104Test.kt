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
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch104Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun owner(s:String)="bookhub-v1-"+digest(s).take(32)
 private fun book(id:String,source:String)=Book(id,"相同书名","自写作者","","",1,0,"EPUB",source)
 private fun gate(w:WireTransport)=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,w)})
 @Test fun migrationReadsHistoricalNamespacesWithoutRewritingThemAndKeepsHiddenAndAnchors()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val a=ReadingStore(app,owner("a"));val b=ReadingStore(app,owner("b"))
   a.addToShelf(book("public:1",a.sourceId));b.addToShelf(book("public:1",b.sourceId));a.addToShelf(book("public:2",a.sourceId));a.hideFromShelf(setOf("public:2"));b.addToShelf(book("public:2",b.sourceId))
   a.save("public:1","old-a",13,"image-a");b.save("public:1","old-b",27);a.markEntered(book("public:1",a.sourceId),200);b.markEntered(book("public:1",b.sourceId),100)
   val dir=File(app.applicationInfo.dataDir,"shared_prefs");val before=listOf(a,b).associate{it.sourceId to File(dir,"reader-${it.sourceId}.xml").readBytes()}
   val vm=ReaderViewModel(app,sessionVault=MemorySessionVault());vm.ui.first{!it.shelfLoading}
   assertEquals(listOf(a.sourceId+"|public:1",b.sourceId+"|public:1",b.sourceId+"|public:2"),vm.ui.value.shelfBooks.map{it.key})
   assertEquals("image-a",vm.positionOf(vm.ui.value.shelfBooks.first())!!.graphicId)
   for((id,bytes) in before)assertArrayEquals(bytes,File(dir,"reader-$id.xml").readBytes())
   vm.signOut();vm.dismissNotice();assertEquals(3,vm.ui.value.shelfBooks.size)
   val hide=vm.ui.value.shelfBooks.last();vm.removeFromShelf(setOf(hide.key),vm.ui.value.connectionRevision)
   val cold=ReaderViewModel(app,sessionVault=MemorySessionVault());cold.ui.first{!it.shelfLoading};assertEquals(2,cold.ui.value.shelfBooks.size);assertFalse(cold.ui.value.shelfBooks.any{it.key==hide.key})
   assertEquals(13,a.position("public:1")!!.offset);assertEquals(27,b.position("public:1")!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun accountSwitchRetainsBothBooksFiltersSearchByOwnerAndNeverRequestsOldBookFromNewSource()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val calls=mutableListOf<String>()
   val wire=WireTransport{uri,_,_,body,_->calls+=uri.path;val data=when {
    uri.path.endsWith("/auth/login")->BookHubFixtures.session(org.json.JSONObject(String(body!!)).getString("username"))
    uri.path.endsWith("/catalog/search")->BookHubFixtures.page("public:2")
    else->BookHubFixtures.page()
   };WireResponse(200,BookHubFixtures.envelope(data).toByteArray())}
   val vm=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=MemorySessionVault())
   vm.connect("https://global.fixture.invalid","a","fake");vm.ui.first{!it.shelfLoading};val a=vm.ui.value.shelfBooks.single();vm.store.save(a.id,"a-chapter",17)
   vm.addToShelf(a.copy(id="public:2"));vm.connect("https://global.fixture.invalid","b","fake");vm.ui.first{!it.shelfLoading}
   val b=vm.ui.value.shelfBooks.first{it.sourceId==vm.store.sourceId};assertEquals(3,vm.ui.value.shelfBooks.size);assertNull(vm.positionOf(b))
   val count=calls.size;vm.open(a);vm.ui.first{!it.loading};assertTrue(vm.ui.value.error!!.contains("BH_AUTH"));assertEquals(count,calls.size);assertEquals(17,vm.positionOf(a)!!.offset)
   vm.backToShelf();vm.search("词");vm.ui.first{!it.findLoading};assertEquals(b.sourceId,vm.ui.value.books.single().sourceId);assertTrue(vm.addToShelf(vm.ui.value.books.single()))
   assertEquals(4,vm.ui.value.shelfBooks.size)
   vm.connect("https://other-source.fixture.invalid","a","fake");vm.ui.first{!it.shelfLoading};assertEquals(5,vm.ui.value.shelfBooks.size)
   val otherSource=vm.ui.value.shelfBooks.single{it.sourceId==vm.store.sourceId};assertNotEquals(a.sourceId,otherSource.sourceId)
   val sourceCalls=calls.size;vm.open(a);vm.ui.first{!it.loading};assertTrue(vm.ui.value.error!!.contains("BH_AUTH"));assertEquals(sourceCalls,calls.size);assertEquals(17,vm.positionOf(a)!!.offset)
   vm.signOut();assertEquals(5,vm.ui.value.shelfBooks.size)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun completeBookHubCopyWithRestoredSessionColdReadsAllPagesAndCoverWithoutAnyHttpThenRevocationBlocksIt()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val id=BookHubSource.ownerId(c)
   val cache=runtime.cache(id);BookDownloader(c,cache,w.network).download("public:1",false);ReadingStore(app,id).addToShelf(book("public:1",id))
   val before=w.calls.size;val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime);vm.ui.first{!it.shelfLoading};val b=vm.ui.value.shelfBooks.single()
   vm.open(b);vm.ui.first{!it.loading};assertNull(vm.ui.value.error);assertNotNull(vm.ui.value.graphicId)
   vm.shelfCover(b);for(ref in vm.ui.value.chapters){vm.open(b,ref.id);vm.ui.first{!it.loading};assertNull(vm.ui.value.error)}
   assertEquals(before,w.calls.size);vm.position(2);val pos=vm.positionOf(b)!!;vm.backToShelf()
   val cold=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=OfflineDownloads(app,w.network,vault,false));cold.ui.first{!it.shelfLoading};cold.open(b);cold.ui.first{!it.loading}
   assertEquals(pos.chapterId,cold.ui.value.chapter!!.id);assertEquals(pos.offset,cold.ui.value.offset);assertEquals(before,w.calls.size)
   val conserved=cold.positionOf(b)
   w.accessStatus=401;try{cold.chapterList(b,true);fail()}catch(_:SourceProblem){}
   val revoked=w.calls.size;cold.backToShelf();assertEquals(1,cold.ui.value.shelfBooks.size);cold.open(b);cold.ui.first{!it.loading};assertTrue(cold.ui.value.error!!.contains("BH_AUTH"));assertEquals(revoked,w.calls.size);assertTrue(cache.status(b.id,true).complete)
   assertTrue(cold.inspectDownload(b).complete);cold.clearDownload(b)
   withContext(Dispatchers.IO){withTimeout(5000){while(cache.manifest(b.id)!=null)delay(10)}}
   assertEquals(conserved,cold.positionOf(b));assertEquals(listOf(b.key),cold.ui.value.shelfBooks.map{it.key});assertEquals(revoked,w.calls.size)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun missingAndDamagedCopiesDoNotPretendCompleteOrOverwriteProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val id=BookHubSource.ownerId(c);val cache=runtime.cache(id)
   BookDownloader(c,cache,w.network).download("public:1",false);val source=BookHubSource(c,cache,w.network);val refs=source.chapters("public:1");val store=ReadingStore(app,id);val b=book("public:1",id);store.addToShelf(b);store.save(b.id,refs[1].id,7);val old=store.position(b.id)
   val raw=File(app.noBackupFilesDir,"offline-v1/"+sha(id.toByteArray())+"/"+sha(b.id.toByteArray())+"/"+sha(w.version.toByteArray())+"/"+sha("a".toByteArray())+".bin");raw.writeBytes(ByteArray(10))
   w.network.online=false;val count=w.calls.size
   val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime);vm.ui.first{!it.shelfLoading};vm.open(b);vm.ui.first{!it.loading};assertNotNull(vm.ui.value.error);assertFalse(cache.status(b.id,true).complete);assertEquals(old,store.position(b.id));assertEquals(count,w.calls.size)
  }finally{Dispatchers.resetMain()}
 }
 private class Owned(val file:File,val expected:String):BookSource {
  override val id="owned-local-fixture-v1";override val independentOffline=true
  val book=Book("own","自有合成文本","自写作者","","",1,0,sourceId=id)
  override suspend fun books()=listOf(book)
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("own-1","自有章节"))
  override suspend fun chapter(bookId:String,chapterId:String):Chapter {
   if(sha(file)!=expected)throw SourceProblem(FailureKind.PROTOCOL,"自有文件损坏",diagnostic="LOCAL_CORRUPT")
   return Chapter(chapterId,"自有章节",listOf(file.readText()))
  }
 }
 @Test fun independentlyOwnedFileReadsWithoutAnySessionAfterColdStartAndOtherSourceSelection()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val f=File(app.filesDir,"owned.txt").apply{parentFile!!.mkdirs();writeText("自写且明确允许永久离线的合成资料。".repeat(100))};val provider=Owned(f,sha(f));ReadingStore(app,provider.id).addToShelf(provider.book)
   val vm=ReaderViewModel(app,sessionVault=MemorySessionVault(),independentSources=listOf(provider));vm.ui.first{!it.shelfLoading};vm.open(provider.book);vm.ui.first{!it.loading};assertNull(vm.ui.value.error);vm.position(31);vm.signOut();assertTrue(vm.ui.value.shelfBooks.any{it.key==provider.book.key})
   val cold=ReaderViewModel(app,TestReadingSource(),sessionVault=MemorySessionVault(),independentSources=listOf(Owned(f,sha(f))));cold.ui.first{!it.shelfLoading};cold.open(provider.book);cold.ui.first{!it.loading};assertNull(cold.ui.value.error);assertEquals(31,cold.ui.value.offset)
   f.writeText("损坏");cold.open(provider.book);cold.ui.first{!it.loading};assertTrue(cold.ui.value.error!!.contains("LOCAL_CORRUPT"));assertEquals(31,cold.positionOf(provider.book)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun unrelatedAccountAuthFailureDoesNotCoverIndependentlyOwnedReading()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val release=CompletableDeferred<Unit>();val f=File(app.filesDir,"independent.txt").apply{parentFile!!.mkdirs();writeText("自有且永久授权的测试正文。".repeat(80))};val provider=Owned(f,sha(f));ReadingStore(app,provider.id).addToShelf(provider.book)
   val origin="https://independent-auth.fixture.invalid";val session=SavedSession(origin,"account","d","a","r")
   val wire=WireTransport{uri,_,_,_,_->if(uri.path.endsWith("/catalog/search")){release.await();WireResponse(401,"{\"code\":\"SESSION_REVOKED\"}".toByteArray())}else WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())}
   val vault=MemorySessionVault().apply{value=session};val vm=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault,independentSources=listOf(provider));vm.ui.first{!it.shelfLoading}
   vm.search("词");vm.open(provider.book);vm.ui.first{!it.loading};release.complete(Unit);vm.ui.first{!it.findLoading}
   assertNull(vm.ui.value.error);assertNotNull(vm.ui.value.chapter);assertNull(vm.ui.value.notice);assertNotNull(vm.ui.value.findError);assertNull(vault.value)
   vm.position(42);assertEquals(42,vm.positionOf(provider.book)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun invalidHistoricalNamespaceIsIsolatedAndBookHubCannotClaimIndependentProviderRights()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val bad=owner("broken");app.getSharedPreferences("reader-$bad",0).edit().putString("localShelf","not-json").commit()
   val good=ReadingStore(app,owner("good"));good.addToShelf(book("public:1",good.sourceId))
   val vm=ReaderViewModel(app,sessionVault=MemorySessionVault());vm.ui.first{!it.shelfLoading};assertEquals(listOf(good.sourceId),vm.ui.value.shelfBooks.map{it.sourceId});assertNotNull(vm.ui.value.localShelfWarning)
   assertEquals("not-json",app.getSharedPreferences("reader-$bad",0).getString("localShelf",null))
   val spoof=object:BookSource {override val id=owner("spoof");override val independentOffline=true;override suspend fun books()=emptyList<Book>();override suspend fun chapters(bookId:String)=emptyList<ChapterRef>();override suspend fun chapter(bookId:String,chapterId:String)=error("never")}
   try{ReaderViewModel(app,sessionVault=MemorySessionVault(),independentSources=listOf(spoof));fail("BookHub namespace must not become independently licensed")}catch(_:IllegalArgumentException){}
  }finally{Dispatchers.resetMain()}
 }
 @Test fun explicitBookReadDenialSurvivesColdStartAndOnlyFreshAuthenticatedSuccessCanRestoreIt()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false);val id=BookHubSource.ownerId(c)
   val cache=runtime.cache(id);BookDownloader(c,cache,w.network).download("public:1",false);val b=book("public:1",id);ReadingStore(app,id).addToShelf(b)
   val denied=WireTransport{uri,method,headers,body,max->if(uri.path.endsWith("/bookshelf")) WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page().apply{getJSONArray("items").getJSONObject(0).put("capabilities",org.json.JSONObject().put("canRead",false))}).toByteArray()) else w.execute(uri,method,headers,body,max)}
   val vm=ReaderViewModel(app,connectionGate=gate(denied),sessionVault=vault,offlineRuntime=runtime);vm.ui.first{!it.shelfLoading};val before=w.calls.size
   vm.open(b);vm.ui.first{!it.loading};assertNull(vm.ui.value.error);assertEquals(before,w.calls.size)
   val old=vm.positionOf(b);vm.refreshShelf();vm.ui.first{!it.shelfLoading};assertTrue(vm.ui.value.error!!.contains("BH_FORBIDDEN"));assertFalse(vm.ui.value.canReturnPage);assertTrue(cache.status(b.id,true).complete)
   val cold=ReaderViewModel(app,connectionGate=gate(denied),sessionVault=vault,offlineRuntime=runtime);cold.ui.first{!it.shelfLoading};cold.open(b);cold.ui.first{!it.loading};assertTrue(cold.ui.value.error!!.contains("BH_FORBIDDEN"));assertEquals(before,w.calls.size);assertEquals(old,cold.positionOf(b))
   w.network.online=false
   try{cold.chapterList(b,true);fail("network failure must not clear persisted denial")}catch(_:Exception){}
   assertEquals(false,ReadingStore(app,id).knownReadPermission(b.id));cold.open(b);cold.ui.first{!it.loading};assertTrue(cold.ui.value.error!!.contains("BH_FORBIDDEN"))
   w.network.online=true;cold.chapterList(b,true);assertEquals(true,ReadingStore(app,id).knownReadPermission(b.id));val after=w.calls.size
   cold.open(b);cold.ui.first{!it.loading};assertNull(cold.ui.value.error);assertEquals(after,w.calls.size);assertEquals(old!!.chapterId,cold.ui.value.chapter!!.id)
   w.accessStatus=403;w.accessError="FORBIDDEN"
   try{cold.chapterList(b,true);fail("explicit server denial must block the active cached page")}catch(_:SourceProblem){}
   assertEquals(false,ReadingStore(app,id).knownReadPermission(b.id));assertTrue(cold.ui.value.error!!.contains("BH_FORBIDDEN"));assertFalse(cold.ui.value.canReturnPage)
  }finally{Dispatchers.resetMain()}
 }

 @Test fun readDenialArrivingDuringResourceLoadCannotCommitLateBodyOrProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   var deny=false;val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val vault=MemorySessionVault()
   val wire=WireTransport{uri,_,_,_,_->val text=when {
    uri.path.endsWith("/auth/login")->BookHubFixtures.envelope(BookHubFixtures.session())
    uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page().apply{getJSONArray("items").getJSONObject(0).put("capabilities",org.json.JSONObject().put("canRead",!deny))})
    uri.path.endsWith("/access")->BookHubFixtures.envelope(BookHubFixtures.txtManifest())
    else->{entered.complete(Unit);release.await();BookHubFixtures.firstText}
   };WireResponse(200,text.toByteArray())}
   val vm=ReaderViewModel(app,connectionGate=gate(wire),sessionVault=vault);vm.connect("https://late-denial.fixture.invalid","fake","fake");vm.ui.first{!it.shelfLoading};val b=vm.ui.value.shelfBooks.single()
   vm.open(b);entered.await();deny=true;vm.refreshShelf();vm.ui.first{!it.shelfLoading};release.complete(Unit);runCurrent()
   assertTrue(vm.ui.value.error!!.contains("BH_FORBIDDEN"));assertNull(vm.positionOf(b));assertFalse(vm.ui.value.canReturnPage)
  }finally{Dispatchers.resetMain()}
 }

}
