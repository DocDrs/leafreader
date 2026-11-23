package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import java.security.cert.Certificate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BookHubLoginPolicyTest {
    private val fixture = ConfirmedBookHubEntry(ServiceOrigin("https://bookhub.fixture.invalid"), "测试服务", "fixture identity")

    @Test fun migrationNeverReplacesALegacyDeploymentWithANewDefault() {
        assertEquals("https://same.fixture.invalid",ServiceOrigin("HTTPS://SAME.fixture.invalid:443/").value)
        assertEquals("https://[::1]",ServiceOrigin("https://[::1]:443/").value)
        assertEquals(InitialBookHubTarget(true, "https://legacy.fixture.invalid"), InitialBookHubTarget.migrate("https://legacy.fixture.invalid", fixture))
        assertFalse(InitialBookHubTarget.migrate("https://bookhub.fixture.invalid/", fixture).useCustom)
        assertTrue(InitialBookHubTarget.migrate("https://legacy.fixture.invalid", null).useCustom)
        assertFalse(InitialBookHubTarget.migrate("", null).useCustom)
        assertNull(BuiltInBookHub.confirmedEntry)
    }

    @Test fun failedHttpsVerificationNeverConstructsAClientOrSubmitsCredentials() = runBlocking {
        var clients = 0
        val gate = BookHubConnectionGate(LoginOriginVerifier { throw SourceProblem(FailureKind.NETWORK, "fixture TLS rejected") }, {
            clients++; BookHubClient(it)
        })
        try { gate.login(fixture.origin.value, "fixture-user", "fixture-password"); fail("Expected rejection") }
        catch (e: SourceProblem) { assertEquals(FailureKind.NETWORK, e.kind) }
        assertEquals(0, clients)
    }

    @Test fun realVerifierRejectsHttpBeforeOpeningAnyConnection() = runBlocking {
        try { SystemHttpsOriginVerifier().verify(ServiceOrigin("http://127.0.0.1:1")); fail("HTTP must not receive login") }
        catch (e: SourceProblem) { assertEquals(FailureKind.PROTOCOL, e.kind) }
    }

    @Test fun verifiedExactOriginIsTheOnlyLoginDestinationAndTheLoginHasNoOldBearer() = runBlocking {
        val events = mutableListOf<String>()
        val calls = mutableListOf<Triple<URI, String, Map<String, String>>>()
        val wire = WireTransport { uri, method, headers, _, _ ->
            calls += Triple(uri, method, headers)
            WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
        }
        val gate = BookHubConnectionGate(LoginOriginVerifier { events += "verify:${it.value}" }, {
            events += "client:${it.value}"; BookHubClient(it, wire)
        })
        gate.login(fixture.origin.value, "fixture-user", "fixture-password")
        assertEquals(listOf("verify:https://bookhub.fixture.invalid", "client:https://bookhub.fixture.invalid"), events)
        assertEquals(1, calls.size)
        assertEquals("https://bookhub.fixture.invalid/api/v1/auth/login", calls.single().first.toString())
        assertEquals("POST", calls.single().second)
        assertNull(calls.single().third["Authorization"])
    }

    private class Probe(val status: Int, val tlsFailure: Boolean = false) : HttpsURLConnection(java.net.URL("https://probe.fixture.invalid")) {
        var disconnected = false
        override fun connect() {}
        override fun disconnect() { disconnected = true }
        override fun usingProxy() = false
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getResponseCode(): Int {
            if (tlsFailure) throw SSLHandshakeException("fixture certificate rejection")
            return status
        }
    }

    @Test fun preflightRejectsRedirectAndTlsFailureBeforeAnyPasswordAndDisablesCookies() = runBlocking {
        for (probe in listOf(Probe(302), Probe(200, true))) {
            var clients = 0
            val gate = BookHubConnectionGate(SystemHttpsOriginVerifier { probe }, { clients++; BookHubClient(it) })
            try { gate.login(fixture.origin.value, "fixture-user", "fixture-password"); fail("Expected verification error") }
            catch (_: SourceProblem) { }
            assertEquals(0, clients)
            assertEquals("HEAD", probe.requestMethod)
            assertFalse(probe.instanceFollowRedirects)
            assertEquals("", probe.getRequestProperty("Cookie"))
            assertTrue(probe.disconnected)
        }
    }

    @Test fun mismatchedClientCannotForwardPasswordAndLoginRedirectIsNotFollowed() = runBlocking {
        var calls = 0
        val wire = WireTransport { _, _, _, _, _ -> calls++; WireResponse(302, byteArrayOf()) }
        val gate = BookHubConnectionGate(LoginOriginVerifier {}, { BookHubClient(ServiceOrigin("https://other.fixture.invalid"), wire) })
        try { gate.login(fixture.origin.value, "fixture-user", "fixture-password"); fail("Expected origin mismatch") }
        catch (_: SourceProblem) { }
        assertEquals(0, calls)
        val same = BookHubConnectionGate(LoginOriginVerifier {}, { BookHubClient(it, wire) })
        try { same.login(fixture.origin.value, "fixture-user", "fixture-password"); fail("Expected redirect error") }
        catch (_: SourceProblem) { }
        assertEquals(1, calls)
    }

    @Test fun upgradeKeepsLegacyAddressSettingsAndLocalCharacterPositionWithoutAutoLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val app: Application = ApplicationProvider.getApplicationContext()
            app.getSharedPreferences("own-server", 0).edit().putString("origin", "https://legacy.fixture.invalid").commit()
            val old = ReadingStore(app, RetiredBundledBooks.SOURCE_ID)
            old.saveSettings(ReaderSettings(fontSp = 25f, lineSpacing = 1.75f, palette = 4))
            old.save("demo-1", "chapter-2", 123)
            var probes = 0
            val vm = ReaderViewModel(app, connectionGate = BookHubConnectionGate(LoginOriginVerifier { probes++ }))
            advanceUntilIdle()
            assertEquals(0, probes)
            assertEquals("https://legacy.fixture.invalid", vm.ownOrigin())
            assertTrue(InitialBookHubTarget.migrate(vm.ownOrigin(), BuiltInBookHub.confirmedEntry).useCustom)
            assertEquals(25f, vm.ui.value.settings.fontSp)
            assertEquals(1.75f, vm.ui.value.settings.lineSpacing)
            assertEquals(4, vm.ui.value.settings.palette)
            assertEquals(123, vm.store.position("demo-1")!!.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun switchingServersPreservesOldSessionUntilSuccessfulLoginAndKeepsProgressIsolated() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val clients = mutableListOf<BookHubClient>()
            var reject = false
            val wire = WireTransport { uri, _, headers, _, _ ->
                val data = if (uri.path.endsWith("/auth/login")) {
                    assertNull(headers["Authorization"]); BookHubFixtures.session()
                } else JSONObject().put("items", JSONArray()).put("nextCursor", JSONObject.NULL).put("hasMore", false)
                WireResponse(200, BookHubFixtures.envelope(data).toByteArray())
            }
            val gate = BookHubConnectionGate(LoginOriginVerifier {
                if (reject) throw SourceProblem(FailureKind.NETWORK, "fixture TLS rejected")
            }, { BookHubClient(it, wire).also(clients::add) })
            val app: Application = ApplicationProvider.getApplicationContext()
            val local = object : BookSource {
                override val id = "login-policy-local"
                override suspend fun books() = emptyList<Book>()
                override suspend fun chapters(bookId: String) = emptyList<ChapterRef>()
                override suspend fun chapter(bookId: String, chapterId: String) = Chapter(chapterId, "", emptyList())
            }
            val vm = ReaderViewModel(app, local, gate, MemorySessionVault())
            vm.connect("https://first.fixture.invalid", "fixture-user", "fixture-password")
            advanceUntilIdle()
            assertTrue(vm.ui.value.remote)
            val oldStore = vm.store
            oldStore.save("public:1", "fixture-chapter", 56)
            reject = true
            vm.connect("https://second.fixture.invalid", "fixture-user", "fixture-password")
            assertNotNull(clients.first().accountId)
            advanceUntilIdle()
            assertTrue(vm.ui.value.remote) // Failed target selection keeps the previous session and shelf.
            assertNotNull(vm.ui.value.connectionError)
            assertEquals(1, clients.size)
            assertEquals(56, oldStore.position("public:1")!!.offset)
            reject = false
            vm.connect("https://second.fixture.invalid", "fixture-user", "fixture-password")
            advanceUntilIdle()
            assertTrue(vm.ui.value.remote)
            assertNull(vm.store.position("public:1"))
            assertEquals(56, oldStore.position("public:1")!!.offset)
            assertEquals("https://second.fixture.invalid", vm.ownOrigin())
        } finally { Dispatchers.resetMain() }
    }
}
