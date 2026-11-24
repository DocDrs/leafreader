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
class Patch110Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private suspend fun vm(w:ShelfSyncFixtures,account:String=w.session.accountId):Pair<ReaderViewModel,OfflineDownloads> {
  val vault=MemorySessionVault().apply{value=w.client(account).snapshot()};val rt=OfflineDownloads(app,w.network,vault,false).apply{setAutomaticWifi(false)}
  val vm=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=rt);vm.ui.first{!it.shelfLoading};return vm to rt
 }
 private fun seed(rt:OfflineDownloads,w:ShelfSyncFixtures,b:Book) {val cache=rt.cache(b.sourceId);val e=cache.generation(b.id);val m=w.content.manifest().put("bookId",b.id);cache.saveManifest(b.id,m,e);w.content.bytes().forEach{(id,raw)->cache.put(b.id,w.content.version,id,raw,e)};assertTrue(cache.complete(b.id,m,e))}
 private suspend fun done(vm:ReaderViewModel,b:Book) {withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{b.key !in it.membershipBusy && b.key !in it.clearingBooks}}}}
 private suspend fun refresh(vm:ReaderViewModel) {vm.syncShelfFromBookHub();withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.syncingShelf && !it.shelfLoading}}}}
 @Test fun commandsUseVerifiedPutDeletePathsRevisionAndOperationIdentifiers()=runTest {
  val w=ShelfSyncFixtures();val c=w.client();val before=c.shelfDetail("public:1");assertTrue(before.present)
  c.changeShelf("public:1",false,before.revision);assertFalse(c.shelfDetail("public:1").present);c.changeShelf("public:1",true,1)
  val commands=w.calls.filter{it.method in listOf("PUT","DELETE")};assertEquals(listOf("DELETE","PUT"),commands.map{it.method});assertTrue(commands.all{it.path=="/api/v1/me/bookshelf/public:1" && it.query==null});assertEquals(listOf(0L,1L),commands.map{it.body!!.getLong("baseRevision")});assertEquals(2,commands.map{it.body!!.getString("operationId")}.toSet().size)
  assertTrue(w.calls.all{it.path.startsWith("/api/v1/") && !it.path.contains("reading-state")});assertTrue(w.calls.first().path.endsWith("/me/bookshelf"))
 }
 @Test fun privateDetailAndInvalidIdsNeverEscapeExactOrigin()=runTest {
  val w=ShelfSyncFixtures();val c=w.client();val id="private:12345678-1234-1234-1234-123456789abc";c.shelfDetail(id);assertTrue(w.calls.any{it.path=="/api/v1/me/private-books/$id"})
  val count=w.calls.size;for(idBad in listOf("../public:1","https://elsewhere.invalid","public:1?token=x"))assertTrue(runCatching{c.shelfDetail(idBad)}.isFailure);assertEquals(count,w.calls.size)
 }
 @Test fun malformedOrConflictingAcknowledgementsFailClosed()=runTest {
  val w=ShelfSyncFixtures();val c=w.client();w.failure=409
  assertEquals(FailureKind.VERSION,(runCatching{c.changeShelf("public:1",false,0)}.exceptionOrNull() as SourceProblem).kind);assertTrue("public:1" in w.members)
  w.failure=0;for(state in listOf(JSONObject(),JSONObject().put("revision",1).put("deletedAt",JSONObject.NULL),JSONObject().put("revision",-1).put("deletedAt","date"))) {
   w.responseOverride={call->if(call.method=="DELETE")WireResponse(200,BookHubFixtures.envelope(JSONObject().put("state",state)).toByteArray())else null}
   assertTrue(runCatching{c.changeShelf("public:1",false,0)}.isFailure)
  }
 }
 @Test fun onlineAddAndRemoveOnlyCommitLocalAfterAcknowledgementAndKeepOtherBook()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val (vm,rt)=vm(w);refresh(vm);val b=w.book();val st=ReadingStore(app,b.sourceId);st.save(b.id,"saved",31);st.preserveRecovery(b.id);seed(rt,w,b)
   val other=w.book("public:2");st.addToShelf(other);st.save(other.id,"kept",17);val pos=st.position(other.id)
   vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);done(vm,b)
   assertFalse("public:1" in w.members);assertFalse(vm.isOnShelf(b));assertNull(st.position(b.id));assertNull(st.preservedPosition(b.id));assertNull(rt.cache(b.sourceId).manifest(b.id));assertEquals(pos,st.position(other.id));assertTrue(vm.isOnShelf(other))
   vm.search("自写");advanceUntilIdle();vm.addToShelf(b);done(vm,b);assertTrue(vm.isOnShelf(b));assertTrue("public:1" in w.members);assertNull(st.position(b.id));assertTrue(w.calls.none{it.path.contains("reading-state")})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun deleteFailurePreservesProgressCopiesAndExplicitRetryReadsLatestRevision()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val (vm,rt)=vm(w);refresh(vm);val b=w.book();val st=ReadingStore(app,b.sourceId);st.save(b.id,"saved",21);seed(rt,w,b);w.failure=409
   vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);done(vm,b);assertTrue(vm.isOnShelf(b));assertEquals(21,st.position(b.id)!!.offset);assertTrue(rt.cache(b.sourceId).status(b.id,true).complete);assertTrue(vm.shelfOperation(b).reason.contains("修订冲突"))
   w.failure=0;w.revisions[b.id]=7;vm.retryShelfOperation(b);done(vm,b);assertFalse(vm.isOnShelf(b));assertNull(st.position(b.id));assertEquals(7L,w.calls.last{it.method=="DELETE"}.body!!.getLong("baseRevision"))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun lostDeleteResponseIsNotReplayedAndExplicitRetryConfirmsStateWithoutSecondDelete()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val(vm,rt)=vm(w);refresh(vm);val b=w.book();val st=ReadingStore(app,b.sourceId);st.save(b.id,"saved",11);seed(rt,w,b);w.timeoutAfterCommit=true
   vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);done(vm,b);assertEquals("UNCONFIRMED",vm.shelfOperation(b).state);assertTrue(vm.isOnShelf(b));assertNotNull(st.position(b.id));assertEquals(1,w.mutationCount)
   vm.retryShelfOperation(b);done(vm,b);assertFalse(vm.isOnShelf(b));assertNull(st.position(b.id));assertEquals(1,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun externalRemovalNeedsFullSnapshotAndDetailsAndNeverClearsProgressOrFiles()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val(vm,rt)=vm(w);refresh(vm);val b=w.book();val st=ReadingStore(app,b.sourceId);st.save(b.id,"anchor",29);seed(rt,w,b);w.members.clear();refresh(vm)
   assertEquals("SUCCESS",vm.shelfUpdateRecord().state);assertFalse(vm.isOnShelf(b));assertEquals(29,st.position(b.id)!!.offset);assertTrue(rt.cache(b.sourceId).status(b.id,true).complete)
   w.members+=b.id;refresh(vm);assertTrue(vm.isOnShelf(b));assertEquals(29,vm.positionOf(b)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun emptyContradictionAndMalformedPartialPagesCannotRemoveLocalMembership()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val(vm,_)=vm(w);refresh(vm);val b=w.book();ReadingStore(app,b.sourceId).save(b.id,"anchor",23)
   w.pageOverride={JSONObject().put("items",JSONArray()).put("hasMore",false)};refresh(vm);assertEquals("FAILED",vm.shelfUpdateRecord().state);assertTrue(vm.isOnShelf(b))
   w.pageOverride={query->if(query?.contains("cursor=")==true)JSONObject().put("items",JSONArray()).put("hasMore",true) else BookHubFixtures.page("public:2","next")};refresh(vm);assertTrue(vm.isOnShelf(b));assertEquals(23,vm.positionOf(b)!!.offset)
   w.pageOverride={JSONObject().put("items",JSONArray().put(BookHubFixtures.book("public:2")).put(BookHubFixtures.book("public:2"))).put("hasMore",false)};w.members.clear();refresh(vm);assertTrue(vm.isOnShelf(b));assertEquals("FAILED",vm.shelfUpdateRecord().state)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun legacyHiddenAndOfflineDeletionNeverUploadOnReconnectAndExplicitReaddDoes()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val b=w.book();val st=ReadingStore(app,b.sourceId);st.rememberServiceShelf(listOf(b));st.hideFromShelf(setOf(b.id));val(vm,_)=vm(w);refresh(vm)
   assertFalse(vm.isOnShelf(b));assertEquals(0,w.mutationCount);vm.addToShelf(b);done(vm,b);assertTrue(vm.isOnShelf(b));assertEquals(0,w.mutationCount)
   w.network.online=false;vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);done(vm,b);assertFalse(vm.isOnShelf(b));w.network.online=true;refresh(vm);assertFalse(vm.isOnShelf(b));assertTrue(b.id in w.members);assertEquals(0,w.mutationCount)
   vm.addToShelf(b);done(vm,b);assertTrue(vm.isOnShelf(b));assertEquals(0,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun localOnlyLegacyBooksRemainVisibleAndExplicitJoinIsRequired()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val b=w.book("public:2");ReadingStore(app,b.sourceId).addToShelf(b);val(vm,_)=vm(w);refresh(vm)
   assertTrue(vm.isOnShelf(b));assertTrue(vm.offerJoinBookHub(b));assertFalse(b.id in w.members);assertEquals(0,w.mutationCount);vm.joinBookHub(b);done(vm,b);assertTrue(b.id in w.members);assertFalse(vm.offerJoinBookHub(b))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun delayedWriteAfterAccountSwitchCannotPublishOrCleanEitherAccount()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val(vm,rt)=vm(w);refresh(vm);val b=w.book();val st=ReadingStore(app,b.sourceId);st.save(b.id,"old",33);seed(rt,w,b)
   val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.beforeResponse={call->if(call.method=="DELETE"){started.complete(Unit);withContext(NonCancellable){release.await()}}}
   vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);started.await();vm.signOut();release.complete(Unit);done(vm,b)
   assertTrue(vm.isOnShelf(b));assertEquals(33,st.position(b.id)!!.offset);assertTrue(rt.cache(b.sourceId).status(b.id,true).complete);assertEquals("UNCONFIRMED",vm.shelfOperation(b).state)
   val before=w.mutationCount;val(cold,_)=vm(w,"another-account");assertTrue(cold.isOnShelf(b));assertFalse(cold.willSyncMembership(b));cold.retryShelfOperation(b);assertEquals(before,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun persistedInterruptedOperationNeverAutomaticallyReplaysAtStartup()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val b=w.book();val st=ReadingStore(app,b.sourceId);st.rememberServiceShelf(listOf(b));st.saveShelfOperation(b.id,ShelfOperationRecord(false,"RUNNING",1));st.save(b.id,"saved",19)
   val(vm,_)=vm(w);assertEquals("UNCONFIRMED",vm.shelfOperation(b).state);assertEquals(0,w.mutationCount);assertEquals(19,vm.positionOf(b)!!.offset);assertTrue(vm.isOnShelf(b))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun remoteRemoveIsIsolatedFromSameIdInOtherServiceAndAccount()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val b=w.book();val foreign=w.book(owner=BookHubSource.ownerId(w.client("foreign","https://other.fixture.invalid")));LocalShelfIndex(app).remember(foreign.sourceId);ReadingStore(app,foreign.sourceId).addToShelf(foreign);ReadingStore(app,foreign.sourceId).save(foreign.id,"foreign",61)
   val(vm,_)=vm(w);refresh(vm);w.members.clear();refresh(vm);assertFalse(vm.isOnShelf(b));assertTrue(vm.isOnShelf(foreign));assertEquals(61,vm.positionOf(foreign)!!.offset);assertTrue(w.calls.none{it.account=="foreign"})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun authRevocationAndNetworkErrorsNeverRemoveMembershipOrStoredProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val(vm,_)=vm(w);refresh(vm);val b=w.book();ReadingStore(app,b.sourceId).save(b.id,"old",18);w.failure=401
   vm.removeFromShelf(setOf(b.key),vm.ui.value.connectionRevision);done(vm,b);assertTrue(vm.isOnShelf(b));assertEquals(18,vm.positionOf(b)!!.offset);assertFalse(vm.bookHubConnected())
  }finally{Dispatchers.resetMain()}
 }
 private suspend fun loginDifference(w:ShelfSyncFixtures):ReaderViewModel {
  val b=w.book("public:2");val st=ReadingStore(app,b.sourceId);st.addToShelf(b);st.save(b.id,"local-anchor",37)
  val vm=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=MemorySessionVault(),offlineRuntime=OfflineDownloads(app,w.network,MemorySessionVault(),false).apply{setAutomaticWifi(false)})
  vm.connect(w.session.origin,w.session.accountId,"synthetic-password-not-a-credential")
  withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.connecting && !it.shelfLoading}}};return vm
 }
 private suspend fun choiceDone(vm:ReaderViewModel) {withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.shelfChoiceBusy}}}}
 @Test fun loginDifferenceDoesNotMergeOrWriteBeforeUserChoosesAndCanBePostponed()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);val d=vm.ui.value.shelfDifference!!
   assertEquals(listOf("public:2"),d.addRemote.map{it.id});assertEquals(listOf("public:1"),d.removeRemote.map{it.id});assertFalse(vm.isOnShelf(w.book()));assertTrue(vm.isOnShelf(w.book("public:2")));assertEquals(0,w.mutationCount)
   vm.postponeShelfDifference();assertFalse(vm.ui.value.showShelfChoice);assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);refresh(vm);assertFalse(vm.ui.value.showShelfChoice);assertEquals(0,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun remoteIncrementalMergeRetainsLocalOnlyProgressAndDoesNotLaterPushOrHideIt()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);val b=w.book("public:2");vm.chooseShelfDifference(vm.ui.value.shelfDifference!!.token,false);choiceDone(vm)
   assertFalse(vm.ui.value.shelfChoicePending);assertEquals("SUCCESS",vm.shelfUpdateRecord().state);assertTrue(vm.isOnShelf(b));assertTrue(vm.isOnShelf(w.book()));assertEquals(37,vm.positionOf(b)!!.offset);assertEquals(0,w.mutationCount)
   refresh(vm);assertTrue(vm.isOnShelf(b));assertEquals(0,w.mutationCount);assertTrue(b.id in ReadingStore(app,b.sourceId).localOnlyIds())
   vm.signOut();val before=w.mutationCount;vm.connect(w.session.origin,w.session.accountId,"synthetic");vm.ui.first{!it.connecting && !it.shelfLoading};assertTrue(vm.ui.value.shelfChoicePending);assertEquals(before,w.mutationCount);assertEquals(37,vm.positionOf(b)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun localWinsAddsRecognizedNotYetShelvedBookAndOnlyRemovesPreviewedRemoteMembers()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);val b=w.book("public:2");val foreign=w.book("public:8",BookHubSource.ownerId(w.client("foreign")));LocalShelfIndex(app).remember(foreign.sourceId);ReadingStore(app,foreign.sourceId).addToShelf(foreign)
   vm.chooseShelfDifference(vm.ui.value.shelfDifference!!.token,true);choiceDone(vm)
   assertFalse(vm.ui.value.shelfChoicePending);assertEquals(setOf("public:2"),w.members);assertEquals(2,w.mutationCount);assertEquals(37,vm.positionOf(b)!!.offset);assertTrue(vm.isOnShelf(b));assertTrue(vm.isOnShelf(foreign));assertFalse(vm.isOnShelf(w.book()));assertTrue(w.calls.none{it.method in listOf("PUT","DELETE") && it.path.endsWith("public:8")})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun changedPreviewCannotExecuteAnyWriteAndRequiresFreshComparison()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);val token=vm.ui.value.shelfDifference!!.token;w.members+="public:3";vm.chooseShelfDifference(token,true);choiceDone(vm)
   assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);assertEquals(0,w.mutationCount);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset);assertNotNull(vm.ui.value.notice)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun localWinsPartialFailureDoesNotReportCompleteOrClearProgressAndRequiresExplicitRecompare()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);w.responseOverride={call->if(call.method=="DELETE")WireResponse(409,"{\"code\":\"REVISION_CONFLICT\"}".toByteArray())else null}
   vm.chooseShelfDifference(vm.ui.value.shelfDifference!!.token,true);choiceDone(vm)
   assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);assertEquals(1,w.mutationCount);assertTrue("public:2" in w.members);assertTrue("public:1" in w.members);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset);assertTrue(vm.ui.value.notice!!.contains("未全部完成"))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun unrecognizedLocalBookIsSkippedWithoutGuessingByTitle()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();w.responseOverride={call->if(call.path.endsWith("/catalog/books/public:2"))WireResponse(404,"{\"code\":\"NOT_FOUND\"}".toByteArray())else null};val vm=loginDifference(w);val d=vm.ui.value.shelfDifference!!
   assertTrue(d.addRemote.isEmpty());assertEquals(listOf("public:2"),d.skipped.map{it.id});vm.chooseShelfDifference(d.token,true);choiceDone(vm);assertTrue(vm.isOnShelf(w.book("public:2")));assertFalse("public:2" in w.members);assertFalse(vm.ui.value.shelfChoicePending);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun failedOrPartialLoginShelfNeverOffersExecutableDirection()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();w.pageOverride={JSONObject().put("items",JSONArray()).put("hasMore",true)};val vm=loginDifference(w)
   assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);assertFalse(vm.ui.value.showShelfChoice);assertEquals(0,w.mutationCount);assertTrue(vm.isOnShelf(w.book("public:2")))
  }finally{Dispatchers.resetMain()}
 }

 @Test fun offlineEmptyPageIsNotRemoteEmptyButAuthenticatedTrueEmptyCanBeCompared()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val b=w.book("public:2");val st=ReadingStore(app,b.sourceId);st.addToShelf(b);st.requireShelfChoice();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val rt=OfflineDownloads(app,w.network,vault,false);w.network.online=false
   val vm=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=rt);vm.loadShelf();vm.ui.first{!it.shelfLoading};assertTrue(w.calls.isEmpty());assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);assertFalse(vm.ui.value.shelfKnownComplete);assertTrue(vm.isOnShelf(b));assertNotNull(vm.ui.value.shelfError)
   w.network.online=true;w.members.clear();vm.connect(w.session.origin,w.session.accountId,"synthetic");withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{it.shelfDifference!=null}}};val d=vm.ui.value.shelfDifference!!;assertTrue(d.removeRemote.isEmpty());assertEquals(listOf(b.id),d.addRemote.map{it.id});assertTrue(w.calls.any{it.path.endsWith("/me/bookshelf")});assertEquals(0,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun failedSecondPageCannotProduceLoginComparisonOrHideAnyExistingBook()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();w.members+="public:3";w.pageSize=1;w.responseOverride={call->if(call.path.endsWith("/me/bookshelf") && call.query?.contains("cursor=")==true)WireResponse(503,"{\"code\":\"UNAVAILABLE\"}".toByteArray())else null}
   val vm=loginDifference(w);withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{it.shelfError!=null}}};assertNull(vm.ui.value.shelfDifference);assertFalse(vm.ui.value.shelfKnownComplete);assertTrue(vm.isOnShelf(w.book("public:2")));assertEquals(0,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun explicitMergeRestoresOnlyCorrespondingLegacyHiddenMembershipAndNeverClearedContent()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val owner=w.book().sourceId;val st=ReadingStore(app,owner);val one=w.book();val three=w.book("public:3")
   st.addToShelf(one);st.addToShelf(three);st.save(one.id,"old",40);st.requestCleanup(one.id,true);st.finishCleanup(one.id);st.hideFromShelf(setOf(three.id));val vm=loginDifference(w)
   assertFalse(vm.isOnShelf(one));assertNull(st.position(one.id));assertTrue(three.id in st.hiddenShelfIds());vm.chooseShelfDifference(vm.ui.value.shelfDifference!!.token,false);choiceDone(vm)
   assertTrue(vm.isOnShelf(one));assertFalse(vm.isOnShelf(three));assertNull(st.position(one.id));assertNull(st.preservedPosition(one.id));assertTrue(three.id in st.hiddenShelfIds());assertEquals(0,w.mutationCount)
   val rt=OfflineDownloads(app,w.network,MemorySessionVault(),false);assertNull(rt.cache(owner).manifest(one.id));refresh(vm);assertFalse(vm.isOnShelf(three))
  }finally{Dispatchers.resetMain()}
 }

 @Test fun tokenRefreshRetriesSameOperationWithCurrentDeviceWithoutCrossOriginForwarding()=runTest {
  val w=ShelfSyncFixtures();val c=w.client();var first=true
  w.responseOverride={call->if(call.method=="DELETE" && first){first=false;WireResponse(401,"{\"code\":\"SESSION_EXPIRED\"}".toByteArray())}else null}
  c.changeShelf("public:1",false,0)
  val commands=w.calls.filter{it.method=="DELETE"};assertEquals(2,commands.size);assertEquals(commands[0].body!!.getString("operationId"),commands[1].body!!.getString("operationId"));assertEquals(0L,commands[1].body!!.getLong("baseRevision"));assertEquals("synthetic-device",commands[0].body!!.getString("deviceId"));assertEquals("fixture-device",commands[1].body!!.getString("deviceId"));assertEquals(1,w.mutationCount);assertTrue(w.calls.all{it.path.startsWith("/api/v1/")})
 }
 @Test fun revisionOnlyPreviewChangeAlsoRequiresReconfirmationWithoutAnyWrite()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();val vm=loginDifference(w);val token=vm.ui.value.shelfDifference!!.token;w.revisions["public:1"]=9;vm.chooseShelfDifference(token,true);choiceDone(vm);assertEquals(0,w.mutationCount);assertTrue(vm.ui.value.shelfChoicePending);assertNull(vm.ui.value.shelfDifference);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
  }finally{Dispatchers.resetMain()}
 }

}
