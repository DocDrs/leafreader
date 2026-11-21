package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.Book
import app.leafreader.data.BookHubSource
import app.leafreader.offline.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class BatchDownloadQueueTest {
    @Test fun simultaneousBatchesCreateOneQueueIdentityAndKeepWifiPolicyAndOwner() {
        val app=ApplicationProvider.getApplicationContext<Application>();val wire=OfflineFixtures();val client=wire.client()
        val runtime=OfflineDownloads(app,wire.network,MemorySessionVault(),false);runtime.activate(client)
        val owner=BookHubSource.ownerId(client);val book=Book("public:1","自写测试","作者","","",1,0,sourceId=owner)
        val pool=Executors.newFixedThreadPool(4);val start=CountDownLatch(1)
        try {
            val futures=(1..20).map{pool.submit(Callable{start.await();runtime.enqueueIfNeeded(owner,book)})};start.countDown()
            val outcomes=futures.map{it.get(10,TimeUnit.SECONDS)}
            assertEquals(1,outcomes.count{it==BatchEnqueueResult.ADDED});assertEquals(19,outcomes.count{it==BatchEnqueueResult.PRESENT})
            val r=runtime.request(runtime.key(owner,book.id))!!;assertFalse(r.allowMetered)
            assertEquals(BatchEnqueueResult.REJECTED,runtime.enqueueIfNeeded("other-account",book,true));assertNull(runtime.request(runtime.key("other-account",book.id)))
            assertEquals(r,runtime.request(runtime.key(owner,book.id)))
        } finally {pool.shutdownNow()}
    }
}
