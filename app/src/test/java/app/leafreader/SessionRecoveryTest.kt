package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import app.leafreader.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SessionRecoveryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val origin = "https://session.fixture.invalid"
    private val saved = SavedSession(origin, "fixture-account", "fixture-device", "fixture-access", "fixture-refresh")
    private val local = object : BookSource {
        override val id = "session-local"
        override suspend fun books() = listOf(Book("local", "离线书", "自写", "", "本地", 1, 0))
        override suspend fun chapters(bookId: String) = listOf(ChapterRef("local", "本地"))
        override suspend fun chapter(bookId: String, chapterId: String) = Chapter("local", "本地", listOf("自写正文。".repeat(80)))
    }
    /** Real AES-GCM primitive, test key only. Android Keystore provider is a device check, not simulated here. */
    private class TestCipher : SessionCipher {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        var fail = false
        override fun encrypt(plain: ByteArray): ByteArray {
            if (fail) throw GeneralSecurityException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
            return cipher.iv + cipher.doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray): ByteArray {
            if (fail) throw GeneralSecurityException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            return cipher.doFinal(sealed, 12, sealed.size - 12)
        }
    }
    private class Wire : WireTransport {
        var logins = 0; var refreshes = 0; var offline = false; var expire = false; var revoke = false; var deny = false
        var refreshNetworkFailure = false; var refreshExpired = false
        override suspend fun execute(uri: java.net.URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse {
            assertEquals("session.fixture.invalid", uri.host)
            if (offline) throw SourceProblem(FailureKind.NETWORK, "fixture offline")
            if (uri.path.endsWith("/auth/login")) { logins++; return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray()) }
            if (uri.path.endsWith("/auth/refresh")) {
                refreshes++; assertFalse(String(body!!).contains("password"))
                if (refreshNetworkFailure) throw SourceProblem(FailureKind.NETWORK, "fixture refresh timeout")
                if (refreshExpired) return WireResponse(401, "{\"code\":\"REFRESH_EXPIRED\"}".toByteArray())
                return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session().put("accessToken", "fixture-access-new").put("refreshToken", "fixture-refresh-new")).toByteArray())
            }
            if (revoke) return WireResponse(401, "{\"code\":\"SESSION_REVOKED\"}".toByteArray())
            if (deny) return WireResponse(403, byteArrayOf())
            if (expire && headers["Authorization"] == "Bearer fixture-access") return WireResponse(401, "{\"code\":\"SESSION_EXPIRED\"}".toByteArray())
            return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
        }
        fun gate() = BookHubConnectionGate(LoginOriginVerifier {}, { BookHubClient(it, this) })
    }

    @Test fun encryptedPreferencesRoundTripContainNoPlaintextAndTamperOrKeyFailureSafelyRequiresRelogin() {
        val cipher = TestCipher(); val vault = EncryptedSessionVault(app, cipher)
        assertTrue(vault.save(saved)); assertEquals(saved, (vault.load() as SessionLoad.Ready).session)
        val prefs = app.getSharedPreferences("bookhub-session-sealed-v1", 0)
        val raw = prefs.all.toString()
        for (secret in listOf(saved.accessToken, saved.refreshToken, saved.accountId, origin, "password")) assertFalse(raw.contains(secret))
        assertEquals("SavedSession(redacted)", saved.toString())
        val first = prefs.getString("sealed", null)
        assertTrue(vault.save(saved)); assertNotEquals(first, prefs.getString("sealed", null))
        prefs.edit().putString("sealed", "tampered").commit()
        assertEquals(SessionLoad.Unavailable, vault.load()); assertTrue(prefs.all.isEmpty())
        assertTrue(vault.save(saved)); cipher.fail = true
        assertEquals(SessionLoad.Unavailable, vault.load()); assertFalse(vault.save(saved)); assertTrue(prefs.all.isEmpty())
        vault.clear(); assertEquals(SessionLoad.Empty, vault.load())
    }

    @Test fun coldViewModelRestoresTokensWithoutPasswordLoginAndOfflineDoesNotLogOut() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vault = MemorySessionVault(); val wire = Wire()
            val first = ReaderViewModel(app, local, wire.gate(), vault); advanceUntilIdle()
            first.connect(origin, "fixture-user", "fixture-password"); advanceUntilIdle()
            assertEquals(1, wire.logins); assertNotNull(vault.value)
            val keys = first.ui.value.shelfBooks.map { it.key }.toSet()
            wire.offline = true
            val cold = ReaderViewModel(app, local, wire.gate(), vault); advanceUntilIdle()
            assertTrue(cold.ui.value.remote); assertNull(cold.ui.value.shelfError); cold.loadShelf();advanceUntilIdle();assertNotNull(cold.ui.value.shelfError); assertNotNull(vault.value)
            assertEquals(keys, cold.ui.value.shelfBooks.map { it.key }.toSet()); assertEquals(1, wire.logins)
            wire.offline = false; wire.expire = true; cold.loadShelf(); advanceUntilIdle()
            assertEquals(1, wire.refreshes); assertEquals("fixture-access-new", vault.value!!.accessToken)
            val again = ReaderViewModel(app, local, wire.gate(), vault); advanceUntilIdle()
            assertTrue(again.ui.value.remote); assertEquals(1, wire.refreshes); assertEquals(1, wire.logins)
            again.signOut(); advanceUntilIdle(); assertNull(vault.value)
            assertFalse(ReaderViewModel(app, local, wire.gate(), vault).ui.value.remote)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun ordinaryPermissionDenialKeepsSessionButRevocationClearsTokensAndPreservesShelf() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vault = MemorySessionVault(); val wire = Wire(); val vm = ReaderViewModel(app, local, wire.gate(), vault); advanceUntilIdle()
            vm.connect(origin, "fixture-user", "fixture-password"); advanceUntilIdle()
            val keys = vm.ui.value.shelfBooks.map { it.key }
            wire.deny = true; vm.loadShelf(); advanceUntilIdle(); assertNotNull(vault.value); assertEquals(keys, vm.ui.value.shelfBooks.map { it.key })
            wire.deny = false; wire.revoke = true; vm.loadShelf(); advanceUntilIdle()
            assertNull(vault.value); assertNull(vm.ui.value.notice);assertTrue(vm.ui.value.sourceLabel.contains("重新登录")); assertEquals(keys, vm.ui.value.shelfBooks.map { it.key })
            assertEquals(0, wire.refreshes)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun refreshNetworkFailureKeepsTokensButExpiredRefreshRequiresReloginWithoutDeletingBooks() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vault = MemorySessionVault(); val wire = Wire(); val vm = ReaderViewModel(app, local, wire.gate(), vault); advanceUntilIdle()
            vm.connect(origin, "fixture", "fixture-password"); advanceUntilIdle()
            val keys = vm.ui.value.shelfBooks.map { it.key }; wire.expire = true; wire.refreshNetworkFailure = true
            vm.loadShelf(); advanceUntilIdle(); assertNotNull(vault.value); assertEquals(1, wire.refreshes)
            wire.refreshNetworkFailure = false; wire.refreshExpired = true
            vm.loadShelf(); advanceUntilIdle(); assertNull(vault.value); assertEquals(2, wire.refreshes)
            assertNull(vm.ui.value.notice);assertTrue(vm.ui.value.sourceLabel.contains("重新登录")); assertEquals(keys, vm.ui.value.shelfBooks.map { it.key })
        } finally { Dispatchers.resetMain() }
    }

    @Test fun retainedViewModelStoreReusesSessionAndANewStoreRestoresWithoutAnotherLogin() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val vault = MemorySessionVault(); val wire = Wire()
            val factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(app, local, wire.gate(), vault) as T
            }
            val retained = ViewModelStore()
            val before = ViewModelProvider(retained, factory)[ReaderViewModel::class.java]; advanceUntilIdle()
            before.connect(origin, "fixture", "fixture-password"); advanceUntilIdle()
            val recreatedOwner = ViewModelProvider(retained, factory)[ReaderViewModel::class.java]
            assertSame(before, recreatedOwner); assertEquals(1, wire.logins); assertTrue(recreatedOwner.ui.value.remote)
            retained.clear()
            val cold = ViewModelProvider(ViewModelStore(), factory)[ReaderViewModel::class.java]; advanceUntilIdle()
            assertNotSame(before, cold); assertEquals(1, wire.logins); assertTrue(cold.ui.value.remote)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun concurrentExpiredRequestsRefreshOnceAndLateRefreshCannotResurrectClearedSession() = runTest {
        var oldCalls = 0; var refreshes = 0; val both = CompletableDeferred<Unit>()
        val wire = WireTransport { uri, _, headers, _, _ ->
            if (uri.path.endsWith("/auth/refresh")) { refreshes++; WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session().put("accessToken", "new")).toByteArray()) }
            else if (headers["Authorization"] == "Bearer fixture-access") {
                oldCalls++; if (oldCalls == 2) both.complete(Unit); both.await()
                WireResponse(401, "{\"code\":\"SESSION_EXPIRED\"}".toByteArray())
            } else WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
        }
        val client = BookHubClient(ServiceOrigin(origin), wire); client.restore(saved)
        val a = async { client.api("/me/bookshelf") }; val b = async { client.api("/me/bookshelf") }
        a.await(); b.await(); assertEquals(1, refreshes)

        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val late = BookHubClient(ServiceOrigin(origin), WireTransport { uri, _, _, _, _ ->
            if (uri.path.endsWith("/auth/refresh")) {
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
            } else WireResponse(401, "{\"code\":\"SESSION_EXPIRED\"}".toByteArray())
        }); late.restore(saved)
        val result = async { runCatching { late.api("/me/bookshelf") } }
        entered.await(); late.clearSession(); release.complete(Unit)
        assertTrue(result.await().exceptionOrNull() is CancellationException); assertNull(late.snapshot())
    }

    @Test fun lateLoginAfterExitNeverRestoresTokensOrChangesTheLocalShelf() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val vault = MemorySessionVault()
            val clients = mutableListOf<BookHubClient>()
            val gate = BookHubConnectionGate(LoginOriginVerifier {}, { target -> BookHubClient(target, WireTransport { _, _, _, _, _ ->
                entered.complete(Unit); withContext(NonCancellable) { release.await() }
                WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session()).toByteArray())
            }).also(clients::add) })
            val vm = ReaderViewModel(app, local, gate, vault); advanceUntilIdle()
            vm.connect(origin, "fixture", "fixture-password"); runCurrent(); entered.await()
            vm.signOut(); release.complete(Unit); advanceUntilIdle()
            assertFalse(vm.ui.value.remote); assertNull(vault.value); assertNull(clients.single().snapshot())
            assertEquals(setOf("local"), vm.ui.value.shelfBooks.map { it.id }.toSet())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun undecryptableSessionFallsBackToLocalWithoutDeletingProgress() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            ReadingStore(app, local.id).save("local", "local", 18)
            val vault = object : SessionVault {
                override fun load() = SessionLoad.Unavailable
                override fun save(session: SavedSession) = false
                override fun clear() {}
            }
            val vm = ReaderViewModel(app, local, Wire().gate(), vault); advanceUntilIdle()
            assertFalse(vm.ui.value.remote); assertEquals(18, vm.store.position("local")!!.offset)
            assertTrue(vm.ui.value.notice!!.contains("无法解密")); assertEquals(setOf("local"), vm.ui.value.shelfBooks.map { it.id }.toSet())
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateBodyAfterLogoutCannotWriteAPositionOrReplaceTheShelf() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val release = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>(); val vault = MemorySessionVault()
            val gate = BookHubConnectionGate(LoginOriginVerifier {}, { target -> BookHubClient(target, WireTransport { uri, _, _, _, _ ->
                val text = when {
                    uri.path.endsWith("/auth/login") -> BookHubFixtures.envelope(BookHubFixtures.session())
                    uri.path.endsWith("/bookshelf") -> BookHubFixtures.envelope(BookHubFixtures.page())
                    uri.path.endsWith("/access") -> BookHubFixtures.envelope(BookHubFixtures.txtManifest())
                    else -> { entered.complete(Unit); withContext(NonCancellable) { release.await() }; BookHubFixtures.firstText }
                }
                WireResponse(200, text.toByteArray())
            }) })
            val vm = ReaderViewModel(app, local, gate, vault); advanceUntilIdle()
            vm.connect(origin, "fixture", "fixture-password"); advanceUntilIdle()
            val remote = vm.ui.value.shelfBooks.single { it.id == "public:1" }; val accountStore = vm.store
            vm.open(remote); runCurrent(); entered.await(); vm.signOut(); release.complete(Unit); advanceUntilIdle()
            assertNull(accountStore.position(remote.id)); assertNull(vm.ui.value.book); assertNull(vault.value)
            assertEquals(setOf("local","public:1"), vm.ui.value.shelfBooks.map { it.id }.toSet())
        } finally { Dispatchers.resetMain() }
    }
}
