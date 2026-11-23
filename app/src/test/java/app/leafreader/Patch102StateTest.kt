package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Patch102StateTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun envelope(j:JSONObject)=WireResponse(200,BookHubFixtures.envelope(j).toByteArray())
 private class Source:BookSource {
  override val id="patch102-reading"
  var lists=0;var failure:FailureKind?=null
  override suspend fun books()=listOf(Book("book","自写审查","作者","","",2,0))
  override suspend fun chapters(bookId:String):List<ChapterRef>{lists++;return listOf(ChapterRef("a","甲章"),ChapterRef("b","乙章"))}
  override suspend fun chapter(bookId:String,chapterId:String):Chapter {
   if(chapterId=="b" && failure!=null)throw SourceProblem(failure!!,"合成失败")
   return Chapter(chapterId,chapterId,listOf("自写测试段落。".repeat(80)))
  }
 }
 @Test fun failedBoundaryCanReturnAndContinueButAuthCannotRestoreReadablePage()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val s=Source();val vm=ReaderViewModel(app,s,sessionVault=MemorySessionVault());advanceUntilIdle();vm.open(vm.ui.value.shelfBooks.single());advanceUntilIdle();vm.position(23)
   val saved=vm.store.position("book");s.failure=FailureKind.NETWORK;vm.boundary(1);advanceUntilIdle()
   assertNotNull(vm.ui.value.error);assertTrue(vm.ui.value.canReturnPage);assertEquals(saved,vm.store.position("book"))
   vm.returnReadablePage();assertNull(vm.ui.value.error);assertEquals(23,vm.ui.value.offset);vm.position(31);assertEquals(31,vm.store.position("book")!!.offset)
   s.failure=FailureKind.AUTH;vm.boundary(1);advanceUntilIdle();assertFalse(vm.ui.value.canReturnPage);vm.returnReadablePage();assertNotNull(vm.ui.value.error)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun normalDirectoryAndSelectionReuseListAndInvalidPositionNeedsExplicitRecovery()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val s=Source();val vm=ReaderViewModel(app,s,sessionVault=MemorySessionVault());advanceUntilIdle();val book=vm.ui.value.shelfBooks.single()
   vm.store.save(book.id,"removed",99);val old=vm.store.position(book.id);vm.open(book);advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("BH_VERSION"));assertEquals(old,vm.store.position(book.id))
   vm.chapterList(book);assertEquals(1,s.lists)
   vm.open(book,"b");advanceUntilIdle();assertEquals(1,s.lists);assertEquals("b",vm.store.position(book.id)!!.chapterId)
   val prefs=app.getSharedPreferences("reader-${s.id}",0);assertTrue(prefs.all.keys.any{it.startsWith("recovery-${book.id}-")})
   vm.chapterList(book,true);assertEquals(2,s.lists)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun editedTargetAndFailedOrCancelledLoginKeepOldSessionAndSuccessfulLoginSwitches()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val old=SavedSession("https://original.fixture.invalid","old","device","access","refresh")
   val vault=MemorySessionVault().apply{value=old};var reject=true
   val wire=WireTransport{uri,_,_,body,_->if(uri.path.endsWith("/auth/login")){if(reject)throw SourceProblem(FailureKind.NETWORK,"合成超时");envelope(BookHubFixtures.session("new"))}else envelope(BookHubFixtures.page())}
   val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault)
   advanceUntilIdle();val owner=vm.store.sourceId;vm.store.save("public:1","chapter",19)
   vm.connect("https://new.fixture.invalid","new","fake");advanceUntilIdle();assertEquals(old,vault.value);assertEquals(owner,vm.store.sourceId)
   vm.cancelConnection();assertEquals(old,vault.value);reject=false;vm.connect("https://new.fixture.invalid","new","fake");advanceUntilIdle()
   assertEquals("new",vault.value!!.accountId);assertNotEquals(owner,vm.store.sourceId);assertEquals(19,ReadingStore(app,owner).position("public:1")!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun canonicalAliasReloginRetainsLegacyShelfProgressOfflineAndQueueIdentity()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val raw="https://ALIAS.fixture.invalid:443";val session=SavedSession(raw,"same","device","access","refresh")
   val canonicalOwner="bookhub-v1-"+digest("https://alias.fixture.invalid|same").take(32)
   ReadingStore(app,canonicalOwner).save("public:1","canonical-conflict",99)
   val vault=MemorySessionVault().apply{value=session};val wire=WireTransport{uri,_,_,_,_->envelope(if(uri.path.endsWith("/auth/login"))BookHubFixtures.session("same")else BookHubFixtures.page())}
   val manager=OfflineDownloads(app,AlwaysOnline,vault,false)
   val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=manager)
   runCurrent();val owner=vm.store.sourceId;assertEquals("bookhub-v1-"+digest(raw+"|same").take(32),owner)
   val b=vm.ui.value.shelfBooks.single();vm.store.save(b.id,"kept",41);vm.download(b);val key=manager.key(owner,b.id);val nonce=manager.request(key)!!.nonce
   vm.connect("https://alias.fixture.invalid","same","fake");runCurrent()
   assertEquals(owner,vm.store.sourceId);assertEquals(41,vm.store.position(b.id)!!.offset);assertEquals(raw,vault.value!!.storageOrigin);assertEquals(nonce,manager.request(key)!!.nonce)
   vm.signOut();runCurrent();vm.connect("https://alias.fixture.invalid","same","fake");runCurrent()
   assertEquals(owner,vm.store.sourceId);assertEquals(41,vm.store.position(b.id)!!.offset);assertEquals(99,ReadingStore(app,canonicalOwner).position(b.id)!!.offset)
   assertEquals(ServiceOrigin(raw).value,ServiceOrigin("https://alias.fixture.invalid").value)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun searchRendersBeforeShelfCompletesAndUnknownCannotAddThenOwnedIsFiltered()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val more=CompletableDeferred<Unit>()
   val wire=WireTransport{uri,_,_,_,_->envelope(when {
    uri.path.endsWith("/catalog/search")->BookHubFixtures.page("public:2")
    uri.rawQuery?.contains("cursor=")==true->{more.await();BookHubFixtures.page("public:2")}
    else->BookHubFixtures.page().put("hasMore",true).put("nextCursor","next")
   })}
   val vault=MemorySessionVault().apply{value=SavedSession("https://search102.fixture.invalid","account","d","a","r")}
   val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault);runCurrent();vm.search("词");runCurrent()
   assertFalse(vm.ui.value.findLoading);assertEquals(1,vm.ui.value.books.size);val result=vm.ui.value.books.single();assertFalse(vm.canAddBook(result));assertFalse(vm.addToShelf(result))
   more.complete(Unit);advanceUntilIdle();assertTrue(vm.ui.value.books.isEmpty());assertTrue(vm.ui.value.shelfKnownComplete)
   vm.removeFromShelf(setOf(result.key),vm.ui.value.connectionRevision);vm.search("词");advanceUntilIdle();assertTrue(vm.canAddBook(vm.ui.value.books.single()));assertTrue(vm.addToShelf(vm.ui.value.books.single()))
   vm.refreshShelf();runCurrent();assertFalse(vm.ui.value.shelfKnownComplete);assertFalse(vm.canAddBook(result.copy(id="public:3")))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun readdUpdatesMetadataWithoutChangingSavedPosition() {
  val s=ReadingStore(app,"meta102");val b=Book("id","旧","旧作者","","",1,0,sourceId=s.sourceId)
  s.addToShelf(b);s.save(b.id,"chapter",19);val saved=s.position(b.id);s.hideFromShelf(setOf(b.id));s.addToShelf(b.copy(title="新",author="新作者",format="EPUB"))
  assertEquals("新",s.orderedShelf().single().title);assertEquals("EPUB",s.orderedShelf().single().format);assertEquals(saved,s.position(b.id))
 }
 @Test fun hiddenCopiesAreManageableAndVerificationNeverRequestsOrQueues()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val wire=OfflineFixtures();val c=wire.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,wire.network,vault,false)
   val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=runtime)
   val b=vm.ui.value.shelfBooks.single();val cache=runtime.cache(b.sourceId);val manifest=wire.manifest();val epoch=cache.generation(b.id)
   cache.saveManifest(b.id,manifest,epoch);wire.bytes().forEach{(id,bytes)->cache.put(b.id,wire.version,id,bytes,epoch)};assertTrue(cache.complete(b.id,manifest,epoch))
   vm.store.save(b.id,"kept",37);val position=vm.store.position(b.id);vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision)
   assertTrue(vm.ui.value.shelfBooks.isEmpty());assertEquals(b.key,vm.managementBooks().single().key)
   val count=wire.calls.size;assertTrue(vm.inspectDownload(b).complete);assertNull(runtime.request(runtime.key(b.sourceId,b.id)));assertEquals(count,wire.calls.size)
   vm.clearDownload(b);withContext(Dispatchers.IO){withTimeout(5000){while(cache.manifest(b.id)!=null)delay(10)}}
   assertFalse(vm.inspectDownload(b).complete);assertEquals(position,vm.store.position(b.id));assertEquals(count,wire.calls.size)
   assertTrue(vm.managementBooks().isEmpty())
  }finally{Dispatchers.resetMain()}
 }
 @Test fun shelfRefreshCannotStealReadingFailureRetry()=runTest {
  Dispatchers.setMain(StandardTestDispatcher(testScheduler))
  try {
   val source=Source();val vm=ReaderViewModel(app,source,sessionVault=MemorySessionVault());advanceUntilIdle();vm.open(vm.ui.value.shelfBooks.single());advanceUntilIdle()
   source.failure=FailureKind.NETWORK;vm.boundary(1);advanceUntilIdle();assertNotNull(vm.ui.value.error)
   vm.loadShelf();advanceUntilIdle();source.failure=null;vm.retry();advanceUntilIdle()
   assertNull(vm.ui.value.error);assertEquals("b",vm.ui.value.chapter!!.id)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun reorderedEpubNavigationDoesNotInventWholeBookPercentageOrFetchBodies()=runBlocking {
  val manifest=BookHubFixtures.epubManifest();val toc=manifest.getJSONArray("toc");manifest.put("toc",org.json.JSONArray().put(toc.getJSONObject(1)).put(toc.getJSONObject(0)))
  var bodies=0
  val wire=WireTransport{uri,_,_,_,_->if(!uri.path.endsWith("/access"))bodies++;envelope(manifest)}
  val c=BookHubClient(ServiceOrigin("https://progress102.fixture.invalid"),wire);c.restore(SavedSession(c.origin.value,"a","d","a","r"))
  val source=BookHubSource(c);assertEquals(2,source.chapters("public:1").size);assertFalse(source.progressIsReliable("public:1"));assertEquals(0,bodies)
 }
}
