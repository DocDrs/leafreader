package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import app.leafreader.covers.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverThumbnailsTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun folder()=File(app.noBackupFilesDir,"cover-test-"+UUID.randomUUID()).apply{mkdirs()}
    private fun source(w:CoverFixtures,client:BookHubClient=w.client(),root:File=folder())=BookHubSource(client,OfflineStore(root,BookHubSource.ownerId(client)),w.base.network)
    private suspend fun using(test:suspend(CoroutineScope)->Unit) {val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO);try{test(scope)}finally{scope.cancel()}}
    @Test fun namedEmbeddedCoverUsesExistingResourcesOnlineAndColdOfflineWithoutHttp()=runBlocking {using{scope->
        val w=CoverFixtures();val s=source(w);w.seed(s.offline!!);val disk=folder();val repo=BookCoverRepository(disk,scope)
        val found=EmbeddedCover.read(s.offline!!,"public:1",s.offline!!.manifest("public:1")!!)!!;assertEquals("pic",found.resourceId)
        val a=repo.load(s,"public:1","0"){true}!!;assertEquals(160,a.width);assertEquals(w.color,a.getPixel(1,1));assertTrue(w.calls.isEmpty())
        w.base.network.online=false;val cold=BookCoverRepository(disk,scope);assertNotNull(cold.load(s,"public:1","1"){true});assertTrue(w.calls.isEmpty())
        assertFalse(s.offline!!.status("public:1").complete)
    }}
    @Test fun explicitCoverTitleWorksButOrdinaryOrExternalIllustrationIsNeverUsedAsCover()=runBlocking {using{scope->
        for(title in listOf(false,true)) {
            val w=CoverFixtures().apply{named=false;titleMarker=title};val s=source(w);w.seed(s.offline!!)
            val local=EmbeddedCover.read(s.offline!!,"public:1",s.offline!!.manifest("public:1")!!)
            assertEquals(title,local!=null)
            if(!title){assertNotNull(BookCoverRepository(folder(),scope).load(s,"public:1","0"){true});assertEquals(1,w.calls.count{it.endsWith("/cover")})}
        }
        val w=CoverFixtures().apply{externalImage=true};val s=source(w);w.seed(s.offline!!)
        assertNull(EmbeddedCover.read(s.offline!!,"public:1",s.offline!!.manifest("public:1")!!))
        val chapter=CoverFixtures();val c=source(chapter);chapter.seed(c.offline!!)
        c.offline!!.put("public:1",chapter.version,"cover",("<html><head><title>Cover</title></head><body><p>"+"普通正文不能充当封面。".repeat(100)+"</p><img src='/api/v1/books/public%3A1/resources/pic'/></body></html>").toByteArray(),c.offline!!.generation("public:1"))
        assertNull(EmbeddedCover.read(c.offline!!,"public:1",c.offline!!.manifest("public:1")!!))
    }}
    @Test fun officialSingleCoverBindsVersionAndPersistsWithoutDownloadingBodyOrBook()=runBlocking {using{scope->
        val w=CoverFixtures();val s=source(w);val disk=folder();val repo=BookCoverRepository(disk,scope)
        assertNotNull(repo.load(s,"public:1","0"){true});assertEquals(2,w.calls.count{it.endsWith("/access")});assertEquals(1,w.calls.count{it.endsWith("/cover")})
        assertEquals(OfflineStatus(),s.offline!!.status("public:1"));val before=w.calls.size
        assertNotNull(repo.load(s,"public:1","0"){true});assertEquals(before,w.calls.size)
        w.base.network.online=false;assertNotNull(BookCoverRepository(disk,scope).load(s,"public:1","cold"){true});assertEquals(before,w.calls.size)
        w.color=0xff5b4579.toInt();w.seed(s.offline!!)
        assertEquals(w.color,repo.load(s,"public:1","download-arrived"){true}!!.getPixel(1,1));assertEquals(before,w.calls.size)
        assertTrue(w.calls.all{it.endsWith("/access") || it.endsWith("/cover")})
    }}
    @Test fun sameBookAndTitleDifferentVersionsAndKnownOldOfflineVersionRemainIsolated()=runBlocking {using{scope->
        val w=CoverFixtures();val s=source(w);val disk=folder();val repo=BookCoverRepository(disk,scope)
        val old=repo.load(s,"public:1","0"){true}!!.getPixel(1,1);w.version="cover-v2";w.color=0xff5c477a.toInt()
        assertEquals(w.color,repo.load(s,"public:1","refresh"){true}!!.getPixel(1,1));assertNotEquals(old,w.color)
        w.version="cover-v1";w.named=false;w.seed(s.offline!!,false);w.base.network.online=false
        assertEquals(old,BookCoverRepository(disk,scope).load(s,"public:1","old-progress"){true}!!.getPixel(1,1))
    }}
    @Test fun noCoverBadImageAuthAndOfflineMissingQuietlyFallBackAndDoNotLogOutOrRetryStorm()=runBlocking {using{scope->
        for(status in listOf(404,401,503)) {
            val w=CoverFixtures().apply{coverStatus=status};val c=w.client();val before=c.snapshot();val s=source(w,c);val repo=BookCoverRepository(folder(),scope)
            assertNull(repo.load(s,"public:1","0"){true});val calls=w.calls.size;assertNull(repo.load(s,"public:1","0"){true});assertEquals(calls,w.calls.size);assertEquals(before,c.snapshot())
            assertFalse(w.calls.any{it.contains("/auth/")})
        }
        val w=CoverFixtures().apply{badImage=true};val s=source(w);assertNull(BookCoverRepository(folder(),scope).load(s,"public:1","0"){true})
        w.base.network.online=false;assertNull(BookCoverRepository(folder(),scope).load(s,"public:1","0"){true})
    }}
    @Test fun wrongBookOrVersionChangingDuringCoverNeverPublishesPixels()=runBlocking {using{scope->
        val wrong=CoverFixtures().apply{wrongBook=true};val s=source(wrong);assertNull(BookCoverRepository(folder(),scope).load(s,"public:1","0"){true});assertFalse(wrong.calls.any{it.endsWith("/cover")})
        val w=CoverFixtures().apply{afterCover={version="changed"}};val root=folder()
        assertNull(BookCoverRepository(root,scope).load(source(w),"public:1","0"){true});assertFalse(root.walkTopDown().any{it.extension=="png"})
    }}
    @Test fun duplicateLoadsCoalesceAndDifferentBooksUseAtMostTwoNetworkSlots()=runBlocking {using{scope->
        val w=CoverFixtures();val s=source(w);val repo=BookCoverRepository(folder(),scope)
        coroutineScope{listOf(async{repo.load(s,"public:1","0"){true}},async{repo.load(s,"public:1","0"){true}}).awaitAll().forEach{assertNotNull(it)}}
        assertEquals(1,w.calls.count{it.endsWith("/cover")});w.calls.clear()
        coroutineScope{(2..5).map{n->async{repo.load(s,"public:$n","0"){true}}}.awaitAll().forEach{assertNotNull(it)}}
        assertEquals(2,w.peak.get())
    }}
    @Test fun clearWhileOldResponseIsDelayedCannotResurrectFilesAndNewExplicitLoadStillWorks()=runBlocking {using{scope->
        val w=CoverFixtures();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.afterCover={entered.complete(Unit);withContext(NonCancellable){release.await()}}
        val s=source(w);val root=folder();val repo=BookCoverRepository(root,scope);val store=ReadingStore(app,s.id);store.save("public:1","preserved",27);val saved=store.position("public:1")
        val task=async{repo.load(s,"public:1","0"){true}};entered.await();s.offline!!.clear("public:1");repo.clear(s.id,"public:1");release.complete(Unit)
        assertNull(task.await());assertFalse(root.walkTopDown().any{it.extension=="png"});assertEquals(saved,store.position("public:1"))
        assertNotNull(repo.load(s,"public:1","new-request"){true});assertEquals(saved,store.position("public:1"))
    }}
    @Test fun sameBookIdOtherAccountAndLateOldSessionNeverShareOrPublishCover()=runBlocking {using{scope->
        val w=CoverFixtures();val c=w.client();val a=source(w,c);val disk=folder();val repo=BookCoverRepository(disk,scope)
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();w.afterCover={entered.complete(Unit);withContext(NonCancellable){release.await()}}
        val task=async{repo.load(a,"public:1","0"){c.snapshot()!=null}};entered.await();c.clearSession();release.complete(Unit);assertNull(task.await())
        w.afterCover=null;val b=source(w,w.client("second-account"));assertNotEquals(a.id,b.id);w.base.network.online=false
        assertNull(repo.load(b,"public:1","0"){true});w.base.network.online=true;assertNotNull(repo.load(b,"public:1","0"){true})
    }}
    @Test fun corruptedThumbnailAndSizeLimitsFallBackWithoutHttpOrUnboundedDisk()=runBlocking {using{scope->
        val w=CoverFixtures();val s=source(w);val root=folder();assertNotNull(BookCoverRepository(root,scope).load(s,"public:1","0"){true})
        root.walkTopDown().first{it.extension=="png"}.writeBytes(byteArrayOf(1));w.base.network.online=false;val before=w.calls.size
        assertNull(BookCoverRepository(root,scope).load(s,"public:1","0"){true});assertEquals(before,w.calls.size)
        val diskRoot=folder();val disk=CoverThumbnailStore(diskRoot);val bytes=ByteArray(CoverThumbnailStore.MAX_BYTES){(it%251).toByte()}
        for(n in 1..40)assertTrue(disk.put("owner","book-$n","v1",0,bytes){true})
        val images=diskRoot.walkTopDown().filter{it.extension=="png"}.toList();assertTrue(images.size<=CoverThumbnailStore.DISK_ITEMS);assertTrue(images.sumOf{it.length()}<=CoverThumbnailStore.DISK_BYTES)
        assertTrue(diskRoot.walkTopDown().filter{it.isFile}.sumOf{it.length()}<=CoverThumbnailStore.DISK_BYTES)
        assertFalse(disk.put("owner","large","v",0,ByteArray(CoverThumbnailStore.MAX_BYTES+1)){true})
        assertFalse(disk.put("owner","invalid-version","x".repeat(513),0,byteArrayOf(1)){true})
    }}
}
