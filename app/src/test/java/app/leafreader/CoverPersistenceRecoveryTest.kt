package app.leafreader

import app.leafreader.covers.*
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverPersistenceRecoveryTest {
    @Test fun memoryHitRetriesUnavailableDiskSoARecoveredThumbnailSurvivesColdOfflineLoad()=runBlocking {
        val wire=CoverFixtures();val client=wire.client();val owner=BookHubSource.ownerId(client)
        val source=BookHubSource(client,OfflineStore(Files.createTempDirectory("cover-content").toFile(),owner),wire.base.network)
        val root=Files.createTempFile("cover-disk-blocked",".tmp").toFile()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Default)
        try {
            val repo=BookCoverRepository(root,scope)
            assertNotNull(repo.load(source,"public:1","same"){true})
            assertTrue(root.isFile);root.delete();root.mkdirs()
            assertNotNull(repo.load(source,"public:1","same"){true})
            assertEquals(wire.version,CoverThumbnailStore(root).latest(owner,"public:1",0))
            val calls=wire.calls.size;wire.base.network.online=false
            assertNotNull(BookCoverRepository(root,scope).load(source,"public:1","cold"){true});assertEquals(calls,wire.calls.size)
        }finally{scope.cancel()}
    }
}
