package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OfflineAppFlowTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun gate(w:OfflineFixtures)=BookHubConnectionGate(LoginOriginVerifier{}, {BookHubClient(it,w)})
    @Test fun productionConnectionPathColdOfflineResumesTextAndCoverWithoutHttp()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()}
            val runtime=OfflineDownloads(app,w.network,vault,false)
            val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false)
            val book=vm.ui.first{!it.shelfLoading}.shelfBooks.single()
            BookDownloader(c,runtime.cache(book.sourceId),w.network).download(book.id,false)
            vm.open(book);vm.ui.first{!it.loading};assertNull(vm.ui.value.error);assertNotNull(vm.ui.value.graphicId)
            val image=vm.ui.value.graphicId;vm.backToShelf();w.network.online=false
            val before=w.calls.size
            val cold=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=OfflineDownloads(app,w.network,vault,false),linkBookHubShelf=false)
            cold.ui.first{!it.shelfLoading};cold.open(book);cold.ui.first{!it.loading}
            assertNull(cold.ui.value.error);assertEquals(image,cold.ui.value.graphicId)
            val refs=cold.ui.value.chapters;cold.open(book,refs[1].id);cold.ui.first{!it.loading};assertNull(cold.ui.value.error)
            cold.position(17);cold.settings(ReaderSettings(27f,1.7f,4));val saved=cold.store.position(book.id)!!;cold.backToShelf()
            val again=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=OfflineDownloads(app,w.network,vault,false),linkBookHubShelf=false)
            again.ui.first{!it.shelfLoading};again.open(book);again.ui.first{!it.loading}
            assertNull(again.ui.value.error);assertEquals(saved.chapterId,again.ui.value.chapter!!.id);assertEquals(saved.offset,again.ui.value.offset)
            assertEquals(4,again.ui.value.settings.palette);assertEquals(before,w.calls.size)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun upgradeDoesNotQueueExistingShelfButNewAddQueuesOnceAndSettingPersists()=runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
        try {
            val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()};val runtime=OfflineDownloads(app,w.network,vault,false)
            val vm=ReaderViewModel(app,connectionGate=gate(w),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false)
            val existing=vm.ui.first{!it.shelfLoading}.shelfBooks.single();val owner=existing.sourceId
            assertNull(runtime.request(runtime.key(owner,existing.id)))
            val new=existing.copy(id="public:2",title="新加入自写样本");val calls=w.calls.size;vm.addToShelf(new)
            OfflineEvents.revision.first{runtime.request(runtime.key(owner,new.id))!=null}
            val request=runtime.request(runtime.key(owner,new.id))!!;assertTrue(request.automatic);assertFalse(request.allowMetered)
            vm.addToShelf(new);assertEquals(request.nonce,runtime.request(request.key)!!.nonce)
            assertEquals(1,vm.ui.value.shelfBooks.count{it.key==new.key});assertEquals(calls,w.calls.size)
            vm.automaticWifi(false);val third=existing.copy(id="public:3");vm.addToShelf(third)
            assertNull(runtime.request(runtime.key(owner,third.id)));assertFalse(OfflineDownloads(app,w.network,vault,false).automaticWifi())
        }finally{Dispatchers.resetMain()}
    }
}
