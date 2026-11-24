package app.leafreader

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import java.net.URI
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.reader.NativeReaderView
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookHubAppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun reader(view: View = compose.activity.window.decorView): NativeReaderView? {
        if (view is NativeReaderView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) reader(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun verifiedFixtureLoginSearchDirectoryAndNativeResume() {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                val data = when {
                    path.endsWith("/auth/login") -> BookHubFixtures.session()
                    path.endsWith("/bookshelf") -> BookHubFixtures.page()
                    path.endsWith("/catalog/search") -> BookHubFixtures.page("public:2")
                    path.endsWith("/access") -> BookHubFixtures.txtManifest().put("bookId", request.requestUrl!!.pathSegments[3])
                    path.endsWith("/a") -> return MockResponse().setBody(BookHubFixtures.firstText)
                    path.endsWith("/b") -> return MockResponse().setBody(BookHubFixtures.secondText)
                    else -> return MockResponse().setResponseCode(404)
                }
                return MockResponse().setBody(BookHubFixtures.envelope(data))
            }
        }
        server.start()
        try {
            // HTTPS selection and verification are fake; only the self-authored loopback fixture receives test credentials.
            val gate = BookHubConnectionGate(LoginOriginVerifier { assertEquals("https://flow.fixture.invalid", it.value) }, { origin ->
                BookHubClient(origin, WireTransport { uri, method, headers, body, limit ->
                    assertEquals("flow.fixture.invalid", uri.host)
                    val local = URI("http://127.0.0.1:${server.port}${uri.rawPath}${uri.rawQuery?.let { "?$it" }.orEmpty()}")
                    UrlConnectionTransport().execute(local, method, headers, body, limit)
                })
            })
            lateinit var vm: ReaderViewModel
            compose.runOnUiThread {
                vm = ReaderViewModel(ApplicationProvider.getApplicationContext<Application>(), connectionGate = gate, sessionVault = MemorySessionVault(),linkBookHubShelf=false)
                compose.activity.setContent { MaterialTheme { ReaderApp(vm) } }
            }
            compose.waitUntil(30000) { !vm.ui.value.shelfLoading }
            compose.onNodeWithTag("tab-settings").performClick()
            compose.onNodeWithTag("settings-source").performClick()
            compose.onNodeWithText("我的服务地址（HTTPS）").performScrollTo().performTextInput("https://flow.fixture.invalid")
            compose.onNodeWithTag("confirm-source-target").performClick()
            compose.onNodeWithText("服务账号").performScrollTo().performTextInput("fixture-user")
            compose.onNodeWithText("服务密码").performScrollTo().performTextInput("fixture-password")
            compose.onNode(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role,androidx.compose.ui.semantics.Role.Checkbox)).performScrollTo().performClick()
            compose.onNodeWithTag("bookhub-login-submit").performClick()
            compose.waitUntil(30000) { compose.waitForIdle();vm.ui.value.remote && !vm.ui.value.shelfLoading }
            compose.onNodeWithTag("tab-settings").assertIsSelected()
            compose.onNodeWithTag("tab-find").performClick()
            compose.onNodeWithTag("find-search-input").performTextInput("灯")
            compose.onNodeWithTag("find-search-submit").performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithTag("find-book-public:2").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("find-directory-public:2").performScrollTo().performClick()
            compose.waitUntil(30000) { compose.onAllNodesWithText("第二章　归岸").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle {
                assertNull(vm.store.position("public:2")); assertEquals(0L, vm.store.lastEnteredAt("public:2"))
            }
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithTag("find-read-public:2").performScrollTo().performClick()
            compose.waitUntil(30000) { reader() != null }
            compose.runOnIdle { assertTrue(reader()!!.contentDescription.contains("第一章")); reader()!!.performClick() }
            compose.waitForIdle()
            compose.onNodeWithText("目录").assertIsDisplayed().performClick()
            try {
                compose.waitUntil(30000) { compose.waitForIdle();compose.onAllNodesWithText("第二章　归岸").fetchSemanticsNodes().isNotEmpty() }
            } catch(e:Exception) {
                println("Fixture directory failure: requests=${server.requestCount}, loading=${vm.ui.value.loading}, error=${vm.ui.value.error}")
                fun dump(n:androidx.compose.ui.semantics.SemanticsNode) {println(n.config);n.children.forEach(::dump)}
                compose.onAllNodes(isRoot()).fetchSemanticsNodes().forEach(::dump)
                throw e
            }
            compose.onNodeWithText("第二章　归岸").performScrollTo().performClick()
            compose.waitUntil(30000) { reader()?.contentDescription?.contains("第二章") == true }
            var position = 0
            compose.runOnIdle { reader()!!.turn(1); position = reader()!!.currentOffset; assertTrue(position > 0); reader()!!.performClick() }
            compose.onNodeWithText("←  书架").performClick()
            compose.onNodeWithTag("shelf-book-public:2").assertDoesNotExist()
            compose.onNodeWithTag("tab-find").performClick()
            compose.onNodeWithTag("add-shelf-public:2").performScrollTo().performClick()
            compose.onNodeWithTag("tab-shelf").performClick()
            compose.onNodeWithTag("shelf-book-public:2").performScrollTo().performClick()
            compose.waitUntil(30000) { reader() != null }
            compose.runOnIdle { assertTrue(reader()!!.contentDescription.contains("第二章")); assertEquals(position, reader()!!.currentOffset) }
            assertTrue(server.requestCount >= 7)
        } finally { server.shutdown() }
    }
}
