package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Patch107Test {
 private val app:Application=ApplicationProvider.getApplicationContext()
 @Test fun savedSessionRestoresItsExactSourceAndLocalDataWithoutAnyBuiltInFallbackOrHttp()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val w=OfflineFixtures();val c=w.client();val vault=MemorySessionVault().apply{value=c.snapshot()}
   val runtime=OfflineDownloads(app,w.network,vault,false);val id=BookHubSource.ownerId(c);val cache=runtime.cache(id)
   BookDownloader(c,cache,w.network).download("public:1",false)
   val store=ReadingStore(app,id);val b=Book("public:1","合成书","合成作者","","",2,0,"EPUB",id)
   store.addToShelf(b);store.addToShelf(b.copy(id="hidden"));store.hideFromShelf(setOf("hidden"))
   val refs=BookHubSource(c,cache,w.network).chapters(b.id);store.save(b.id,refs[1].id,7);val position=store.position(b.id)
   val settings=ReaderSettings(fontSp=26f,palette=4,lineSpacing=1.72f);store.saveSettings(settings)
   // Simulates an older encrypted session whose target was not in the address preference.
   app.getSharedPreferences("own-server",0).edit().clear().commit()
   val saved=vault.value;val count=w.calls.size;w.network.online=false
   val gate=BookHubConnectionGate(LoginOriginVerifier{error("no verification during restore")},{BookHubClient(it,w)})
   val cold=ReaderViewModel(app,connectionGate=gate,sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);cold.ui.first{!it.shelfLoading}
   assertEquals(c.origin.value,cold.ownOrigin());assertEquals(saved,vault.value)
   assertEquals("BookHub · 已连接",cold.ui.value.sourceLabel);assertFalse(cold.ui.value.sourceLabel.contains(c.origin.uri.host))
   assertEquals(listOf(b.key),cold.ui.value.shelfBooks.map{it.key});assertEquals(position,cold.positionOf(b))
   assertEquals(settings,store.settings());assertTrue(cache.status(b.id,true).complete)
   cold.open(b);cold.ui.first{!it.loading};assertNull(cold.ui.value.error);assertEquals(7,cold.ui.value.offset);assertEquals(count,w.calls.size)
   val again=ReaderViewModel(app,connectionGate=gate,sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false);again.ui.first{!it.shelfLoading}
   assertEquals(listOf(b.key),again.ui.value.shelfBooks.map{it.key});assertEquals(count,w.calls.size)
   assertEquals(position!!.chapterId,again.positionOf(b)!!.chapterId);assertEquals(position.offset,again.positionOf(b)!!.offset);assertEquals(settings,store.settings())
  }finally{Dispatchers.resetMain()}
 }
 @Test fun absentSavedOriginAndSessionNeverInventsATargetOrRemovesExistingBooks()=runTest {
  Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
  try {
   val id="bookhub-v1-"+digest("unknown historical owner").take(32);val store=ReadingStore(app,id)
   val b=Book("public:1","已有书","作者","","",1,0,"TXT",id);store.addToShelf(b);store.save(b.id,"old-chapter",31)
   app.getSharedPreferences("own-server",0).edit().clear().commit()
   val vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{error("unexpected network")},{error("unexpected client")}),sessionVault=MemorySessionVault(),linkBookHubShelf=false)
   vm.ui.first{!it.shelfLoading};assertEquals("",vm.ownOrigin());assertFalse(vm.ui.value.remote)
   assertEquals(listOf(b.key),vm.ui.value.shelfBooks.map{it.key});assertEquals(31,vm.positionOf(b)!!.offset)
  }finally{Dispatchers.resetMain()}
 }
}
