package app.leafreader

import android.app.Application
import android.app.job.JobInfo
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.sync.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
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
class Patch112SyncTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private class H(val w:ShelfSyncFixtures,val vault:MemorySessionVault,val downloads:OfflineDownloads,val rt:ShelfSyncRuntime,val c:BookHubClient,val book:Book,val st:ReadingStore)
 private var now=1000000L
 private fun setup(w:ShelfSyncFixtures=ShelfSyncFixtures()):H {
  val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val dl=OfflineDownloads(app,w.network,vault,false).apply{setAutomaticWifi(false)}
  val rt=ShelfSyncRuntime(app,vault,w.gate(),dl,{now},false);rt.activate(c,false)
  val b=w.book();val st=ReadingStore(app,b.sourceId);st.addToShelf(b);st.save(b.id,"kept",31)
  return H(w,vault,dl,rt,c,b,st)
 }
 private suspend fun state(h:H,expected:Set<String>) {withContext(Dispatchers.IO){withTimeout(15000){h.rt.revision.first{h.rt.intents.get(h.book.sourceId,h.book.id)?.state in expected}}}}
 private fun seed(h:H) {val cache=h.downloads.cache(h.book.sourceId);val m=h.w.content.manifest();val e=cache.generation(h.book.id);cache.saveManifest(h.book.id,m,e);h.w.content.bytes().forEach{(id,bytes)->cache.put(h.book.id,h.w.content.version,id,bytes,e)};cache.complete(h.book.id,m,e)}
 @Test fun transientConflictRereadsRevisionAndSucceedsWithoutAUserRetry()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);var first=true
   h.w.responseOverride={c->if(c.method=="DELETE" && first){first=false;h.w.revisions[h.book.id]=7;WireResponse(409,"{\"code\":\"REVISION_CONFLICT\"}".toByteArray())}else null}
   assertTrue(h.rt.enqueue(h.c,h.book,false));state(h,setOf("DONE"));assertNull(h.st.position(h.book.id));assertNull(h.downloads.cache(h.book.sourceId).manifest(h.book.id));assertFalse(h.book.id in h.w.members)
   assertEquals(listOf(0L,7L),h.w.calls.filter{it.method=="DELETE"}.map{it.body!!.getLong("baseRevision")});assertTrue(h.w.calls.none{it.path.contains("reading-state")})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun networkFailurePersistsAndNextDayJobProcessesOnlyThePendingBook()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);h.w.responseOverride={c->if(c.method=="DELETE")throw SourceProblem(FailureKind.NETWORK,"synthetic")else null}
   h.rt.enqueue(h.c,h.book,false);state(h,setOf("PENDING"));assertEquals(31,h.st.position(h.book.id)!!.offset);assertTrue(h.downloads.cache(h.book.sourceId).status(h.book.id,true).complete)
   val before=h.w.calls.size;h.rt.runScheduled(true);assertEquals(before,h.w.calls.size)
   now+=ShelfIntents.DAY;h.w.responseOverride=null
   val cold=ShelfSyncRuntime(app,h.vault,h.w.gate(),h.downloads,{now},false);cold.runScheduled(true)
   assertEquals("DONE",cold.intents.get(h.book.sourceId,h.book.id)!!.state);assertNull(h.st.position(h.book.id));assertTrue(h.w.calls.filter{it.path.contains("/catalog/books/")}.all{it.path.endsWith(h.book.id)})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun offlineNeverSendsHttpOrClearsDataAndNormalSameAccountLoginResumes()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);h.w.network.online=false;h.rt.enqueue(h.c,h.book,false);state(h,setOf("PENDING"));assertTrue(h.w.calls.isEmpty());assertEquals(31,h.st.position(h.book.id)!!.offset)
   h.rt.deactivate();val other=h.w.client("another");h.vault.value=other.snapshot();h.rt.activate(other,true);h.w.network.online=true;h.rt.onLogin(other);assertTrue(h.w.calls.isEmpty());assertEquals(31,h.st.position(h.book.id)!!.offset)
   val own=h.w.client();h.vault.value=own.snapshot();h.rt.activate(own,true);h.rt.onLogin(own);state(h,setOf("DONE"));assertNull(h.st.position(h.book.id))
  }finally{Dispatchers.resetMain()}
 }
 @Test fun revokedCredentialsPauseUntilAnActualLoginAndNeverUseAPassword()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.w.failure=401;h.rt.enqueue(h.c,h.book,false);state(h,setOf("WAIT_AUTH"));assertNull(h.vault.value);assertEquals(31,h.st.position(h.book.id)!!.offset)
   val before=h.w.calls.size;now+=ShelfIntents.DAY;h.rt.runScheduled(true);assertEquals(before,h.w.calls.size)
   h.w.failure=0;val restored=h.w.client();h.vault.value=restored.snapshot();h.rt.activate(restored,true);h.rt.onLogin(restored);state(h,setOf("DONE"));assertTrue(h.w.calls.none{it.path.endsWith("/auth/login")})
  }finally{Dispatchers.resetMain()}
 }
 @Test fun lateDeleteAfterLogoutCannotClearLocalStateOrPublishToAnotherAccount()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
   h.w.beforeResponse={c->if(c.method=="DELETE"){started.complete(Unit);withContext(NonCancellable){release.await()}}}
   h.rt.enqueue(h.c,h.book,false);started.await();h.rt.deactivate();h.vault.clear();val other=h.w.client("other");h.vault.value=other.snapshot();h.rt.activate(other,true);release.complete(Unit)
   withContext(Dispatchers.IO){withTimeout(15000){h.rt.running.first{it.isEmpty()}}};assertEquals(31,h.st.position(h.book.id)!!.offset);assertTrue(h.downloads.cache(h.book.sourceId).status(h.book.id,true).complete);assertEquals("WAIT_AUTH",h.rt.intents.get(h.book.sourceId,h.book.id)!!.state)
   val n=h.w.mutationCount;h.rt.onLogin(other);assertEquals(n,h.w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun reverseWhileDeleteIsInFlightRestoresMembershipWithoutClearingProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var once=true
   h.w.beforeResponse={c->if(c.method=="DELETE" && once){once=false;started.complete(Unit);release.await()}}
   h.rt.enqueue(h.c,h.book,false);started.await();h.rt.enqueue(h.c,h.book,true);release.complete(Unit)
   withContext(Dispatchers.IO){withTimeout(15000){h.rt.running.first{it.isEmpty() && h.rt.intents.get(h.book.sourceId,h.book.id)?.present==true}}}
   assertTrue(h.book.id in h.w.members);assertEquals(31,h.st.position(h.book.id)!!.offset);assertTrue(h.downloads.cache(h.book.sourceId).status(h.book.id,true).complete)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun explicitCancelAfterSendCompensatesToTheObservedBaselineWithoutDataDeletion()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var once=true
   h.w.beforeResponse={c->if(c.method=="DELETE" && once){once=false;started.complete(Unit);release.await()}}
   h.rt.enqueue(h.c,h.book,false);started.await();assertTrue(h.rt.cancel(h.book));release.complete(Unit)
   withContext(Dispatchers.IO){withTimeout(15000){h.rt.running.first{it.isEmpty()}}};assertTrue(h.book.id in h.w.members);assertEquals(31,h.st.position(h.book.id)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun legacyHiddenAndV2FailuresAndLoggedOutEditsNeverBecomeNewCloudIntents()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.st.hideFromShelf(setOf(h.book.id));h.st.saveShelfOperation(h.book.id,ShelfOperationRecord(false,"FAILED",1));h.rt.onLogin(h.c)
   assertTrue(h.rt.intents.all().isEmpty());assertTrue(h.w.calls.isEmpty());h.rt.cancelLocal(h.book);now+=ShelfIntents.DAY;h.rt.runScheduled(true);assertEquals(0,h.w.mutationCount);assertEquals(31,h.st.position(h.book.id)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun dailyJobIsPersistedNetworkConstrainedAndDoesNotScanTheLibrary()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();val info=h.rt.dailyJobInfo();assertTrue(info.isPeriodic);assertTrue(info.isPersisted);assertEquals(ShelfIntents.DAY,info.intervalMillis);assertEquals(JobInfo.NETWORK_TYPE_ANY,info.networkType)
   h.rt.runScheduled(true);assertEquals(1,h.w.calls.size);assertTrue(h.w.calls.single().path.endsWith("/me/bookshelf"));assertEquals(0,h.w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun cleanupCrashMarkerPreventsAnOldCompletedIntentFromClearingANewDownload()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.w.network.online=false;h.rt.enqueue(h.c,h.book,false);val r=h.rt.intents.get(h.book.sourceId,h.book.id)!!;h.rt.intents.save(r.copy(state="APPLYING",due=now))
   h.st.requestCleanup(h.book.id,remoteHide=true,cleanupId=r.nonce);h.downloads.eraseAndCancel(r.owner,r.book.id,r.nonce);h.downloads.finishErase(r.owner,r.book.id,r.nonce);seed(h);h.st.save(h.book.id,"new-after-completed-cleanup",77)
   h.rt.runScheduled(false);assertEquals("DONE",h.rt.intents.get(r.owner,r.book.id)!!.state);assertTrue(h.downloads.cache(r.owner).status(r.book.id,true).complete);assertEquals(77,h.st.position(r.book.id)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun localOnlyEditAfterLogoutCancelsPendingCloudWorkInsteadOfReplayingItAtLogin()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.w.network.online=false;h.rt.enqueue(h.c,h.book,false);state(h,setOf("PENDING"));h.rt.deactivate();h.vault.clear();h.rt.cancelLocal(h.book);h.st.requestCleanup(h.book.id,true);h.st.finishCleanup(h.book.id)
   h.w.network.online=true;val own=h.w.client();h.vault.value=own.snapshot();h.rt.activate(own,true);h.rt.onLogin(own);assertEquals("CANCELLED",h.rt.intents.get(h.book.sourceId,h.book.id)!!.state);assertEquals(0,h.w.mutationCount);assertTrue(h.book.id in h.w.members)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun consecutiveFailuresBackOffAndAreBudgetedBeforeADailyRecovery()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.w.responseOverride={c->if(c.method=="DELETE")throw SourceProblem(FailureKind.NETWORK,"synthetic")else null};h.rt.enqueue(h.c,h.book,false);state(h,setOf("PENDING"))
   repeat(6){val r=h.rt.intents.get(h.book.sourceId,h.book.id)!!;if(r.state!="CAPPED"){now=r.due;h.rt.process(h.c)}}
   val capped=h.rt.intents.get(h.book.sourceId,h.book.id)!!;assertEquals("CAPPED",capped.state);val calls=h.w.calls.size;h.rt.runScheduled(true);assertEquals(calls,h.w.calls.size);assertEquals(31,h.st.position(h.book.id)!!.offset)
   now=capped.due+1;h.w.responseOverride=null;h.rt.runScheduled(true);state(h,setOf("DONE"));assertNull(h.st.position(h.book.id))
  }finally{Dispatchers.resetMain()}
 }

 @Test fun foregroundAndScheduledRunsShareOneMutationAndCannotRepeatCommittedCleanup()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();seed(h);val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var once=true
   h.w.beforeResponse={c->if(c.method=="DELETE" && once){once=false;started.complete(Unit);release.await()}}
   h.rt.enqueue(h.c,h.book,false);started.await();val bg=async{h.rt.runScheduled(true)};val fg=async{h.rt.onLogin(h.c)};release.complete(Unit);bg.await();fg.await();state(h,setOf("DONE"))
   assertEquals(1,h.w.mutationCount);assertNull(h.st.position(h.book.id));assertNull(h.downloads.cache(h.book.sourceId).manifest(h.book.id));assertTrue(h.rt.running.value.isEmpty())
  }finally{Dispatchers.resetMain()}
 }

 @Test fun successfulNewAddRetainsAutomaticWifiQueueWithoutDuplicatingOrIgnoringDisabledPreference()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=setup();h.st.hideFromShelf(setOf(h.book.id));h.w.members.remove(h.book.id);h.downloads.setAutomaticWifi(true);h.w.network.wifi=false
   h.rt.enqueue(h.c,h.book,true);state(h,setOf("DONE"));val r=h.downloads.request(h.downloads.key(h.book.sourceId,h.book.id))!!;assertTrue(r.automatic);assertFalse(r.allowMetered);assertEquals(h.book.sourceId,r.owner);assertEquals("等待 Wi-Fi",r.message)
   h.rt.onLogin(h.c);assertEquals(r.nonce,h.downloads.request(r.key)!!.nonce);assertEquals(31,h.st.position(h.book.id)!!.offset)
   val other=h.book.copy(id="public:2");h.downloads.setAutomaticWifi(false);h.rt.enqueue(h.c,other,true)
   withContext(Dispatchers.IO){withTimeout(15000){h.rt.revision.first{h.rt.intents.get(other.sourceId,other.id)?.state=="DONE"}}};assertNull(h.downloads.request(h.downloads.key(other.sourceId,other.id)))
   h.downloads.setAutomaticWifi(true);h.w.detailOverride={id,j->if(id=="public:3")j.put("format","PDF")else j};val unsupported=h.book.copy(id="public:3",format="PDF");h.rt.enqueue(h.c,unsupported,true)
   withContext(Dispatchers.IO){withTimeout(15000){h.rt.revision.first{h.rt.intents.get(unsupported.sourceId,unsupported.id)?.state=="DONE"}}};assertNull(h.downloads.request(h.downloads.key(unsupported.sourceId,unsupported.id)))
  }finally{Dispatchers.resetMain()}
 }

}
