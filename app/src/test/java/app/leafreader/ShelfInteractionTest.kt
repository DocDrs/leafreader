package app.leafreader

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w320dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShelfInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:ReaderViewModel
    private lateinit var runtime:OfflineDownloads
    private lateinit var wire:OfflineFixtures
    private fun setup() {
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            val app=ApplicationProvider.getApplicationContext<Application>()
            wire=OfflineFixtures();val vault=MemorySessionVault().apply{value=wire.client().snapshot()}
            val transport=WireTransport { uri,method,headers,body,max ->
                if(uri.path.endsWith("/auth/login")) WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.session(JSONObject(String(body!!)).getString("username"))).toByteArray())
                else wire.execute(uri,method,headers,body,max)
            }
            runtime=OfflineDownloads(app,wire.network,vault,false)
            vm=androidx.lifecycle.ViewModelProvider(compose.activity, object:androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T =
                    ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{}, {BookHubClient(it,transport)}),sessionVault=vault,offlineRuntime=runtime) as T
            })[ReaderViewModel::class.java]
            compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
        }
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.isNotEmpty()}
    }
    @Test fun compactRowContainsBookMetadataAndLongPressNeverOpensButTapResumes() {
        setup();val book=vm.ui.value.shelfBooks.single()
        val row=compose.onNodeWithTag("shelf-book-${book.id}")
        val text=row.fetchSemanticsNode().config[SemanticsProperties.Text].map{it.text}
        assertEquals(listOf(book.title,book.author,book.description),text)
        compose.onNodeWithTag("download-status-${book.id}").assertDoesNotExist()
        compose.onNodeWithTag("shelf-directory-${book.id}").assertDoesNotExist()
        row.performTouchInput{longClick()}
        compose.onNodeWithTag("shelf-book-actions").assertIsDisplayed()
        compose.onNodeWithTag("download-status-${book.id}").assertIsDisplayed()
        compose.runOnIdle{assertNull(vm.ui.value.book);assertNull(vm.store.position(book.id));assertEquals(0L,vm.store.lastEnteredAt(book.id))}
        compose.onNodeWithText("关闭").performClick();row.performClick()
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.loading && vm.ui.value.book!=null}
        compose.runOnIdle{assertNull(vm.ui.value.error);assertNotNull(vm.ui.value.chapter)}
        compose.onNodeWithTag("directory-list").assertDoesNotExist()
        compose.runOnIdle{assertNull(vm.ui.value.error);val textChapter=vm.ui.value.chapters[1];vm.open(book,textChapter.id)}
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.loading && vm.ui.value.graphicId==null}
        compose.runOnIdle{vm.position(17);vm.backToShelf()}
        row.performClick();compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.loading && vm.ui.value.chapter!=null}
        compose.runOnIdle{assertEquals(17,vm.ui.value.offset);assertNull(vm.ui.value.error)}
    }
    @Test fun accessibleMenuUpdatesAfterCancelAndClosesOnAccountSwitchWithSameBookId() {
        setup();val a=vm.ui.value.shelfBooks.single()
        fun menu()=compose.onNodeWithTag("shelf-book-${a.id}").performSemanticsAction(SemanticsActions.OnLongClick){it()}
        menu();compose.onNodeWithTag("download-book-${a.id}").performClick()
        compose.onNodeWithText("加入下载队列").performClick()
        menu();compose.onNodeWithTag("cancel-download-${a.id}").assertIsDisplayed().performClick()
        compose.waitUntil(15000){compose.waitForIdle();runtime.request(runtime.key(a.sourceId,a.id))?.state=="CANCELLED"}
        compose.waitUntil(15000){compose.waitForIdle();compose.onAllNodesWithTag("download-book-${a.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("download-status-${a.id}").assertIsDisplayed()
        compose.runOnIdle{assertNull(vm.ui.value.book);vm.connect("https://offline.fixture.invalid","second-synthetic-account","synthetic-password")}
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.connecting && vm.ui.value.shelfBooks.any{it.sourceId!=a.sourceId}}
        compose.onNodeWithTag("shelf-book-actions").assertDoesNotExist()
        val b=vm.ui.value.shelfBooks.single();assertEquals(a.id,b.id);assertNotEquals(a.sourceId,b.sourceId)
        compose.onNodeWithTag("tab-shelf").performClick()
        menu();compose.onNodeWithTag("download-book-${b.id}").performClick();compose.onNodeWithText("加入下载队列").performClick()
        compose.runOnIdle{assertEquals("CANCELLED",runtime.request(runtime.key(a.sourceId,a.id))!!.state);assertEquals("QUEUED",runtime.request(runtime.key(b.sourceId,b.id))!!.state);assertNull(vm.store.position(b.id))}
        compose.runOnIdle{vm.signOut()};compose.onNodeWithTag("shelf-book-actions").assertDoesNotExist()
    }
    @Test fun findPageStartsEmptyAndKeepsOnlyUnsubmittedInputAcrossTabs() {
        setup();compose.onNodeWithTag("tab-find").performClick()
        compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed()
        compose.runOnIdle{assertTrue(vm.ui.value.books.isEmpty())}
        compose.onNodeWithTag("find-search-input").performTextInput("未提交关键词")
        compose.onNodeWithTag("tab-settings").performClick()
        compose.onNodeWithTag("settings-source").assertIsDisplayed()
        compose.onNodeWithTag("tab-find").performClick()
        compose.onNodeWithTag("find-search-input").assert(hasText("未提交关键词"))
        compose.onNodeWithTag("find-book-public:1").assertDoesNotExist()
        compose.onNodeWithTag("find-search-clear").performClick()
        compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed()
        compose.runOnIdle{assertTrue(vm.ui.value.books.isEmpty());assertTrue(vm.ui.value.query.isBlank())}
    }
}
