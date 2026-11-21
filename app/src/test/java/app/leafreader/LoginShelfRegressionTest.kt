package app.leafreader

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LoginShelfRegressionTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val local = object : BookSource {
        override val id = "original-offline-v1"
        override suspend fun books() = listOf(Book("local-a", "本地甲书", "自写", "", "本地", 1, 0), Book("local-b", "本地乙书", "自写", "", "本地", 1, 0))
        override suspend fun chapters(bookId: String) = listOf(ChapterRef("local-chapter", "本地章"))
        override suspend fun chapter(bookId: String, chapterId: String) = Chapter(chapterId, "本地章", listOf("保留的离线正文。".repeat(70)))
    }
    private class Wire : WireTransport {
        var shelf = BookHubFixtures.page()
        var failShelf = false
        var failLogin = false
        val clients = mutableListOf<BookHubClient>()
        override suspend fun execute(uri: java.net.URI, method: String, headers: Map<String, String>, body: ByteArray?, maxBytes: Int): WireResponse {
            if (uri.path.endsWith("/auth/login")) {
                assertNull(headers["Authorization"])
                if (failLogin) return WireResponse(401, byteArrayOf())
                val user = JSONObject(String(body!!)).getString("username")
                return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.session(user)).toByteArray())
            }
            if (uri.path.endsWith("/bookshelf")) return if (failShelf) WireResponse(500, byteArrayOf()) else WireResponse(200, BookHubFixtures.envelope(shelf).toByteArray())
            if (uri.path.endsWith("/access")) return WireResponse(200, BookHubFixtures.envelope(BookHubFixtures.txtManifest()).toByteArray())
            return WireResponse(200, (if (uri.path.endsWith("/a")) BookHubFixtures.firstText else BookHubFixtures.secondText).toByteArray())
        }
        fun gate() = BookHubConnectionGate(LoginOriginVerifier {}, { BookHubClient(it, this).also(clients::add) })
        fun empty() { shelf = JSONObject().put("items", JSONArray()).put("hasMore", false).put("nextCursor", JSONObject.NULL) }
    }
    private fun legacyLocal() {
        // Exact pre-0.5 layout: source is implicit in the preference filename, no sourceId in book JSON.
        app.getSharedPreferences("reader-${local.id}", 0).edit()
            .putString("serviceShelf", """[{"id":"local-a","title":"本地甲书","author":"自写","color":0,"format":"TXT"},{"id":"local-b","title":"本地乙书","author":"自写","color":0,"format":"TXT"}]""")
            .putString("local-a.chapter", "local-chapter").putInt("local-a.offset", 81).putLong("local-a.time", 42)
            .putFloat("font", 27f).putFloat("spacing", 1.8f).putInt("palette", 4).commit()
    }

    @Test fun upgradeLoginSuccessFailureEmptyShelfLogoutAndColdStartPreserveLocalBooksAndLayout() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            legacyLocal(); val wire = Wire(); wire.empty()
            val vm = ReaderViewModel(app, local, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            val before = vm.ui.value.shelfBooks.map { it.key }; val settings = vm.ui.value.settings
            assertEquals(27f, settings.fontSp); assertEquals(4, settings.palette)
            vm.connect("https://shelf.fixture.invalid", "account-a", "fixture-password")
            assertEquals(before, vm.ui.value.shelfBooks.map { it.key })
            advanceUntilIdle(); assertTrue(vm.ui.value.remote)
            assertEquals(before, vm.ui.value.shelfBooks.map { it.key }); assertEquals(settings, vm.ui.value.settings)
            val book = vm.ui.value.shelfBooks.first()
            vm.open(book); advanceUntilIdle(); assertEquals(81, vm.ui.value.offset)
            assertTrue(vm.ui.value.chapter!!.text.contains("保留的离线正文"))
            vm.position(99); vm.backToShelf(); assertNull(vm.store.position(book.id)) // never writes local progress in the account store
            wire.failLogin = true; vm.connect("https://shelf.fixture.invalid", "account-b", "fixture-password"); advanceUntilIdle()
            assertNotNull(vm.ui.value.connectionError); assertEquals(before, vm.ui.value.shelfBooks.map { it.key }); assertEquals(settings, vm.ui.value.settings)
            vm.signOut(); advanceUntilIdle(); assertEquals(before, vm.ui.value.shelfBooks.map { it.key })
            vm.settings(settings.copy(paragraphSpacingDp = 33f))
            val cold = ReaderViewModel(app, local, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            assertEquals(before, cold.ui.value.shelfBooks.map { it.key }); assertEquals(33f, cold.ui.value.settings.paragraphSpacingDp)
            cold.open(cold.ui.value.shelfBooks.first()); advanceUntilIdle(); assertEquals(99, cold.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun sameAccountCachedShelfAndProgressSurviveEmptyResponseLoadErrorReloginAndRestart() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wire = Wire(); val vm = ReaderViewModel(app, local, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            vm.connect("https://shelf.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            val remote = vm.ui.value.shelfBooks.single { it.id == "public:1" }
            val localKeys = vm.ui.value.shelfBooks.filter { it.sourceId == local.id }.map { it.key }
            vm.open(remote); advanceUntilIdle(); vm.position(53); vm.backToShelf()
            wire.failShelf = true; vm.loadShelf(); advanceUntilIdle()
            assertNotNull(vm.ui.value.shelfError); assertTrue(vm.ui.value.shelfBooks.any { it.key == remote.key })
            wire.failShelf = false; wire.empty(); vm.loadShelf(); advanceUntilIdle()
            assertTrue(vm.ui.value.shelfBooks.any { it.key == remote.key })
            vm.connect("https://shelf.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            assertEquals(53, vm.positionOf(remote)!!.offset)
            vm.signOut(); advanceUntilIdle()
            assertEquals(localKeys.toSet(), vm.ui.value.shelfBooks.map { it.key }.toSet())
            val cold = ReaderViewModel(app, local, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            cold.connect("https://shelf.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            assertEquals(1, cold.ui.value.shelfBooks.count { it.key == remote.key })
            cold.open(cold.ui.value.shelfBooks.first { it.key == remote.key }); advanceUntilIdle()
            assertEquals(53, cold.ui.value.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun accountAndOriginSwitchesNeverReusePositionsOrOldSessionsAndFailedLoginKeepsShelf() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val wire = Wire(); val vm = ReaderViewModel(app, local, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            vm.connect("https://first.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            val a = vm.ui.value.shelfBooks.single { it.id == "public:1" }
            vm.open(a); advanceUntilIdle(); vm.position(77); vm.backToShelf()
            vm.connect("https://first.fixture.invalid", "account-b", "fixture-password"); advanceUntilIdle()
            assertNull(wire.clients.first().accountId)
            val b = vm.ui.value.shelfBooks.single { it.id == "public:1" }
            assertNotEquals(a.key, b.key); assertNull(vm.positionOf(b)); assertEquals(77, vm.positionOf(a)!!.offset)
            vm.open(a); advanceUntilIdle(); assertNotNull(vm.ui.value.error); assertNull(vm.ui.value.chapter)
            vm.backToShelf(); vm.open(b); advanceUntilIdle(); assertEquals(0, vm.ui.value.offset); vm.position(29); vm.backToShelf()
            val keys = vm.ui.value.shelfBooks.map { it.key }; wire.failLogin = true
            vm.connect("https://second.fixture.invalid", "account-b", "fixture-password"); advanceUntilIdle()
            assertEquals(keys, vm.ui.value.shelfBooks.map { it.key }); assertEquals(29, vm.positionOf(b)!!.offset)
            vm.open(b); advanceUntilIdle(); assertTrue(vm.ui.value.error!!.contains("BH_AUTH")); vm.backToShelf()
            wire.failLogin = false; vm.connect("https://second.fixture.invalid", "account-b", "fixture-password"); advanceUntilIdle()
            val other = vm.ui.value.shelfBooks.single { it.id == "public:1" }
            assertNotEquals(b.key, other.key); assertNull(vm.positionOf(other)); assertEquals(77, vm.positionOf(a)!!.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun identicalRawIdsInLocalAndBookHubRouteToTheirOwnTextAndProgress() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val collision = object : BookSource {
                override val id = "local-id-collision"
                override suspend fun books() = listOf(Book("public:1", "同ID本地书", "自写", "", "本地", 1, 0))
                override suspend fun chapters(bookId: String) = listOf(ChapterRef("local", "本地"))
                override suspend fun chapter(bookId: String, chapterId: String) = Chapter("local", "本地", listOf("这是本地文字。".repeat(80)))
            }
            val wire = Wire(); val vm = ReaderViewModel(app, collision, wire.gate(), MemorySessionVault()); advanceUntilIdle()
            vm.connect("https://collision.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            val sameIds = vm.ui.value.shelfBooks.filter { it.id == "public:1" }; assertEquals(2, sameIds.size); assertEquals(2, sameIds.map { it.key }.distinct().size)
            val localBook = sameIds.single { it.sourceId == collision.id }; val remote = sameIds.single { it.sourceId != collision.id }
            vm.open(localBook); advanceUntilIdle(); assertTrue(vm.ui.value.chapter!!.text.contains("这是本地文字")); vm.position(19); vm.backToShelf()
            vm.open(remote); advanceUntilIdle(); assertTrue(vm.ui.value.chapter!!.text.contains("灯亮了")); vm.position(45); vm.backToShelf()
            assertEquals(19, vm.positionOf(localBook)!!.offset); assertEquals(45, vm.positionOf(remote)!!.offset)
        } finally { Dispatchers.resetMain() }
    }

    @Test fun lateCancelledShelfRefreshCannotReTagOldSourceBooksIntoTheNewAccountCache() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val localA = Book("local-a", "旧本地", "自写", "", "本地", 1, 0)
            val localB = localA.copy(id = "late-local", title = "晚到本地结果")
            val slow = object : BookSource {
                override val id = "late-local-shelf"
                override suspend fun books() = emptyList<Book>()
                override suspend fun browse(query: String, cursor: String?): BookPage {
                    entered.complete(Unit); withContext(NonCancellable) { release.await() }; return BookPage(listOf(localA, localB))
                }
                override suspend fun chapters(bookId: String) = emptyList<ChapterRef>()
                override suspend fun chapter(bookId: String, chapterId: String) = Chapter(chapterId, "", emptyList())
            }
            ReadingStore(app, slow.id).rememberServiceShelf(listOf(localA))
            val wire = Wire(); val vm = ReaderViewModel(app, slow, wire.gate(), MemorySessionVault())
            runCurrent(); entered.await(); vm.connect("https://shelf.fixture.invalid", "account-a", "fixture-password"); advanceUntilIdle()
            release.complete(Unit); advanceUntilIdle(); vm.backToShelf(); vm.loadShelf(); advanceUntilIdle()
            assertEquals(setOf("local-a", "public:1"), vm.ui.value.shelfBooks.map { it.id }.toSet())
            assertFalse(vm.store.savedServiceShelf().any { it.id == "late-local" })
        } finally { Dispatchers.resetMain() }
    }
}
