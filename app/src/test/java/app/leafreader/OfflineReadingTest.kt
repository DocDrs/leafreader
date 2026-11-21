package app.leafreader

import android.app.Application
import android.app.job.JobInfo
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineReadingTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun cache(c:BookHubClient,name:String="store")=OfflineStore(File(app.noBackupFilesDir,name),BookHubSource.ownerId(c))
    private suspend fun code(action:suspend()->Unit)=try{action();"SUCCESS"}catch(e:SourceProblem){e.diagnostic}
    @Test fun wholePackageColdOfflineHasDirectoryActualPictureAndAllChaptersWithZeroHttp()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c)
        BookDownloader(c,store,w.network).download("public:1",false)
        assertTrue(store.status("public:1").complete);assertEquals(5,store.status("public:1").saved)
        assertEquals(1,w.calls.count{it.startsWith("POST")});assertEquals(1,w.calls.count{it.contains("reading-package/content")});assertFalse(w.calls.any{it.contains("/resources/")})
        w.network.online=false;w.network.wifi=false;val before=w.calls.size
        val cold=BookHubSource(w.client(),cache(c),w.network);val refs=cold.chapters("public:1")
        val initial=cold.initialReading("public:1",refs);assertEquals(refs[0].id,initial.chapter.id);assertEquals(1,initial.chapter.graphics.size)
        for(r in refs)assertTrue(cold.chapter("public:1",r.id).text.isNotEmpty())
        assertEquals(before,w.calls.size)
        val manifest=store.manifest("public:1")!!.toString();assertFalse(manifest.contains("ticket="))
    }
    @Test fun readingAndWholeDownloadShareFilesAndPartialColdReadingIsExplicit()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);val source=BookHubSource(c,store,w.network)
        val refs=source.chapters("public:1");val read=source.chapter("public:1",refs[1].id)
        assertEquals("部分离线",store.status("public:1").label);assertFalse(store.status("public:1").complete)
        w.network.online=false;val before=w.calls.size;val cold=BookHubSource(w.client(),cache(c),w.network)
        val again=cold.chaptersFor("public:1",refs[1].id);assertEquals(read.text,cold.chapter("public:1",again[1].id).text)
        assertEquals("BH_OFFLINE_MISSING",code{cold.chapter("public:1",again[3].id)})
        assertEquals(before,w.calls.size)
        w.network.online=true;BookDownloader(c,store,w.network).download("public:1",false);assertTrue(store.status("public:1").complete)
    }
    @Test fun absentPackageFallsBackButForbiddenDoesNot()=runBlocking {
        val w=OfflineFixtures().apply{bundleStatus=404};val c=w.client();val store=cache(c)
        BookDownloader(c,store,w.network).download("public:1",false);assertTrue(store.status("public:1").complete)
        assertEquals(5,w.calls.count{it.contains("/resources/")})
        val denied=OfflineFixtures().apply{bundleStatus=403};val dc=denied.client();val ds=cache(dc,"denied")
        assertEquals("BH_HTTP_403",code{BookDownloader(dc,ds,denied.network).download("public:1",false)})
        assertFalse(ds.status("public:1").complete);assertFalse(denied.calls.any{it.contains("/resources/")})
    }
    @Test fun badHashUnsafePackageAndLowSpaceNeverBecomeComplete()=runBlocking {
        for(unsafe in listOf(false,true)) {
            val w=OfflineFixtures().apply{unsafeZip=unsafe;wrongHash=!unsafe};val c=w.client();val store=cache(c,"invalid-$unsafe")
            assertTrue(code{BookDownloader(c,store,w.network).download("public:1",false)}.startsWith("BH_DOWNLOAD_"));assertFalse(store.status("public:1").complete)
        }
        val w=OfflineFixtures();val c=w.client();val noSpace=OfflineStore(File(app.noBackupFilesDir,"full"),BookHubSource.ownerId(c)){0L}
        try{BookDownloader(c,noSpace,w.network).download("public:1",false);fail()}catch(e:java.io.IOException){assertTrue(e.message!!.contains("空间"))}
        assertFalse(noSpace.status("public:1").complete)
    }
    @Test fun interruptedInstallKeepsVerifiedPartialResourcesAndRetryCompletes()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);val access=w.manifest();val epoch=store.generation("public:1");store.saveManifest("public:1",access,epoch)
        val f=File(app.cacheDir,"package.zip").apply{writeBytes(w.packageBytes())};var valid=true
        try{ReadingPackage.install(f,access,store,epoch,{valid}){_,_->valid=false};fail()}catch(_:DownloadStopped){}
        assertEquals(1,store.status("public:1").saved);assertFalse(store.status("public:1").complete)
        BookDownloader(c,cache(c),w.network).download("public:1",false);assertTrue(store.status("public:1").complete)
        assertFalse(File(app.noBackupFilesDir,"store").walkTopDown().any{it.isFile&&it.name.startsWith("tmp-")})
    }
    @Test fun versionsStaySeparateAndOldChapterResumeCanUseOldVersionOffline()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c)
        BookDownloader(c,store,w.network).download("public:1",false)
        val first=BookHubSource(c,store,w.network);val oldRefs=first.chapters("public:1");val oldText=first.chapter("public:1",oldRefs[1].id).text
        w.version="offline-fixture-v2";BookDownloader(c,store,w.network).download("public:1",false)
        w.accessStatus=503;val unavailable=BookHubSource(c,cache(c),w.network)
        unavailable.chaptersFor("public:1",oldRefs[1].id);assertEquals(oldText,unavailable.chapter("public:1",oldRefs[1].id).text)
        w.accessStatus=200
        w.network.online=false;val cold=BookHubSource(w.client(),cache(c),w.network)
        cold.chaptersFor("public:1",oldRefs[1].id);assertEquals(oldText,cold.chapter("public:1",oldRefs[1].id).text)
        val newRefs=cold.chapters("public:1");assertNotEquals(oldRefs[1].id,newRefs[1].id);assertTrue(cold.chapter("public:1",newRefs[1].id).text.contains("fixture-v2"))
    }
    @Test fun equalBookIdAcrossAccountsAndOriginsNeverSharesBytesOrJobs()=runBlocking {
        val w=OfflineFixtures();val a=w.client("account-A");val b=w.client("account-B");val manager=OfflineDownloads(app,w.network,MemorySessionVault(),false)
        val ca=manager.cache(BookHubSource.ownerId(a));BookDownloader(a,ca,w.network).download("public:1",false)
        val cb=manager.cache(BookHubSource.ownerId(b));assertEquals(0,cb.status("public:1").saved)
        w.network.online=false;assertEquals("BH_OFFLINE_MISSING",code{BookHubSource(b,cb,w.network).chapters("public:1")})
        val other=w.client("account-A","https://other-offline.fixture.invalid");assertEquals(0,manager.cache(BookHubSource.ownerId(other)).status("public:1").saved)
        try{BookHubSource(b,ca,w.network);fail()}catch(_:IllegalArgumentException){}
    }
    @Test fun realAuthFailureNeverUsesCachedContentsAndLogoutDisablesOfflineSource()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);BookDownloader(c,store,w.network).download("public:1",false)
        w.accessStatus=401;val source=BookHubSource(c,store,w.network);assertEquals("BH_HTTP_401",code{source.chapters("public:1")})
        w.network.online=false;assertEquals("BH_OFFLINE_AUTH",code{source.chapters("public:1")});assertTrue(store.status("public:1").complete)
    }
    @Test fun clearWhileResourceResponseIsDelayedCannotRestoreFilesOrChangeProgress()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);val source=BookHubSource(c,store,w.network)
        val refs=source.chapters("public:1");val progress=ReadingStore(app,"clear-race");progress.save("public:1",refs[1].id,13);val saved=progress.position("public:1")
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        w.onResponse={path->if(path.endsWith("/a")){started.complete(Unit);release.await()}}
        val reading=async{source.chapter("public:1",refs[1].id)};started.await();source.forgetDownloaded("public:1");release.complete(Unit);reading.await()
        assertEquals(OfflineStatus(),cache(c).status("public:1"));assertNull(cache(c).manifest("public:1"));assertEquals(saved,progress.position("public:1"))
        w.onResponse=null;BookDownloader(c,store,w.network).download("public:1",false);assertTrue(store.status("public:1").complete);assertEquals(saved,progress.position("public:1"))
    }
    @Test fun clearWhileWholeResponseIsDelayedAndAccountSwitchDoNotPublishLateCompletion()=runBlocking {
        val w=OfflineFixtures();val a=w.client("account-A");val manager=OfflineDownloads(app,w.network,MemorySessionVault(),false);manager.activate(a)
        val owner=BookHubSource.ownerId(a);val book=BookHubFixtures.book().let{Book("public:1","队列样书","自写","","",4,0,sourceId=owner)}
        assertTrue(manager.enqueue(owner,book));val request=manager.request(manager.key(owner,book.id))!!
        val start=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.onResponse={path->if(path.endsWith("/content")){start.complete(Unit);release.await()}}
        val task=async {try{BookDownloader(a,manager.cache(owner),w.network).download(book.id,false,{manager.valid(request,a)});"COMPLETE"}catch(_:DownloadStopped){"STOPPED"}}
        start.await();manager.cancel(owner,book.id);manager.cache(owner).clear(book.id)
        val b=w.client("account-B");manager.activate(b);release.complete(Unit);assertEquals("STOPPED",task.await())
        manager.update(request,"COMPLETE","整本已下载");assertFalse(manager.cache(owner).status(book.id).complete);assertFalse(manager.cache(BookHubSource.ownerId(b)).status(book.id).complete)
        assertEquals("CANCELLED",manager.request(request.key)!!.state)
        w.onResponse=null;manager.activate(a);assertTrue(manager.enqueue(owner,book));val next=manager.request(request.key)!!
        BookDownloader(a,manager.cache(owner),w.network).download(book.id,false,{manager.valid(next,a)});assertTrue(manager.cache(owner).status(book.id).complete)
    }
    @Test fun wifiPolicyBlocksCellularAndMidRequestNetworkSwitchWithoutAnySilentAllowance()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);w.network.wifi=false
        try{BookDownloader(c,store,w.network).download("public:1",false);fail()}catch(_:DownloadStopped){}
        assertTrue(w.calls.isEmpty())
        w.network.wifi=true;w.onResponse={path->if(path.endsWith("/content"))w.network.wifi=false}
        try{BookDownloader(c,store,w.network).download("public:1",false);fail()}catch(_:DownloadStopped){}
        assertFalse(store.status("public:1").complete)
        w.onResponse=null;BookDownloader(c,store,w.network).download("public:1",true);assertTrue(store.status("public:1").complete)
    }
    @Test fun queuePersistsWifiDefaultAndOnlyExplicitNewAddsAreEnqueued() {
        val w=OfflineFixtures();val c=w.client();val manager=OfflineDownloads(app,w.network,MemorySessionVault(),false);manager.activate(c)
        val owner=BookHubSource.ownerId(c);assertTrue(manager.automaticWifi());assertNull(manager.request(manager.key(owner,"public:1")))
        val book=Book("public:1","自写","","","",1,0,sourceId=owner);assertTrue(manager.enqueue(owner,book,automatic=true))
        val request=manager.request(manager.key(owner,book.id))!!;val job=manager.jobInfo(request)
        assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,job.networkType);assertTrue(job.isPersisted);assertTrue(job.isRequireStorageNotLow)
        manager.setAutomaticWifi(false);assertEquals("CANCELLED",manager.request(request.key)!!.state)
        val cold=OfflineDownloads(app,w.network,MemorySessionVault(),false);assertFalse(cold.automaticWifi());cold.activate(c)
        assertFalse(cold.enqueue(owner,book,automatic=true));assertTrue(cold.enqueue(owner,book,allowMetered=true))
        assertEquals(JobInfo.NETWORK_TYPE_ANY,cold.jobInfo(cold.request(request.key)!!).networkType)
    }
    @Test fun actualTransportCapabilitiesDistinguishUnmeteredCellularFromWifiAndReactToSwitches() {
        val cm=app.getSystemService(android.net.ConnectivityManager::class.java);val shadow=org.robolectric.Shadows.shadowOf(cm)
        val active=cm.activeNetwork!!;val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();val shadowCaps=org.robolectric.Shadows.shadowOf(caps)
        shadowCaps.clearCapabilities();shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowCaps.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);shadowCaps.addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        shadow.setNetworkCapabilities(active,caps);val state=AndroidConnectionState(app)
        assertTrue(state.online);assertFalse(state.wifi)
        shadowCaps.removeTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR);shadowCaps.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        assertTrue(state.wifi);shadowCaps.removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);assertFalse(state.wifi)
        shadow.setActiveNetworkInfo(null);assertFalse(state.online);assertFalse(state.wifi)
    }
    @Test fun clearingWhileAccessIsDelayedInvalidatesTheEntireOldReadingOperation()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);val source=BookHubSource(c,store,w.network)
        val start=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.onResponse={p->if(p.endsWith("/access")){start.complete(Unit);release.await()}}
        val task=async{source.chapters("public:1")};start.await();source.forgetDownloaded("public:1");release.complete(Unit)
        val refs=task.await();source.chapter("public:1",refs[1].id)
        assertEquals(OfflineStatus(),cache(c).status("public:1"));assertNull(cache(c).manifest("public:1"))
        w.onResponse=null;w.network.online=false
        assertEquals("BH_OFFLINE_MISSING",code{BookHubSource(w.client(),cache(c),w.network).chapters("public:1")})
        w.network.online=true;BookDownloader(c,store,w.network).download("public:1",false);assertTrue(store.status("public:1").complete)
    }
    @Test fun coldQueueUsesOnlyMatchingVaultSessionAndCanContinueWithoutPassword()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val owner=BookHubSource.ownerId(c);val vault=MemorySessionVault().apply{value=c.snapshot()}
        val first=OfflineDownloads(app,w.network,vault,false);first.activate(c)
        val book=Book("public:1","自写","","","",4,0,sourceId=owner);first.enqueue(owner,book)
        val req=first.request(first.key(owner,book.id))!!
        val cold=OfflineDownloads(app,w.network,vault,false){session->w.client(session.accountId,session.origin)}
        val restored=cold.resolveClient(req)!!;assertTrue(cold.valid(req,restored));assertEquals(req.nonce,cold.jobInfo(req).extras.getString("nonce"))
        BookDownloader(restored,cold.cache(owner),w.network).download(book.id,false,{cold.valid(req,restored)})
        cold.update(req,"COMPLETE","整本已下载");assertEquals("整本已下载",cold.label(owner,book.id))
        val wrongVault=MemorySessionVault().apply{value=w.client("another-account").snapshot()}
        assertNull(OfflineDownloads(app,w.network,wrongVault,false){s->w.client(s.accountId,s.origin)}.resolveClient(req))
    }
    @Test fun sameVersionIgnoresResourceListOrderButRejectsChangedNavigation()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);val first=w.manifest();val epoch=store.generation("public:1")
        store.saveManifest("public:1",first,epoch)
        val shuffled=w.manifest();val array=shuffled.getJSONArray("resources");shuffled.put("resources",org.json.JSONArray((0 until array.length()).reversed().map{array.getJSONObject(it)}));shuffled.put("expiresAt","2098-01-01T00:00:00Z")
        assertTrue(store.saveManifest("public:1",shuffled,epoch))
        val changed=w.manifest();changed.getJSONArray("toc").getJSONObject(1).put("href","/api/v1/books/public%3A1/resources/a#different")
        assertEquals("BH_OFFLINE_VERSION",code{store.saveManifest("public:1",changed,epoch)})
        assertFalse(store.manifest("public:1")!!.toString().contains("#different"))
    }
    @Test fun unreachableBackendUsesSavedDataWithoutRenewingOrSendingExpiredTicketlessRequests()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);BookDownloader(c,store,w.network).download("public:1",false)
        val expired=w.manifest().put("expiresAt","2020-01-01T00:00:00Z");store.saveManifest("public:1",expired,store.generation("public:1"))
        w.accessStatus=503;val before=w.calls.size;val source=BookHubSource(c,store,w.network);val refs=source.chapters("public:1")
        assertEquals(1,source.initialReading("public:1",refs).chapter.graphics.size)
        assertEquals(before+1,w.calls.size)
        store.evict("public:1",w.version,"c")
        assertEquals("BH_OFFLINE_MISSING",code{source.chapter("public:1",refs.last().id)})
        assertEquals(before+1,w.calls.size)
    }
    @Test fun processDeathAfterClearTombstoneBeforeDeletionCannotReviveCompletedFiles()=runBlocking {
        val w=OfflineFixtures();val c=w.client();val store=cache(c);BookDownloader(c,store,w.network).download("public:1",false)
        assertTrue(store.status("public:1").complete)
        // Fault injection: the durable invalidation committed, then the process died before file removal.
        File(File(File(app.noBackupFilesDir,"store"),sha("public:1".toByteArray())),"epoch").writeText("1")
        val cold=cache(c);assertEquals(OfflineStatus(),cold.status("public:1"));assertNull(cold.manifest("public:1"))
        val requests=w.calls.count{it.contains("reading-package/content")};BookDownloader(c,cold,w.network).download("public:1",false)
        assertTrue(cold.status("public:1").complete);assertEquals(requests+1,w.calls.count{it.contains("reading-package/content")})
    }
    @Test fun wifiLossAlsoPreventsAnAutomaticCredentialRefreshFromTheDownload()=runBlocking {
        val w=OfflineFixtures().apply{accessStatus=401;accessError="SESSION_EXPIRED"};val c=w.client();val store=cache(c)
        w.onResponse={path->if(path.endsWith("/access"))w.network.wifi=false}
        try{BookDownloader(c,store,w.network).download("public:1",false);fail()}catch(_:DownloadStopped){}
        assertEquals(1,w.calls.size);assertFalse(w.calls.any{it.contains("/auth/refresh")});assertFalse(store.status("public:1").complete)
    }
}
