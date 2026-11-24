package app.leafreader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.leafreader.data.*
import app.leafreader.reader.NativeReaderView
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
@Config(sdk=[34],qualifiers="w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EpubFirstEntryUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun reader(v:View=compose.activity.window.decorView):NativeReaderView? {
        if(v is NativeReaderView)return v
        if(v is ViewGroup)for(i in 0 until v.childCount)reader(v.getChildAt(i))?.let{return it}
        return null
    }
    @Test fun directFirstReadShowsSkipNoticeAndManualImageErrorCannotDestroyResume() {
        val manifest=BookHubFixtures.epubManifest().apply {
            put("spine",JSONArray().put("cover").put("a"))
            getJSONArray("resources").put(BookHubFixtures.resource("cover").put("mediaType","application/xhtml+xml"))
            put("toc",JSONArray().put(JSONObject().put("resourceId","a").put("href","/api/v1/books/public%3A1/resources/a#one").put("label","第一章")))
        }
        val client=BookHubClient(ServiceOrigin("https://ui.fixture.invalid"),WireTransport{uri,_,_,_,_->
            val text=when {
                uri.path.endsWith("/bookshelf")->BookHubFixtures.envelope(BookHubFixtures.page())
                uri.path.endsWith("/access")->BookHubFixtures.envelope(manifest)
                uri.path.endsWith("/cover")->"<html><body><img src='/never'/></body></html>"
                else->"<html><body><h1 id='one'>第一章</h1><p>"+"仅用于验证原生分页和字符位置的测试文字。".repeat(120)+"</p></body></html>"
            };WireResponse(200,text.toByteArray())
        });client.restore(SavedSession(client.origin.value,"fixture-account","fixture-device","fixture-access","fixture-refresh"))
        lateinit var vm:ReaderViewModel
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            val factory=object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T:ViewModel> create(modelClass:Class<T>):T = ReaderViewModel(compose.activity.application,BookHubSource(client),sessionVault=MemorySessionVault(),linkBookHubShelf=false).also{vm=it} as T
            }
            vm=ViewModelProvider(compose.activity,factory)[ReaderViewModel::class.java]
            compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
        }
        compose.waitUntil(30000){compose.onAllNodesWithTag("shelf-book-public:1").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("shelf-book-public:1").performClick()
        compose.waitUntil(30000){vm.ui.value.notice!=null}
        compose.onNode(hasText("已略过 1",substring=true)).assertIsDisplayed()
        compose.onNodeWithTag("directory-list").assertDoesNotExist()
        compose.onNodeWithText("知道了").performClick()
        var anchor=0
        compose.runOnIdle{assertNotNull(reader());reader()!!.turn(1);anchor=reader()!!.currentOffset;assertTrue(anchor>0);reader()!!.performClick()}
        compose.onNodeWithText("目录").performClick();compose.onNodeWithText("书前内容").performScrollTo().performClick()
        compose.waitUntil(30000){vm.ui.value.error!=null}
        compose.onNode(hasText("BH_EPUB_IMAGE_ONLY",substring=true)).assertIsDisplayed()
        compose.runOnIdle{assertEquals(anchor,vm.store.position("public:1")!!.offset)}
        compose.onNodeWithText("返回书架").performClick();compose.onNodeWithTag("shelf-book-public:1").performClick()
        compose.waitUntil(30000){reader()!=null&&vm.ui.value.error==null}
        compose.runOnIdle{assertEquals(anchor,reader()!!.currentOffset);assertNull(vm.ui.value.notice)}
        compose.activityRule.scenario.recreate();compose.runOnIdle{assertEquals(anchor,reader()!!.currentOffset)}
    }
}
