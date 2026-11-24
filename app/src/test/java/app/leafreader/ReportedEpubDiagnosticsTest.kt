package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regressions of the previously reported mechanisms. No real books or server responses. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class ReportedEpubDiagnosticsTest {
    private class Wire(val duplicated:Boolean):WireTransport {
        var accesses=0;var bodies=0
        val manifest=BookHubFixtures.epubManifest().apply {
            if(duplicated) put("spine",JSONArray().put("a").put("a"))
            else {
                put("spine",JSONArray().put("cover").put("a"))
                getJSONArray("resources").put(BookHubFixtures.resource("cover").put("mediaType","application/xhtml+xml"))
            }
        }
        override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
            val text=when {
                uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page())
                uri.path.endsWith("/access")->{accesses++;BookHubFixtures.envelope(manifest)}
                else->{bodies++;if(uri.path.endsWith("/cover"))"<html><body><img src='/never-requested.png'/></body></html>" else BookHubFixtures.epubText}
            }
            return WireResponse(200,text.toByteArray(),if(uri.path.endsWith("/access"))"application/json" else "application/xhtml+xml")
        }
        fun source():BookHubSource {
            val client=BookHubClient(ServiceOrigin("https://diagnosis.fixture.invalid"),this)
            client.restore(SavedSession(client.origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))
            return BookHubSource(client)
        }
    }
    @Test fun explicitImagePageStillFailsWhileAutomaticFirstEntryAndSavedTextResumeWork()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app:Application=ApplicationProvider.getApplicationContext();val wire=Wire(false)
            val vm=ReaderViewModel(app,wire.source(),sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();val book=vm.ui.value.shelfBooks.single()
            val refs=vm.chapterList(book);assertEquals("书前内容",refs.first().title)
            vm.open(book,refs.first().id);advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("BH_EPUB_IMAGE_ONLY"));assertNull(vm.store.position(book.id))
            vm.open(book);advanceUntilIdle();assertNull(vm.ui.value.error);assertTrue(vm.ui.value.chapter!!.text.contains("走向江边"));assertNotNull(vm.ui.value.notice)
            vm.position(5);vm.backToShelf();vm.open(book);advanceUntilIdle()
            assertNull(vm.ui.value.error);assertEquals(refs[1].id,vm.ui.value.chapter!!.id);assertEquals(5,vm.ui.value.offset)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun ambiguousRepeatedTargetStillFailsBeforeBodyAndKeepsOldProgress()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app:Application=ApplicationProvider.getApplicationContext();val wire=Wire(true)
            val vm=ReaderViewModel(app,wire.source(),sessionVault=MemorySessionVault(),linkBookHubShelf=false);advanceUntilIdle();val book=vm.ui.value.shelfBooks.single()
            vm.store.save(book.id,"preserved-old-chapter",27);val saved=vm.store.position(book.id)
            vm.open(book);advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("BH_EPUB_AMBIGUOUS_TARGET"))
            try{vm.chapterList(book);fail("directory must fail for a genuinely ambiguous target")}catch(e:SourceProblem){assertEquals("BH_EPUB_AMBIGUOUS_TARGET",e.diagnostic)}
            vm.open(book,"explicit-chapter");advanceUntilIdle();assertTrue(vm.ui.value.error!!.contains("BH_EPUB_AMBIGUOUS_TARGET"))
            assertEquals(3,wire.accesses);assertEquals(0,wire.bodies);assertEquals(saved,vm.store.position(book.id))
        }finally{Dispatchers.resetMain()}
    }
}
