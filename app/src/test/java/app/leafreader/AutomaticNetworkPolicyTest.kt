package app.leafreader

import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class AutomaticNetworkPolicyTest {
 private val app=ApplicationProvider.getApplicationContext<Application>()
 private class Network:ConnectionState {@Volatile var up=true;@Volatile var strictWifi=false;override val online get()=up;override val wifi get()=up&&strictWifi}
 private inner class Source(override val id:String):IndependentBookSource {
  override val label="自写网络测试";override val independentOffline=true;override val supportsAutomaticDownload=true
  val book=Book("same-id","自写书","","","",1,0,sourceId=id)
  val file=File(app.noBackupFilesDir,"synthetic-network/$id/body");var calls=0
  val entered=CompletableDeferred<Unit>();var hold:CompletableDeferred<Unit>?=null
  override suspend fun thumbnail(book:String):android.graphics.Bitmap?=null
  override suspend fun books()=listOf(book)
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("raw","自写章"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter("raw","自写章",listOf("自写正文"))
  override suspend fun status(book:String)=OfflineStatus(if(file.exists())1 else 0,1,file.exists())
  override suspend fun clear(book:String){file.delete()}
  override suspend fun download(book:String,allowMetered:Boolean){assertTrue("automatic path uses the live guard rather than a stale captured choice",allowMetered);calls++;entered.complete(Unit);hold?.let{withContext(NonCancellable){it.await()}};checkDownloadPolicy();file.parentFile!!.mkdirs();file.writeText("self-authored bytes")}
 }
 private fun shelf(s:Source){ReadingStore(app,s.id).addToShelf(s.book);ReadingStore(app,s.id).save(s.book.id,"raw",53)}
 @Test fun upgradeKeepsEnabledAndDefaultsStrictWithoutBackfillingExistingShelf() {
  val prefs=app.getSharedPreferences("global-auto-download-v1",0);prefs.edit().putBoolean("enabled",true).remove("wifi-only").commit()
  val policy=GlobalDownloadPreferences(app);assertTrue(policy.enabled());assertTrue(policy.wifiOnly())
  val source=Source("old-source");shelf(source);assertTrue(AutomaticSourceDownloads(app,Network(),false){source}.requests().isEmpty())
  policy.setWifiOnly(false);policy.set(false);val cold=GlobalDownloadPreferences(app);assertFalse(cold.enabled());assertFalse(cold.wifiOnly());assertFalse(cold.networkAllowed(Network()))
  cold.set(true);assertTrue(cold.networkAllowed(Network()));assertEquals(53,ReadingStore(app,source.id).position(source.book.id)!!.offset)
 }
 @Test fun actualMeteredWifiCellularAndEthernetNeedExplicitRelaxationAndEnabledMaster() {
  val cm=app.getSystemService(android.net.ConnectivityManager::class.java);val shadow=org.robolectric.Shadows.shadowOf(cm);val active=cm.activeNetwork!!
  val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();val sc=org.robolectric.Shadows.shadowOf(caps);sc.clearCapabilities();sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);shadow.setNetworkCapabilities(active,caps)
  val policy=GlobalDownloadPreferences(app);policy.set(true);val connection=AndroidConnectionState(app)
  for(transport in listOf(android.net.NetworkCapabilities.TRANSPORT_WIFI,android.net.NetworkCapabilities.TRANSPORT_CELLULAR,android.net.NetworkCapabilities.TRANSPORT_ETHERNET)){
   sc.addTransportType(transport);policy.setWifiOnly(true);assertFalse(policy.networkAllowed(connection));policy.setWifiOnly(false);assertTrue(policy.networkAllowed(connection));policy.set(false);assertFalse(policy.networkAllowed(connection));policy.set(true);sc.removeTransportType(transport)
  }
  sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);policy.setWifiOnly(true);assertTrue(policy.networkAllowed(connection))
  sc.removeTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);assertFalse(policy.networkAllowed(connection))
 }
 @Test fun independentWaitingTasksRescheduleSameJobAndColdResumeOnExplicitlyAllowedData()=runBlocking {
  val policy=GlobalDownloadPreferences(app);policy.set(true);val n=Network();val source=Source("public-synthetic");shelf(source)
  val q=AutomaticSourceDownloads(app,n,false){source};assertTrue(q.enqueue(source,source.book));val old=q.request(source.id,source.book.id)!!
  assertTrue(q.execute(old));assertEquals(0,source.calls);assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,q.jobInfo(old).networkType)
  policy.setWifiOnly(false);q.networkPolicyChanged();val next=q.request(source.id,source.book.id)!!
  assertEquals(old.jobId,next.jobId);assertNotEquals(old.nonce,next.nonce);assertEquals(JobInfo.NETWORK_TYPE_ANY,q.jobInfo(next).networkType);assertFalse(q.execute(old))
  val cold=AutomaticSourceDownloads(app,n,false){source};assertEquals(next,cold.request(source.id,source.book.id));assertFalse(cold.execute(next));assertEquals(1,source.calls);assertEquals(1,cold.requests().size);assertEquals("COMPLETE",cold.request(source.id,source.book.id)!!.state);assertEquals(53,ReadingStore(app,source.id).position(source.book.id)!!.offset)
 }
 @Test fun restoringRestrictionDuringIndependentTransferStopsOldWritesAndDoesNotDuplicateIntent()=runBlocking {
  val policy=GlobalDownloadPreferences(app);policy.set(true);policy.setWifiOnly(false);val n=Network();val source=Source("running-synthetic");shelf(source)
  val q=AutomaticSourceDownloads(app,n,false){source};q.enqueue(source,source.book);val old=q.request(source.id,source.book.id)!!;source.hold=CompletableDeferred()
  val task=launch(Dispatchers.Default){q.execute(old)};withTimeout(5000){source.entered.await()}
  policy.setWifiOnly(true);q.networkPolicyChanged();val next=q.request(source.id,source.book.id)!!;assertTrue(q.execute(next));source.hold!!.complete(Unit);task.join()
  assertFalse(source.file.exists());assertNotEquals(old.nonce,next.nonce);assertEquals(old.jobId,next.jobId)
  assertTrue(q.execute(next));assertEquals(1,source.calls);assertFalse(q.enqueue(source,source.book));assertEquals(1,q.requests().size)
  n.strictWifi=true;source.hold=null;assertFalse(q.execute(next));assertEquals("COMPLETE",q.request(source.id,source.book.id)!!.state);q.pause(old);assertEquals("COMPLETE",q.request(source.id,source.book.id)!!.state);assertEquals(53,ReadingStore(app,source.id).position(source.book.id)!!.offset)
 }
 @Test fun bookHubPolicyReschedulesOnlyAutomaticTasksAndPreservesManualNetworkChoicesAndBytes() {
  val f=OfflineFixtures();f.network.wifi=false;val c=f.client();val owner=BookHubSource.ownerId(c);val q=OfflineDownloads(app,f.network,MemorySessionVault(),false);q.activate(c);q.setAutomaticWifi(true)
  fun b(id:String)=Book(id,"自写书","","","",1,0,sourceId=owner)
  assertTrue(q.enqueue(owner,b("public:1"),automatic=true));assertTrue(q.enqueue(owner,b("public:2")));assertTrue(q.enqueue(owner,b("public:3"),allowMetered=true))
  val old=q.request(q.key(owner,"public:1"))!!;val manual=q.request(q.key(owner,"public:2"))!!;val metered=q.request(q.key(owner,"public:3"))!!
  ReadingStore(app,owner).save("public:1","kept",97);val file=File(app.noBackupFilesDir,"offline-v1/kept.partial").apply{parentFile!!.mkdirs();writeText("kept bytes")}
  GlobalDownloadPreferences(app).setWifiOnly(false);q.networkPolicyChanged();val next=q.request(old.key)!!
  assertNotEquals(old.nonce,next.nonce);assertEquals(old.jobId,next.jobId);assertFalse(q.canTransfer(old,c));assertTrue(q.canTransfer(next,c));assertEquals(JobInfo.NETWORK_TYPE_ANY,q.jobInfo(next).networkType)
  runBlocking{try{BookDownloader(c,q.cache(owner),f.network).download(manual.bookId,false,{q.canTransfer(manual,c)});fail("Manual strict choice must remain strict")}catch(_:DownloadStopped){}}
  assertEquals(manual,q.request(manual.key));assertEquals(metered,q.request(metered.key));assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,q.jobInfo(manual).networkType);assertEquals(JobInfo.NETWORK_TYPE_ANY,q.jobInfo(metered).networkType)
  GlobalDownloadPreferences(app).setWifiOnly(true);q.networkPolicyChanged();assertFalse(q.canTransfer(q.request(old.key)!!,c));assertEquals("kept bytes",file.readText());assertEquals(97,ReadingStore(app,owner).position("public:1")!!.offset);assertTrue(f.calls.isEmpty())
 }
 @Test fun bookHubInFlightPackageCannotPublishAfterRestrictionAndWifiResumeKeepsOneQueueRecord()=runBlocking {
  val f=OfflineFixtures();f.network.wifi=false;val c=f.client();val owner=BookHubSource.ownerId(c);val q=OfflineDownloads(app,f.network,MemorySessionVault(),false);q.activate(c);q.setAutomaticWifi(true);GlobalDownloadPreferences(app).setWifiOnly(false)
  val book=Book("public:1","自写包","","","",1,0,sourceId=owner);q.enqueue(owner,book,automatic=true);val old=q.request(q.key(owner,book.id))!!
  val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();f.onResponse={if(it.endsWith("reading-package/content")){entered.complete(Unit);withContext(NonCancellable){release.await()}}}
  val work=async(Dispatchers.Default){try{BookDownloader(c,q.cache(owner),f.network).download(book.id,true,{q.canTransfer(old,c)});false}catch(_:DownloadStopped){true}}
  withTimeout(5000){entered.await()};GlobalDownloadPreferences(app).setWifiOnly(true);q.networkPolicyChanged();val next=q.request(old.key)!!;release.complete(Unit);assertTrue(work.await());assertFalse(q.cache(owner).status(book.id,true).complete)
  q.update(old,"COMPLETE","late");assertEquals("WAITING",q.request(next.key)!!.state)
  val count=f.calls.size;try{BookDownloader(c,q.cache(owner),f.network).download(book.id,true,{q.canTransfer(next,c)});fail()}catch(_:DownloadStopped){};assertEquals(count,f.calls.size)
  f.onResponse=null;f.network.wifi=true;BookDownloader(c,q.cache(owner),f.network).download(book.id,true,{q.canTransfer(next,c)});q.update(next,"COMPLETE","完整缓存");assertTrue(q.cache(owner).status(book.id,true).complete);assertEquals(1,q.requestsForOwner(owner).size)
 }
 @Test fun scheduledConstraintsReplaceRatherThanDuplicateAndNeverResurrectCanceledShelf()=runBlocking {
  val policy=GlobalDownloadPreferences(app);policy.set(true);val n=Network();val s=Source("scheduled-synthetic");shelf(s);val q=AutomaticSourceDownloads(app,n,true){s};q.enqueue(s,s.book)
  val scheduler=app.getSystemService(JobScheduler::class.java);val old=q.request(s.id,s.book.id)!!
  assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,scheduler.getPendingJob(old.jobId)!!.networkType)
  policy.setWifiOnly(false);q.networkPolicyChanged();val current=q.request(s.id,s.book.id)!!;val job=scheduler.getPendingJob(current.jobId)!!
  assertEquals(JobInfo.NETWORK_TYPE_ANY,job.networkType);assertEquals(current.nonce,job.extras.getString("nonce"));assertEquals(1,scheduler.allPendingJobs.count{it.service.className==AutomaticSourceDownloadService::class.java.name})
  policy.setWifiOnly(true);q.networkPolicyChanged();assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,scheduler.getPendingJob(current.jobId)!!.networkType);assertEquals(1,q.requests().size)
  policy.set(false);q.cancelAutomatic();assertNull(scheduler.getPendingJob(current.jobId));policy.set(true);q.scheduleMore();assertNull(scheduler.getPendingJob(current.jobId));assertEquals(0,s.calls)
 }
}
