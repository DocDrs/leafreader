package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class Version07UpgradeCompatibilityTest {
    @Test fun actualV07XmlRetainsSessionShelfLayoutAndCanonicalTxtAndEpubPositions()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app:Application=ApplicationProvider.getApplicationContext();val dir=File(app.applicationInfo.dataDir,"shared_prefs").apply{mkdirs()}
            val prefix="upgrade-v07/";javaClass.classLoader!!.getResourceAsStream(prefix+"files.txt")!!.bufferedReader().readLines().forEach{n->
                javaClass.classLoader!!.getResourceAsStream(prefix+n)!!.use{input->File(dir,n).outputStream().use(input::copyTo)}
            }
            val expected=JSONObject(javaClass.classLoader!!.getResourceAsStream(prefix+"expected.json")!!.bufferedReader().readText());assertEquals("0.7.0",expected.getString("writerVersion"))
            val sealed=app.getSharedPreferences("bookhub-session-sealed-v1",0).getString("sealed",null)
            var offline=true;var logins=0
            val gate=BookHubConnectionGate(LoginOriginVerifier {},{origin->BookHubClient(origin,WireTransport{uri,_,_,_,_->
                assertEquals("upgrade.fixture.invalid",uri.host)
                if(uri.path.endsWith("/auth/login")){logins++;throw AssertionError("Upgrade must not password-login")}
                if(offline)throw SourceProblem(FailureKind.NETWORK,"fixture offline")
                val epub=uri.path.contains("public:2")
                val text=when {
                    uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(JSONObject().put("items",org.json.JSONArray()).put("hasMore",false).put("nextCursor",JSONObject.NULL))
                    uri.path.endsWith("/access")->BookHubFixtures.envelope(if(epub)BookHubFixtures.epubManifest().put("bookId","public:2").apply{getJSONArray("resources").getJSONObject(0).put("url","/api/v1/books/public%3A2/resources/a?ticket=fixture-ticket")} else BookHubFixtures.txtManifest())
                    epub->BookHubFixtures.epubText
                    uri.path.endsWith("/a")->BookHubFixtures.firstText
                    else->BookHubFixtures.secondText
                };WireResponse(200,text.toByteArray())
            })})
            val vm=ReaderViewModel(app,connectionGate=gate,sessionVault=EncryptedSessionVault(app,UpgradeCipher()));advanceUntilIdle()
            assertTrue(vm.ui.value.remote);assertNull(vm.ui.value.shelfError);vm.loadShelf();advanceUntilIdle();assertNotNull(vm.ui.value.shelfError);assertEquals(0,logins)
            assertEquals(expected.getString("sourceId"),vm.store.sourceId);assertEquals(6,vm.store.position("public:2")!!.offset);assertEquals(123,vm.store.position("public:1")!!.offset)
            assertEquals(ReaderSettings(28f,1.8f,4,24f,38f,40f),vm.ui.value.settings)
            assertTrue(vm.ui.value.shelfBooks.none(RetiredBundledBooks::contains));assertTrue(vm.ui.value.shelfBooks.any{it.id=="mountain-letter-v1"})
            offline=false;val book=vm.ui.value.shelfBooks.single{it.id=="public:2"};vm.open(book);advanceUntilIdle()
            assertNull(vm.ui.value.error);assertEquals(6,vm.ui.value.offset);assertEquals(expected.getString("epubChapterId"),vm.ui.value.chapter!!.id)
            assertEquals(expected.getString("epubCanonicalText"),vm.ui.value.chapter!!.text);assertEquals(expected.getString("epubTextSha256"),digest(vm.ui.value.chapter!!.text))
            vm.backToShelf();vm.open(vm.ui.value.shelfBooks.single{it.id=="public:1"});advanceUntilIdle();assertNull(vm.ui.value.error);assertEquals(123,vm.ui.value.offset)
            assertEquals(sealed,app.getSharedPreferences("bookhub-session-sealed-v1",0).getString("sealed",null));assertEquals(0,logins)
        }finally{Dispatchers.resetMain()}
    }
}
