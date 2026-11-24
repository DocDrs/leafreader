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
class Patch111Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private class Harness(val w:ShelfSyncFixtures,val vault:MemorySessionVault,val vm:ReaderViewModel)
 private suspend fun start(w:ShelfSyncFixtures=ShelfSyncFixtures(),local:String="public:2"):Harness {
  val b=w.book(local);ReadingStore(app,b.sourceId).apply{addToShelf(b);save(b.id,"kept-anchor",37)}
  val vault=MemorySessionVault();val rt=OfflineDownloads(app,w.network,vault,false).apply{setAutomaticWifi(false)}
  val vm=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=rt)
  vm.connect(w.session.origin,w.session.accountId,"synthetic")
  withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.connecting && !it.shelfLoading}}}
  return Harness(w,vault,vm)
 }
 private suspend fun refresh(vm:ReaderViewModel) {vm.refreshShelf();withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.syncingShelf && !it.shelfLoading}}}}
 private suspend fun operation(vm:ReaderViewModel,b:Book){withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!vm.membershipBusy(b) && b.key !in it.clearingBooks}}}}
 @Test fun oneSuccessfulLoginPromptsAtMostOnceAndDeferralSurvivesRefreshTabsAndColdRestore()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=start();val vm=h.vm;val w=h.w;val first=vm.ui.value.shelfDifference!!.token
   assertTrue(vm.ui.value.showShelfChoice);vm.postponeShelfDifference();assertNull(vm.ui.value.shelfDifference)
   repeat(2){refresh(vm);vm.selectTab("settings");vm.selectTab("shelf");assertFalse(vm.ui.value.showShelfChoice);assertNull(vm.ui.value.shelfDifference)}
   assertEquals(0,w.mutationCount);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
   val calls=w.calls.size;val cold=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=h.vault,offlineRuntime=OfflineDownloads(app,w.network,h.vault,false));cold.ui.first{!it.shelfLoading};assertFalse(cold.ui.value.showShelfChoice);assertEquals(calls,w.calls.size)
   vm.connect(w.session.origin,w.session.accountId,"synthetic");withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{it.shelfDifference!=null}}};assertTrue(vm.ui.value.showShelfChoice);assertTrue(vm.ui.value.shelfDifference!!.token>first);assertEquals(0,w.mutationCount)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun identicalShelvesDoNotPromptOrShowSuccessFeedback()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=start(local="public:1");assertFalse(h.vm.ui.value.showShelfChoice);assertFalse(h.vm.ui.value.shelfChoicePending);assertNull(h.vm.ui.value.shelfDifference);assertNull(h.vm.ui.value.notice);assertNull(h.vm.ui.value.membershipFeedback);assertEquals(0,h.w.mutationCount)
   refresh(h.vm);assertFalse(h.vm.ui.value.showShelfChoice);assertEquals(37,h.vm.positionOf(h.w.book())!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun ordinaryAddsAndRemovesStillSyncAfterDeferralWithoutReplayingOtherDifferences()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures().apply{members+="public:2"};val h=start(w);val vm=h.vm;vm.postponeShelfDifference()
   val fresh=w.book("public:4");vm.addToShelf(fresh);operation(vm,fresh);assertTrue(vm.isOnShelf(fresh));assertTrue(fresh.id in w.members);assertNull(vm.ui.value.membershipFeedback);assertNull(vm.ui.value.notice);assertFalse(vm.ui.value.showShelfChoice)
   val old=w.book("public:2");vm.removeFromShelf(setOf(old.key),vm.ui.value.connectionRevision);operation(vm,old);assertFalse(vm.isOnShelf(old));assertNull(vm.positionOf(old));assertFalse(old.id in w.members);assertTrue("public:1" in w.members);assertFalse(vm.isOnShelf(w.book()));assertEquals(2,w.mutationCount);assertFalse(vm.ui.value.showShelfChoice)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun searchAfterDeferralFiltersKnownRemoteMembersAndAllowsOnlyExplicitNewAddition()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=start();val vm=h.vm;val w=h.w;vm.postponeShelfDifference()
   w.responseOverride={call->if(call.path.endsWith("/catalog/search"))WireResponse(200,BookHubFixtures.envelope(JSONObject().put("items",JSONArray().put(BookHubFixtures.book("public:1")).put(BookHubFixtures.book("public:2")).put(BookHubFixtures.book("public:4"))).put("hasMore",false)).toByteArray())else null}
   vm.search("自写");withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.findLoading && it.books.isNotEmpty()}}}
   assertEquals(listOf("public:4"),vm.ui.value.books.map{it.id});assertTrue(vm.canAddBook(vm.ui.value.books.single()));assertEquals(0,w.mutationCount);assertFalse(vm.ui.value.showShelfChoice)
   // An explicit local hidden flag still permits a deliberate per-book re-add, without re-opening global choice.
   ReadingStore(app,w.book().sourceId).hideFromShelf(setOf("public:1"));vm.search("自写");withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{!it.findLoading && it.books.isNotEmpty()}}}
   assertEquals(setOf("public:1","public:4"),vm.ui.value.books.map{it.id}.toSet());val hidden=vm.ui.value.books.first{it.id=="public:1"};vm.addToShelf(hidden);operation(vm,hidden);assertTrue(vm.isOnShelf(hidden));assertEquals(0,w.mutationCount);assertFalse(vm.ui.value.showShelfChoice)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun failedLoginCheckGivesShortFailureAndDoesNotPromptLaterInSameLogin()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures();w.pageOverride={JSONObject().put("items",JSONArray()).put("hasMore",true)};val h=start(w);val vm=h.vm
   assertNull(vm.ui.value.shelfDifference);assertEquals("书架检查失败，请重新登录后重试",vm.ui.value.notice);assertTrue(vm.isOnShelf(w.book("public:2")))
   vm.dismissNotice();w.pageOverride=null;refresh(vm);assertFalse(vm.ui.value.showShelfChoice);assertNull(vm.ui.value.shelfDifference);assertEquals(0,w.mutationCount);assertEquals(37,vm.positionOf(w.book("public:2"))!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun failureMessageIsShortAndStillPreservesBookAndExactProgress()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val h=start(local="public:1");h.w.failure=409;val b=h.w.book();h.vm.removeFromShelf(setOf(b.key),h.vm.ui.value.connectionRevision);operation(h.vm,b)
   assertEquals("书架修订冲突，请重试",h.vm.ui.value.notice);assertTrue(h.vm.ui.value.notice!!.length<20);assertTrue(h.vm.isOnShelf(b));assertEquals(37,h.vm.positionOf(b)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
 @Test fun multiPageLoginWaitsForFinalPageAndDoesNotPromptAgainAfterDeferral()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler));try {
   val w=ShelfSyncFixtures().apply{members.addAll(listOf("public:3","public:5"));pageSize=1};val reached=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var delayed=false
   w.beforeResponse={c->if(!delayed && c.path.endsWith("/me/bookshelf") && c.query?.contains("cursor=2")==true){delayed=true;reached.complete(Unit);release.await()}}
   val b=w.book("public:2");ReadingStore(app,b.sourceId).addToShelf(b);val vault=MemorySessionVault();val vm=ReaderViewModel(app,connectionGate=w.gate(),sessionVault=vault,offlineRuntime=OfflineDownloads(app,w.network,vault,false))
   vm.connect(w.session.origin,w.session.accountId,"synthetic");reached.await();assertFalse(vm.ui.value.showShelfChoice);assertNull(vm.ui.value.shelfDifference);release.complete(Unit)
   withContext(Dispatchers.IO){withTimeout(15000){vm.ui.first{it.showShelfChoice}}};assertEquals(setOf("public:1","public:3","public:5"),vm.ui.value.shelfDifference!!.removeRemote.map{it.id}.toSet());vm.postponeShelfDifference();refresh(vm);assertFalse(vm.ui.value.showShelfChoice);assertEquals(0,w.mutationCount);assertTrue(vm.isOnShelf(b))
  }finally{Dispatchers.resetMain()}
 }

}
