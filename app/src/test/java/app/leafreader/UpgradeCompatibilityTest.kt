package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Reads actual on-disk XML written by the delivered 0.6.0 code in another Gradle invocation. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class UpgradeCompatibilityTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private lateinit var expected:JSONObject
    private val settings=ReaderSettings(28f,1.8f,4,24f,38f,40f)
    @Before fun importOldFilesWithoutRewritingTheirKeys() {
        val prefix="upgrade-v06/"
        val names=javaClass.classLoader!!.getResourceAsStream(prefix+"files.txt")!!.bufferedReader().readLines()
        val dir=File(app.applicationInfo.dataDir,"shared_prefs").apply{mkdirs()}
        names.forEach { name -> javaClass.classLoader!!.getResourceAsStream(prefix+name)!!.use { input -> File(dir,name).outputStream().use(input::copyTo) } }
        expected=JSONObject(javaClass.classLoader!!.getResourceAsStream(prefix+"expected.json")!!.bufferedReader().readText())
        assertEquals("0.6.0",expected.getString("writerVersion"))
    }
    private class Wire : WireTransport {
        var offline=true;var revoke=false;var account="fixture-account";var logins=0
        override suspend fun execute(uri:java.net.URI,method:String,headers:Map<String,String>,body:ByteArray?,maxBytes:Int):WireResponse {
            assertEquals("upgrade.fixture.invalid",uri.host)
            if(offline)throw SourceProblem(FailureKind.NETWORK,"fixture offline")
            if(uri.path.endsWith("/auth/login")){logins++;return WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.session(account)).toByteArray())}
            if(revoke)return WireResponse(401,"{\"code\":\"SESSION_REVOKED\"}".toByteArray())
            val data=when {
                uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(JSONObject().put("items",org.json.JSONArray()).put("hasMore",false).put("nextCursor",JSONObject.NULL))
                uri.path.endsWith("/access")->BookHubFixtures.envelope(BookHubFixtures.txtManifest())
                uri.path.endsWith("/a")->BookHubFixtures.firstText
                uri.path.endsWith("/b")->BookHubFixtures.secondText
                else->throw AssertionError("Unexpected fixture request")
            }
            return WireResponse(200,data.toByteArray())
        }
        fun gate()=BookHubConnectionGate(LoginOriginVerifier {},{BookHubClient(it,this)})
    }
    private fun snapshots()=File(app.applicationInfo.dataDir,"shared_prefs").listFiles()!!.filter{it.extension=="xml"}.associate {
        it.nameWithoutExtension to app.getSharedPreferences(it.nameWithoutExtension,0).all.toMap()
    }
    @Test fun legacyDataAndSealedSessionSurviveColdReadOfflineThenResume()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val before=snapshots();val cipher=UpgradeCipher();val vault=EncryptedSessionVault(app,cipher)
            assertTrue(vault.load() is SessionLoad.Ready)
            val wire=Wire();val vm=ReaderViewModel(app,connectionGate=wire.gate(),sessionVault=vault);advanceUntilIdle()
            assertTrue(vm.ui.value.remote);assertNull(vm.ui.value.shelfError);vm.loadShelf();advanceUntilIdle();assertNotNull(vm.ui.value.shelfError);assertEquals(0,wire.logins)
            assertFalse(cipher.forgotten);assertEquals(settings,vm.ui.value.settings)
            assertEquals("https://upgrade.fixture.invalid",vm.ownOrigin());assertEquals(expected.getString("sourceId"),vm.store.sourceId)
            val books=vm.ui.value.shelfBooks
            assertEquals(setOf("retained-local-1","public:1","local-added","mountain-letter-v1","bookhub-same-title","other-account-book"),books.map{it.id}.toSet())
            assertTrue(books.none(RetiredBundledBooks::contains))
            assertTrue(books.any{it.id=="mountain-letter-v1"&&it.sourceId==vm.store.sourceId})
            assertTrue(books.any{it.title=="山海来信"&&it.sourceId==vm.store.sourceId})
            before.forEach{(file,values)->values.forEach{(key,value)->assertEquals("$file/$key",value,app.getSharedPreferences(file,0).all[key])}}
            val old=ReadingStore(app,RetiredBundledBooks.SOURCE_ID)
            assertEquals(147,old.position("mountain-letter-v1")!!.offset);assertEquals(233,old.position("night-log-v1")!!.offset)
            assertEquals(87,ReadingStore(app,expected.getString("otherSourceId")).position("other-account-book")!!.offset)
            wire.offline=false;vm.loadShelf();advanceUntilIdle()
            assertEquals(books.map{it.key},vm.ui.value.shelfBooks.map{it.key}) // Empty service list keeps cached and explicit books.
            vm.open(books.single{it.id=="public:1"});advanceUntilIdle()
            assertNull(vm.ui.value.error);assertNotNull(vm.ui.value.chapter);assertEquals(123,vm.ui.value.offset)
            assertEquals(expected.getString("chapterId"),vm.ui.value.chapter!!.id)
            val cold=ReaderViewModel(app,connectionGate=wire.gate(),sessionVault=EncryptedSessionVault(app,UpgradeCipher()));advanceUntilIdle()
            assertEquals(settings,cold.ui.value.settings);assertTrue(cold.ui.value.remote);assertEquals(0,wire.logins)
            assertEquals(123,cold.store.position("public:1")!!.offset)
        }finally{Dispatchers.resetMain()}
    }
    @Test fun filteringDoesNotRequireOrDestroyASessionAndNeverMatchesTitleOrAnotherSource()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val old=ReadingStore(app,RetiredBundledBooks.SOURCE_ID)
            val vm=ReaderViewModel(app,sessionVault=MemorySessionVault());advanceUntilIdle()
            assertFalse(vm.ui.value.remote);assertEquals(setOf("retained-local-1","public:1","local-added","mountain-letter-v1","bookhub-same-title","other-account-book"),vm.ui.value.shelfBooks.map{it.id}.toSet());assertTrue(vm.ui.value.books.isEmpty())
            assertEquals(2,old.savedServiceShelf().size);assertNotNull(old.position("mountain-letter-v1"));assertNotNull(old.position("night-log-v1"))
            vm.loadShelf();vm.selectTab("find");vm.search("");advanceUntilIdle();assertTrue(vm.ui.value.books.isEmpty())
            val base=Book("mountain-letter-v1","anything","","","",1,0,sourceId=RetiredBundledBooks.SOURCE_ID)
            assertTrue(RetiredBundledBooks.contains(base));assertTrue(RetiredBundledBooks.contains(base.copy(id="night-log-v1")))
            assertFalse(RetiredBundledBooks.contains(base.copy(sourceId="bookhub-v1-fixture")))
            assertFalse(RetiredBundledBooks.contains(base.copy(id="user-owned",title="山海来信")))
        }finally{Dispatchers.resetMain()}
    }
    @Test fun realRevocationRequiresLoginButLeavesAllPersistedBooksAndSettings()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wire=Wire().apply{offline=false;revoke=true};val vault=EncryptedSessionVault(app,UpgradeCipher())
            val vm=ReaderViewModel(app,connectionGate=wire.gate(),sessionVault=vault);advanceUntilIdle();vm.loadShelf();advanceUntilIdle()
            assertEquals(SessionLoad.Empty,vault.load());assertNull(vm.ui.value.notice);assertTrue(vm.ui.value.sourceLabel.contains("重新登录"))
            assertEquals(settings,vm.ui.value.settings);assertNotNull(vm.store.position("public:1"));assertEquals(6,vm.ui.value.shelfBooks.size)
            assertTrue(vm.ui.value.shelfBooks.none(RetiredBundledBooks::contains))
        }finally{Dispatchers.resetMain()}
    }
    @Test fun sameAccountReloginAndOtherAccountKeepTheirOldNamespaces()=runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wire=Wire().apply{offline=false};val vm=ReaderViewModel(app,connectionGate=wire.gate(),sessionVault=EncryptedSessionVault(app,UpgradeCipher()));advanceUntilIdle()
            val accountA=vm.store;vm.connect("https://upgrade.fixture.invalid","fixture","fixture-password");advanceUntilIdle()
            assertEquals(expected.getString("sourceId"),vm.store.sourceId);assertEquals(123,vm.store.position("public:1")!!.offset)
            wire.account="other-account";vm.connect("https://upgrade.fixture.invalid","fixture2","fixture-password");advanceUntilIdle()
            assertEquals(expected.getString("otherSourceId"),vm.store.sourceId);assertEquals(87,vm.store.position("other-account-book")!!.offset)
            assertTrue(vm.ui.value.shelfBooks.any{it.id=="public:1"});assertEquals(123,accountA.position("public:1")!!.offset)
            vm.signOut();advanceUntilIdle();assertFalse(vm.ui.value.remote);assertEquals(setOf("retained-local-1","public:1","local-added","mountain-letter-v1","bookhub-same-title","other-account-book"),vm.ui.value.shelfBooks.map{it.id}.toSet())
            assertEquals(settings,vm.ui.value.settings);assertEquals(123,accountA.position("public:1")!!.offset)
        }finally{Dispatchers.resetMain()}
    }
}
