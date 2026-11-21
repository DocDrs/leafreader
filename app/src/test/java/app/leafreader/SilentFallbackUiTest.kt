package app.leafreader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SilentFallbackUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun independentMissingAnchorReadsSilentlyButRealResourceAndAuthErrorsRemainVisible() {
        val manifest=BookHubFixtures.epubManifest().put("toc",JSONArray().put(JSONObject()
            .put("label","自写独立章").put("resourceId","a").put("href","/api/v1/books/public%3A1/resources/a#stale")))
        var bodyStatus=200;var accessStatus=200
        val client=BookHubClient(ServiceOrigin("https://silent-fallback.fixture.invalid"),WireTransport{uri,_,_,_,_->
            when {
                uri.path.endsWith("/bookshelf")->WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.page()).toByteArray())
                uri.path.endsWith("/access")->WireResponse(accessStatus,(if(accessStatus==200)BookHubFixtures.envelope(manifest) else "{\"code\":\"SESSION_REVOKED\"}").toByteArray())
                else->WireResponse(bodyStatus,BookHubFixtures.epubText.toByteArray(),"application/xhtml+xml")
            }
        });client.restore(SavedSession(client.origin.value,"self-authored-account","self-authored-device","self-authored-access","self-authored-refresh"))
        val source=BookHubSource(client)
        lateinit var vm:ReaderViewModel
        compose.runOnUiThread{
            compose.activity.viewModelStore.clear()
            vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T =
                    ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault()) as T
            })[ReaderViewModel::class.java]
            compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
        }
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.shelfLoading};val book=vm.ui.value.shelfBooks.single()
        compose.onNodeWithTag("shelf-book-${book.id}").performClick()
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.loading && vm.ui.value.chapter!=null}
        compose.onNodeWithText("知道了").assertDoesNotExist()
        compose.onNode(hasText("目录锚点缺失",substring=true)).assertDoesNotExist()
        compose.runOnIdle{assertNull(vm.ui.value.notice);assertNull(vm.ui.value.error);vm.position(9);vm.backToShelf();source.forgetDownloaded(book.id);bodyStatus=503}
        val saved=vm.store.position(book.id)
        compose.onNodeWithTag("shelf-book-${book.id}").performClick()
        compose.waitUntil(15000){compose.waitForIdle();vm.ui.value.error!=null}
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle{assertEquals(saved,vm.store.position(book.id));vm.backToShelf();source.forgetDownloaded(book.id);accessStatus=401}
        compose.onNodeWithTag("shelf-book-${book.id}").performClick()
        compose.waitUntil(15000){compose.waitForIdle();vm.ui.value.error!=null}
        compose.onNodeWithText("重试").assertIsDisplayed()
        compose.runOnIdle{assertEquals(saved,vm.store.position(book.id));assertNull(vm.ui.value.chapter)}
    }
}
