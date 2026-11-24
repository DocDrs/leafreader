package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Real Compose interactions / native drawing; entirely original metadata, image pixels and test identities. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReleaseHomeUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:ReaderViewModel
    private lateinit var runtime:OfflineDownloads
    private lateinit var fixture:OfflineFixtures
    private lateinit var vault:MemorySessionVault
    private var catalogCalls=0
    private val titles=listOf("远行手记","雨后的书页","山间来信","沿河慢慢走","风吹过城市")
    private fun page(ids:List<Int>,next:String?=null)=JSONObject().put("items",JSONArray().apply{ids.forEach{n->put(BookHubFixtures.book("public:$n",titles[n-1]).put("format","EPUB").put("author","自写测试作者").put("description","沿着日常的路，记录值得留住的片刻。"))}}).put("hasMore",next!=null).put("nextCursor",next?:JSONObject.NULL)
    private fun setup(large:Boolean=false) {
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            val app=ApplicationProvider.getApplicationContext<Application>();fixture=OfflineFixtures()
            val covers=CoverFixtures();val pictures=(1..5).map{n->covers.color=listOf(0xff49665f,0xff7c6556,0xff4b586d,0xff6a6253,0xff546b78)[n-1].toInt();covers.image()}
            val wire=WireTransport{uri,method,headers,body,limit->when{
                uri.path.endsWith("/auth/login")->WireResponse(200,BookHubFixtures.envelope(BookHubFixtures.session(JSONObject(String(body!!)).getString("username"))).toByteArray())
                uri.path.endsWith("/bookshelf")->WireResponse(200,BookHubFixtures.envelope(page(listOf(1,2,3))).toByteArray())
                uri.path.endsWith("/catalog/search")->{catalogCalls++;WireResponse(200,BookHubFixtures.envelope(if(uri.rawQuery.contains("cursor="))page(listOf(5)) else page(listOf(1,4),"second")).toByteArray())}
                uri.path.endsWith("/access")->WireResponse(200,BookHubFixtures.envelope(fixture.manifest().put("bookId",uri.path.substringAfter("/books/").substringBefore('/'))).toByteArray())
                uri.path.endsWith("/cover")->WireResponse(200,pictures[uri.path.substringAfter("public:").substringBefore('/').toInt()-1],"image/png")
                else->fixture.execute(uri,method,headers,body,limit)
            }}
            val c=BookHubClient(ServiceOrigin("https://release.fixture.invalid"),wire).also{it.restore(SavedSession(it.origin.value,"release-account","fixture-device","fixture-access","fixture-refresh"))}
            vault=MemorySessionVault().apply{value=c.snapshot()};runtime=OfflineDownloads(app,fixture.network,vault,false);runtime.setAutomaticWifi(false)
            vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false) as T
            })[ReaderViewModel::class.java]
            compose.activity.setContent {
                val density=LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density,if(large)1.7f else 1f)) {
                    MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}
                }
            }
        }
        await{!vm.ui.value.shelfLoading && vm.ui.value.shelfBooks.size==3}
    }
    private fun await(condition:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();condition()}
    private fun snapshot(name:String)=compose.runOnIdle {
        val v=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/release100-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    @Test fun shelfManagementAndSubmittedSearchUseSameRowsAndProduceSameScalePreviews() {
        setup();await{(1..3).all{n->compose.onAllNodesWithTag("shelf-real-cover-public:$n",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
        snapshot("01-shelf")
        compose.onNodeWithTag("shelf-manage").performClick()
        compose.onNodeWithTag("shelf-book-public:1").performClick().assertIsOn()
        compose.onNodeWithTag("shelf-book-public:2").performClick().assertIsOn();snapshot("02-management")
        compose.runOnIdle{assertNull(vm.ui.value.book);assertNull(vm.store.position("public:1"))}
        compose.activityRule.scenario.recreate();compose.onNodeWithTag("shelf-selected-count").assertTextEquals("已选 2 本")
        compose.onNodeWithTag("shelf-manage-exit").performClick()
        compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed();snapshot("03-find-empty")
        compose.runOnIdle{assertEquals(0,catalogCalls);assertTrue(vm.ui.value.books.isEmpty())}
        compose.onNodeWithTag("find-search-input").performTextInput("行")
        compose.onNodeWithTag("find-book-public:1").assertDoesNotExist();compose.onNodeWithTag("find-search-input").performImeAction()
        await{!vm.ui.value.findLoading && vm.ui.value.books.size==1}
        compose.onNodeWithTag("find-book-public:1").assertDoesNotExist();compose.onNodeWithTag("find-book-public:4").assertIsDisplayed()
        await{compose.onAllNodesWithTag("shelf-real-cover-public:4",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()};snapshot("04-find-results")
        compose.onNodeWithTag("find-directory-public:4").assertIsDisplayed();compose.onNodeWithTag("find-read-public:4").assertIsDisplayed()
        compose.onNodeWithTag("add-shelf-public:4").performClick();compose.onNodeWithTag("find-book-public:4").assertDoesNotExist()
        compose.onNodeWithTag("find-load-more").performClick();await{!vm.ui.value.findLoading && vm.ui.value.books.any{it.id=="public:5"}}
        compose.onNodeWithTag("find-search-clear").performClick();compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed();compose.onNodeWithTag("find-book-public:5").assertDoesNotExist()
        compose.onNodeWithTag("tab-shelf").performClick();compose.onNodeWithTag("shelf-book-public:4").assertIsDisplayed()
    }
    @Test fun multiSelectNeverOpensAndWifiBatchIsDeduplicatedAndPreservesProgress() {
        setup();val book=vm.ui.value.shelfBooks.first();val session=vault.value
        compose.runOnIdle{fixture.network.wifi=false;vm.store.save(book.id,"saved-chapter",71)};val position=vm.store.position(book.id)
        compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-select-all").performClick()
        compose.onNodeWithTag("shelf-selected-count").assertTextEquals("已选 3 本")
        compose.onNodeWithTag("shelf-select-all").performClick();compose.onNodeWithTag("shelf-selected-count").assertTextEquals("已选 0 本")
        compose.onNodeWithTag("shelf-book-public:1").performTouchInput{longClick()}
        compose.onNodeWithTag("shelf-book-actions").assertDoesNotExist();compose.runOnIdle{assertNull(vm.ui.value.book)}
        compose.onNodeWithTag("shelf-select-all").performClick();compose.onNodeWithTag("shelf-batch-download").performClick()
        compose.onNodeWithTag("download-wifi").assertIsSelected();compose.onNodeWithText("加入下载队列").performClick()
        await{vm.ui.value.shelfBooks.all{runtime.request(runtime.key(it.sourceId,it.id))!=null}}
        val requests=vm.ui.value.shelfBooks.map{runtime.request(runtime.key(it.sourceId,it.id))!!}
        assertTrue(requests.all{!it.allowMetered && it.message=="等待 Wi-Fi"});await{vm.ui.value.notice!=null}
        compose.onNodeWithText("知道了").performClick()
        compose.onNodeWithTag("shelf-batch-download").performClick();compose.onNodeWithText("加入下载队列").performClick();await{vm.ui.value.notice!=null}
        compose.runOnIdle{requests.forEach{assertEquals(it.nonce,runtime.request(it.key)!!.nonce)};assertEquals(position,vm.store.position(book.id));assertEquals(session,vault.value);assertNull(vm.ui.value.book)}
        compose.onNodeWithText("知道了").performClick()
        compose.runOnIdle{vm.ui.value.shelfBooks.forEach{runtime.cancel(it.sourceId,it.id)}}
        compose.onNodeWithTag("shelf-batch-download").performClick();compose.onNodeWithTag("download-metered").performClick();compose.onNodeWithText("加入下载队列").performClick()
        await{vm.ui.value.shelfBooks.all{runtime.request(runtime.key(it.sourceId,it.id))?.allowMetered==true}}
    }
    @Test fun accountSwitchDismissesSelectionAndPendingConfirmationAndRejectsOldBatch() {
        setup();val old=vm.ui.value.shelfBooks.map{it.key}.toSet();val revision=vm.ui.value.connectionRevision
        compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-select-all").performClick();compose.onNodeWithTag("shelf-batch-download").performClick()
        compose.runOnIdle{vm.connect("https://release.fixture.invalid","second-account","synthetic-password")}
        await{!vm.ui.value.connecting && vm.ui.value.connectionRevision!=revision}
        compose.onNodeWithTag("batch-download-dialog").assertDoesNotExist();compose.onNodeWithTag("shelf-selected-count").assertDoesNotExist()
        compose.runOnIdle{vm.downloadSelection(old,revision,true);assertTrue(vm.ui.value.shelfBooks.none{runtime.request(runtime.key(it.sourceId,it.id))!=null})}
    }
    @Test fun completedCopyAndForeignOrBareKeysAreSkippedWithoutTouchingShelfOrProgress() {
        setup();val books=vm.ui.value.shelfBooks;val first=books.first();val second=books[1]
        compose.runOnIdle {
            val cache=runtime.cache(first.sourceId);val epoch=cache.generation(first.id);val manifest=fixture.manifest()
            assertTrue(cache.saveManifest(first.id,manifest,epoch));fixture.bytes().forEach{(id,raw)->assertTrue(cache.put(first.id,fixture.version,id,raw,epoch))}
            assertTrue(cache.complete(first.id,manifest,epoch));vm.store.save(first.id,"stable-chapter",93)
            vm.downloadSelection(setOf(first.key,second.key,books[2].id,"foreign-account|${books[2].id}"),vm.ui.value.connectionRevision)
        }
        await{vm.ui.value.notice!=null}
        compose.runOnIdle {
            assertNull(runtime.request(runtime.key(first.sourceId,first.id)));assertTrue(runtime.cache(first.sourceId).status(first.id).complete)
            assertNotNull(runtime.request(runtime.key(second.sourceId,second.id)));assertNull(runtime.request(runtime.key(books[2].sourceId,books[2].id)))
            assertEquals(books,vm.ui.value.shelfBooks);assertEquals(93,vm.store.position(first.id)!!.offset)
        }
    }
    @Test @Config(qualifiers="w320dp-h480dp-hdpi")
    fun largeFontSearchControlsAndManagementRemainReachableWithAccessibleSelectionAndBackExit() {
        setup(true);compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-select-all").performClick()
        compose.onNodeWithTag("shelf-batch-download").performScrollTo().assertIsDisplayed();snapshot("05-small-management")
        compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("shelf-manage").assertIsDisplayed()
        compose.onNodeWithTag("shelf-book-public:1").performSemanticsAction(SemanticsActions.OnLongClick){it()};compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithTag("find-search-input").performTextInput("长关键词")
        val input=compose.onNodeWithTag("find-search-input").fetchSemanticsNode().boundsInRoot
        val clear=compose.onNodeWithTag("find-search-clear").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val submit=compose.onNodeWithTag("find-search-submit").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(clear.left>=input.left && clear.right<=submit.left && submit.right<=input.right)
        snapshot("06-small-search");compose.onNodeWithTag("find-search-clear").performClick();compose.onNodeWithTag("find-unsubmitted").assertIsDisplayed()
    }
}
