package app.leafreader

import android.app.Application
import android.app.job.JobInfo
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class GlobalDownloadsTest {
 private val app=ApplicationProvider.getApplicationContext<Application>()
 private class Network:ConnectionState {var up=true;var onWifi=true;override val online get()=up;override val wifi get()=up&&onWifi}
 private inner class Source(override val id:String,override val supportsAutomaticDownload:Boolean=true):IndependentBookSource {
  override val label="自写测试源";override val independentOffline=true
  val file=File(app.noBackupFilesDir,"synthetic-auto/$id/book.bin");var calls=0;var failure:Exception?=null;var hold:CompletableDeferred<Unit>?=null;val entered=CompletableDeferred<Unit>()
  val book=Book("same-book-id","自寫書名","自寫作者","","",1,0xFF536B67,sourceId=id)
  override suspend fun books()=listOf(book)
  override suspend fun chapters(bookId:String)=listOf(ChapterRef("raw-chapter","第一章"))
  override suspend fun chapter(bookId:String,chapterId:String)=Chapter(chapterId,"第一章",listOf("自寫正文"))
  override suspend fun thumbnail(book:String):android.graphics.Bitmap?=null
  override suspend fun status(book:String)=OfflineStatus(if(file.isFile)1 else 0,1,file.isFile)
  override suspend fun clear(book:String){file.delete()}
  override suspend fun download(book:String,allowMetered:Boolean){calls++;entered.complete(Unit);hold?.let{withContext(NonCancellable){it.await()}};checkDownloadPolicy();failure?.let{throw it};file.parentFile!!.mkdirs();file.writeText("only synthetic bytes")}
 }
 private fun shelve(source:Source){assertTrue(ReadingStore(app,source.id).addToShelf(source.book))}
 @Test fun legacyBookHubOnlyOptInDoesNotBecomeGlobalOrEraseManualWorkContentOrProgress() {
  val legacy=app.getSharedPreferences("offline-queue-v1",0);val owner="bookhub-v1-"+"a".repeat(32)
  fun old(id:String,automatic:Boolean):String=JSONObject().put("key",digest("$owner|$id")).put("owner",owner).put("book",id).put("title","自写书")
   .put("job",if(automatic)7000 else 7001).put("nonce","legacy-nonce").put("metered",false).put("auto",automatic).put("state","QUEUED").put("message","等待系统调度").toString()
  legacy.edit().putBoolean("auto-wifi",true).putString("request-"+digest("$owner|public:1"),old("public:1",true)).putString("request-"+digest("$owner|public:2"),old("public:2",false)).commit()
  val store=ReadingStore(app,owner);val book=Book("public:1","旧书","","","",1,0,sourceId=owner);store.addToShelf(book);store.save(book.id,"old-chapter",733)
  val file=File(app.noBackupFilesDir,"offline-v1/legacy-payload.bin").apply{parentFile!!.mkdirs();writeText("retained bytes")}
  val runtime=OfflineDownloads(app,Network(),MemorySessionVault(),false)
  assertFalse(GlobalDownloadPreferences(app).enabled());assertFalse(runtime.automaticWifi())
  assertEquals("CANCELLED",runtime.request(runtime.key(owner,"public:1"))!!.state);assertEquals("QUEUED",runtime.request(runtime.key(owner,"public:2"))!!.state)
  assertEquals("retained bytes",file.readText());assertEquals(733,store.position(book.id)!!.offset);assertEquals(book,store.peekShelf().single())
  assertTrue(AutomaticSourceDownloads(app,Network(),false).requests().isEmpty())
 }
 @Test fun newAdditionsAcrossSourcesDeduplicateRequireActualWifiAndKeepSameBareIdsIsolated()=runBlocking {
  val policy=GlobalDownloadPreferences(app);val network=Network();val a=Source("gutenberg-synthetic");val b=Source("opds-synthetic");val c=Source("legado-synthetic");val unsupported=Source("unsupported",false)
  val sources=listOf(a,b,c,unsupported);sources.forEach(::shelve)
  val queue=AutomaticSourceDownloads(app,network,false){id->sources.firstOrNull{it.id==id}}
  assertFalse(queue.enqueue(a,a.book));policy.set(true);assertFalse(queue.enqueue(unsupported,unsupported.book))
  network.onWifi=false
  for(s in listOf(a,b,c)){assertTrue(queue.enqueue(s,s.book));assertFalse(queue.enqueue(s,s.book));val r=queue.request(s.id,s.book.id)!!;assertEquals(JobInfo.NETWORK_TYPE_UNMETERED,queue.jobInfo(r).networkType);assertTrue(queue.jobInfo(r).isPersisted);assertTrue(queue.execute(r));assertEquals(0,s.calls);assertEquals("WAITING",queue.request(s.id,s.book.id)!!.state)}
  assertEquals(3,queue.requests().map{it.key}.distinct().size)
  network.onWifi=true
  for(s in listOf(a,b,c)){assertFalse(queue.execute(queue.request(s.id,s.book.id)!!));assertEquals(1,s.calls);assertEquals("COMPLETE",queue.request(s.id,s.book.id)!!.state);assertFalse(queue.enqueue(s,s.book))}
  assertTrue(sources.take(3).all{it.file.isFile});val complete=queue.request(a.id,a.book.id)!!;queue.pause(complete);assertEquals("COMPLETE",queue.request(a.id,a.book.id)!!.state)
 }
 @Test fun restartResolvesOnlyMatchingSourceAndGlobalOffDoesNotBackfillOldShelf()=runBlocking {
  val policy=GlobalDownloadPreferences(app);val network=Network();val source=Source("opds-restart");shelve(source)
  val first=AutomaticSourceDownloads(app,network,false){source}
  policy.set(true);assertTrue(first.enqueue(source,source.book));val before=first.request(source.id,source.book.id)!!
  val replacement=Source(source.id);val cold=AutomaticSourceDownloads(app,network,false){replacement}
  assertEquals(before,cold.request(source.id,source.book.id));cold.execute(before);assertEquals(1,replacement.calls);assertTrue(replacement.file.isFile)
  val neverAdded=Source("old-book-with-no-intent");shelve(neverAdded)
  policy.set(false);cold.cancelAutomatic();policy.set(true);cold.scheduleMore();assertNull(cold.request(neverAdded.id,neverAdded.book.id));assertEquals(0,neverAdded.calls)
 }
 @Test fun clearWhileUncancellableResponseIsInFlightCannotResurrectAndNewDownloadStillWorks()=runBlocking {
  GlobalDownloadPreferences(app).set(true);val source=Source("legado-race");shelve(source);val store=ReadingStore(app,source.id);store.save(source.book.id,"raw-chapter",57)
  val queue=AutomaticSourceDownloads(app,Network(),false){source};assertTrue(queue.enqueue(source,source.book));source.hold=CompletableDeferred()
  val old=queue.request(source.id,source.book.id)!!;val task=launch(Dispatchers.Default){queue.execute(old)};withTimeout(5000){source.entered.await()}
  assertTrue(store.requestCleanup(source.book.id));queue.cancel(source.id,source.book.id);source.clear(source.book.id);assertTrue(store.finishCleanup(source.book.id));source.hold!!.complete(Unit);task.join()
  assertFalse(source.file.exists());assertNull(store.position(source.book.id));assertEquals(source.book,store.peekShelf().single());assertEquals("CANCELLED",queue.request(source.id,source.book.id)!!.state)
  val cold=AutomaticSourceDownloads(app,Network(),false){source};assertFalse(cold.busy(source.id,source.book.id));source.hold=null
  assertTrue(cold.enqueue(source,source.book));val fresh=cold.request(source.id,source.book.id)!!;assertNotEquals(old.nonce,fresh.nonce);cold.execute(fresh);assertTrue(source.file.isFile);cold.pause(old);assertFalse(cold.execute(old));assertEquals("COMPLETE",cold.request(source.id,source.book.id)!!.state)
 }
 @Test fun permissionFailureDoesNotRetryOrLoseProgressAndOtherSourceCannotResolveIt()=runBlocking {
  GlobalDownloadPreferences(app).set(true);val source=Source("opds-rights");shelve(source);ReadingStore(app,source.id).save(source.book.id,"kept",89)
  source.failure=SourceProblem(FailureKind.FORBIDDEN,"测试权限已撤销")
  val queue=AutomaticSourceDownloads(app,Network(),false){source};assertTrue(queue.enqueue(source,source.book));assertFalse(queue.execute(queue.request(source.id,source.book.id)!!));assertEquals("FAILED",queue.request(source.id,source.book.id)!!.state);assertEquals(89,ReadingStore(app,source.id).position(source.book.id)!!.offset)
  val wrong=Source("different-source");val cold=AutomaticSourceDownloads(app,Network(),false){wrong};assertNull(cold.provider(source.id));assertEquals(0,wrong.calls)
 }
 @Test fun allSourceByteInventoryIncludesHiddenHistoricalPartialAndThumbnailsWithoutParsingAccounts() {
  val root=app.noBackupFilesDir;val files=listOf("offline-v1/account-a/book-v1/body","offline-v1/account-b/hidden-v2/raw.part","gutenberg-public-v1/1/book.epub","independent-epub-v1/source/book/thumb.jpg","legado-text-v1/source/book/chapter.txt","cover-thumbs-v1/owner/thumb.png")
  var expected=0L
  files.forEachIndexed{i,n->File(root,n).apply{parentFile!!.mkdirs();writeBytes(ByteArray((i+1)*31){0x7f});expected+=length()}}
  assertEquals(expected,GlobalOfflineUsage.bytes(GlobalOfflineUsage.roots(app)))
  val outside=File(app.filesDir,"not-book-content").apply{writeBytes(ByteArray(600))};java.nio.file.Files.createSymbolicLink(File(root,"legado-text-v1/link").toPath(),outside.toPath())
  assertEquals(expected,GlobalOfflineUsage.bytes(GlobalOfflineUsage.roots(app)))
  File(root,files[1]).delete();assertEquals(expected-62,GlobalOfflineUsage.bytes(GlobalOfflineUsage.roots(app)))
 }
 @Test fun transportSwitchDuringTransferPausesAndUsesSameIntentWhenWifiReturns()=runBlocking {
  GlobalDownloadPreferences(app).set(true);val network=Network();val source=Source("opds-switch");shelve(source)
  val queue=AutomaticSourceDownloads(app,network,false){source};assertTrue(queue.enqueue(source,source.book));val request=queue.request(source.id,source.book.id)!!
  source.hold=CompletableDeferred();val work=async(Dispatchers.Default){queue.execute(request)};withTimeout(5000){source.entered.await()}
  network.onWifi=false;source.hold!!.complete(Unit);assertTrue(work.await());assertFalse(source.file.exists());assertEquals("WAITING",queue.request(source.id,source.book.id)!!.state)
  assertFalse(queue.enqueue(source,source.book));assertEquals(request.nonce,queue.request(source.id,source.book.id)!!.nonce)
  network.onWifi=true;source.hold=null;assertFalse(queue.execute(queue.request(source.id,source.book.id)!!));assertTrue(source.file.isFile);assertEquals(1,queue.requests().size)
 }
 @Test fun actualMeteredWifiAndUnmeteredEthernetDoNotQualifyButUnmeteredWifiDoes()=runBlocking {
  val cm=app.getSystemService(android.net.ConnectivityManager::class.java);val shadow=org.robolectric.Shadows.shadowOf(cm);val active=cm.activeNetwork!!
  val caps=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();val sc=org.robolectric.Shadows.shadowOf(caps)
  sc.clearCapabilities();sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);shadow.setNetworkCapabilities(active,caps)
  val connection=AndroidConnectionState(app);assertTrue(connection.online);assertFalse(connection.wifi)
  GlobalDownloadPreferences(app).set(true);val source=Source("actual-network");shelve(source);val queue=AutomaticSourceDownloads(app,connection,false){source};assertTrue(queue.enqueue(source,source.book));val request=queue.request(source.id,source.book.id)!!
  assertTrue(queue.execute(request));assertEquals(0,source.calls)
  sc.removeTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);sc.addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED);assertFalse(connection.wifi)
  assertTrue(queue.execute(request));assertEquals(0,source.calls)
  sc.removeTransportType(android.net.NetworkCapabilities.TRANSPORT_ETHERNET);sc.addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI);assertTrue(connection.wifi)
  assertFalse(queue.execute(request));assertEquals(1,source.calls)
 }

 @Test fun knownBookHubDownloadDenialIsNotQueuedAndExplicitPermissionRecoveryCanQueue()=runBlocking {
  val fixture=OfflineFixtures();val client=fixture.client();val owner=BookHubSource.ownerId(client)
  val runtime=OfflineDownloads(app,fixture.network,MemorySessionVault(),false);runtime.activate(client);runtime.setAutomaticWifi(true)
  val denied=Book("public:2","测试无权书","","","",0,0,sourceId=owner,readAllowed=false)
  assertEquals(BatchEnqueueResult.REJECTED,runtime.enqueueIfNeeded(owner,denied,automatic=true));assertFalse(runtime.enqueue(owner,denied,automatic=true));assertNull(runtime.request(runtime.key(owner,denied.id)));assertTrue(fixture.calls.isEmpty())
  client.observeReadPermission(denied.id,true);assertTrue(runtime.enqueue(owner,denied,automatic=true))
  val request=runtime.request(runtime.key(owner,denied.id))!!;assertTrue(runtime.valid(request,client));client.observeReadPermission(denied.id,false);assertFalse(runtime.valid(request,client))
 }

}
