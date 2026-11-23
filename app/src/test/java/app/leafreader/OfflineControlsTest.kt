package app.leafreader

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
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
class OfflineControlsTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:ReaderViewModel
    private lateinit var runtime:OfflineDownloads
    private lateinit var vault:MemorySessionVault
    private fun setup() {
        compose.runOnUiThread {
            val app=ApplicationProvider.getApplicationContext<Application>();val w=OfflineFixtures();val c=w.client()
            vault=MemorySessionVault().apply{value=c.snapshot()};runtime=OfflineDownloads(app,w.network,vault,false)
            vm=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{}, {BookHubClient(it,w)}),sessionVault=vault,offlineRuntime=runtime)
            compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
        }
        compose.waitUntil(15000){!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.isNotEmpty()}
    }
    @Test fun smallScreenDefaultsToWifiAndMeteredNeedsExplicitChoiceThenClearPreservesState() {
        setup();val book=vm.ui.value.shelfBooks.single();val session=vault.value
        compose.runOnIdle{vm.store.save(book.id,"preserved-chapter",23)};val position=vm.store.position(book.id)
        compose.onNodeWithTag("download-book-${book.id}").assertDoesNotExist()
        compose.onNodeWithTag("shelf-book-${book.id}").performTouchInput { longClick() }
        compose.waitUntil(10000){compose.onAllNodesWithTag("download-book-${book.id}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("download-book-${book.id}").performScrollTo().performClick()
        compose.onNodeWithTag("download-wifi").assertIsSelected();compose.onNodeWithTag("download-metered").assertIsNotSelected().performClick()
        compose.onNodeWithText("加入下载队列").performClick()
        compose.runOnIdle{assertTrue(runtime.request(runtime.key(book.sourceId,book.id))!!.allowMetered)}
        compose.onNodeWithTag("shelf-book-${book.id}").performTouchInput { longClick() }
        compose.onNodeWithTag("clear-download-${book.id}").performScrollTo().performClick()
        compose.onNodeWithText("清除副本").performClick()
        compose.waitUntil(15000){runtime.request(runtime.key(book.sourceId,book.id))?.state=="CANCELLED"}
        compose.runOnIdle{assertEquals(position,vm.store.position(book.id));assertEquals(session,vault.value);assertEquals(book.key,vm.ui.value.shelfBooks.single().key)}
    }
    @Test fun automaticWifiCanBeDisabledAndSurvivesSettingsReopen() {
        setup();compose.onNodeWithTag("tab-settings").performClick()
        compose.onNode(isToggleable()).assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("tab-settings").performClick()
        compose.onNode(isToggleable()).assertIsOff();compose.runOnIdle{assertFalse(runtime.automaticWifi())}
    }
}
