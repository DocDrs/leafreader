package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StreamingDownloadTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private suspend fun fixture(redirect:Boolean=false,interrupt:Boolean=false) {
        val w=OfflineFixtures();val raw=w.packageBytes();val data=w.manifest()
        val desc=JSONObject().put("kind","READING_ZIP").put("schemaVersion",1).put("contentVersion",w.version).put("sizeBytes",raw.size).put("sha256",sha(raw)).put("downloadUrl","/api/v1/books/public%3A1/reading-package/content?ticket=fixture")
        val server=MockWebServer();server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse=when {
                request.requestUrl!!.encodedPath.endsWith("/access")->MockResponse().setBody(BookHubFixtures.envelope(data))
                request.requestUrl!!.encodedPath.endsWith("/reading-package")->MockResponse().setBody(BookHubFixtures.envelope(desc))
                redirect->MockResponse().setResponseCode(302).setHeader("Location","https://outside.fixture.invalid/never-request")
                else->MockResponse().setBody(Buffer().write(raw)).setHeader("Content-Type","application/zip").also{if(interrupt)it.throttleBody(512,100,TimeUnit.MILLISECONDS)}
            }
        };server.start()
        try {
            // Synthetic HTTPS identity; only the test bridge uses a loopback HTTP server.
            val origin=ServiceOrigin("https://stream.fixture.invalid");val real=UrlConnectionTransport()
            fun local(uri:java.net.URI)=java.net.URI("http://127.0.0.1:${server.port}${uri.rawPath}${uri.rawQuery?.let{"?$it"}.orEmpty()}")
            val c=BookHubClient(origin,object:StreamingWireTransport {
                override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int)=real.execute(local(uri),method,headers,body,maxBytes)
                override suspend fun download(uri:java.net.URI,headers:Map<String,String>,file:File,maxBytes:Long,valid:()->Boolean,progress:(Long)->Unit)=real.download(local(uri),headers,file,maxBytes,valid,progress)
            })
            c.restore(SavedSession(origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))
            val cache=OfflineStore(File(app.noBackupFilesDir,"stream-$redirect-$interrupt"),BookHubSource.ownerId(c))
            try {
                BookDownloader(c,cache,w.network).download("public:1",false,progress={if(interrupt && it.startsWith("下载阅读包"))w.network.wifi=false})
                assertFalse(redirect||interrupt);assertTrue(cache.status("public:1").complete)
            }catch(e:Exception){if(!redirect&&!interrupt)throw e;assertFalse(cache.status("public:1").complete)}
            assertEquals(3,server.requestCount)
            val access=server.takeRequest();val post=server.takeRequest();val payload=server.takeRequest()
            assertNotNull(access.getHeader("Authorization"));assertEquals("POST",post.method);assertNull(payload.getHeader("Authorization"))
            assertFalse(File(app.noBackupFilesDir,"stream-$redirect-$interrupt").walkTopDown().any{it.isFile&&it.name.startsWith("tmp-")})
        }finally{server.shutdown()}
    }
    @Test fun productionStreamingTransportWritesVerifiedPackageWithoutBearerOnPayload()=runBlocking {fixture()}
    @Test fun redirectAndMidStreamWifiLossNeverPublishComplete()=runBlocking {fixture(redirect=true);fixture(interrupt=true)}
}
