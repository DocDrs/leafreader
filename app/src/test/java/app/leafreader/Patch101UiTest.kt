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
class Patch101UiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:ReaderViewModel
    private lateinit var runtime:OfflineDownloads
    private lateinit var fixture:OfflineFixtures
    private lateinit var vault:MemorySessionVault
    private var catalogCalls=0
    private val requests=java.util.Collections.synchronizedList(mutableListOf<String>())
    private val titles=listOf("远行手记","雨后的书页","山间来信","沿河慢慢走","风吹过城市")
    private fun page(ids:List<Int>,next:String?=null)=JSONObject().put("items",JSONArray().apply{ids.forEach{n->put(BookHubFixtures.book("public:$n",titles[n-1]).put("format","EPUB").put("author","自写测试作者").put("description","沿着日常的路，记录值得留住的片刻。"))}}).put("hasMore",next!=null).put("nextCursor",next?:JSONObject.NULL)
    private fun setup(large:Boolean=false) {
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            val app=ApplicationProvider.getApplicationContext<Application>();fixture=OfflineFixtures()
            val covers=CoverFixtures();val pictures=(1..5).map{n->covers.color=listOf(0xff49665f,0xff7c6556,0xff4b586d,0xff6a6253,0xff546b78)[n-1].toInt();covers.image()}
            val wire=WireTransport{uri,method,headers,body,limit->requests+="$method ${uri.path}";when{
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
                @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=runtime) as T
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
    private fun await(condition:()->Boolean) {try{compose.waitUntil(15000){compose.waitForIdle();condition()}}catch(e:Throwable){
        println("Fixture state: revision=${vm.ui.value.downloadRevision}, labels=${vm.ui.value.downloadLabels}, task=${vm.downloadTaskLabel(vm.ui.value.shelfBooks.first())}")
        compose.onAllNodesWithTag("download-status-public:1").fetchSemanticsNodes().forEach{println(it.config)}
        compose.onAllNodesWithTag("download-copy-public:1").fetchSemanticsNodes().forEach{println(it.config)}
        throw e
    }}
    private fun snapshot(name:String)=compose.runOnIdle {
        val v=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/patch101-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }
    private fun seedComplete():Book {
        val book=vm.ui.value.shelfBooks.first{it.id=="public:1"}
        compose.runOnIdle {
            val cache=runtime.cache(book.sourceId);val epoch=cache.generation(book.id);val manifest=fixture.manifest()
            cache.saveManifest(book.id,manifest,epoch);fixture.bytes().forEach{(id,raw)->cache.put(book.id,fixture.version,id,raw,epoch)};assertTrue(cache.complete(book.id,manifest,epoch));OfflineEvents.changed()
        }
        return book
    }
    private fun menu(book:Book)=compose.onNode(SemanticsMatcher.expectValue(SavedBookIdentity,book.key)).performSemanticsAction(SemanticsActions.OnLongClick){it()}
    @Test fun countAndManageShareARowAndBatchLocalRemovalSurvivesRefreshAndReaddResumesOffline() {
        setup();val book=seedComplete();val books=vm.ui.value.shelfBooks
        await{(1..3).all{n->compose.onAllNodesWithTag("shelf-real-cover-public:$n",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
        val count=compose.onNodeWithTag("shelf-book-count").fetchSemanticsNode().boundsInRoot
        val manage=compose.onNodeWithTag("shelf-manage").fetchSemanticsNode().boundsInRoot
        assertEquals(count.center.y,manage.center.y,1f);snapshot("01-shelf")
        compose.runOnIdle{fixture.network.online=false;vm.open(book)};await{!vm.ui.value.loading && vm.ui.value.chapter!=null}
        compose.runOnIdle{vm.open(book,vm.ui.value.chapters[1].id)};await{!vm.ui.value.loading && vm.ui.value.graphicId==null}
        compose.runOnIdle{vm.position(37);vm.backToShelf()};val saved=vm.store.position(book.id);val session=vault.value
        compose.onNodeWithTag("shelf-manage").performClick();compose.onNodeWithTag("shelf-book-public:1").performClick();compose.onNodeWithTag("shelf-book-public:2").performClick();await{(1..3).all{n->compose.onAllNodesWithTag("shelf-real-cover-public:$n",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}};snapshot("02-management")
        compose.onNodeWithTag("shelf-batch-remove").performClick();compose.onNodeWithText("取消").performClick();compose.runOnIdle{assertEquals(3,vm.ui.value.shelfBooks.size)}
        compose.onNodeWithTag("shelf-batch-remove").performClick();compose.onNodeWithTag("confirm-remove-shelf").performClick()
        await{vm.ui.value.shelfBooks.size==1};compose.onNodeWithTag("shelf-selected-count").assertTextEquals("已选 0 本")
        compose.runOnIdle{vm.refreshShelf()};await{!vm.ui.value.shelfLoading};compose.runOnIdle{
            assertEquals(saved,vm.store.position(book.id));assertEquals(session,vault.value);assertTrue(runtime.cache(book.sourceId).status(book.id,true).complete)
            assertEquals(listOf("public:3"),ReadingStore(compose.activity,book.sourceId).orderedShelf().map{it.id})
        }
        compose.onNodeWithTag("shelf-manage-exit").performClick();compose.runOnIdle{fixture.network.online=true;vm.selectTab("find");vm.search("行")}
        await{!vm.ui.value.findLoading && vm.ui.value.books.any{it.id==book.id}}
        compose.onNodeWithTag("add-shelf-${book.id}").performClick();compose.onNodeWithTag("find-book-${book.id}").assertDoesNotExist();compose.onNodeWithTag("tab-shelf").performClick()
        compose.runOnIdle{fixture.network.online=false};compose.onNodeWithTag("shelf-book-${book.id}").performClick();await{!vm.ui.value.loading && vm.ui.value.chapter!=null}
        compose.runOnIdle{assertEquals(37,vm.ui.value.offset);assertNull(vm.ui.value.error)}
        // A cold ViewModel with the same persisted account sees explicit re-add and keeps the other hidden ID.
        compose.runOnIdle{
            val cold=ReaderViewModel(compose.activity.application,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,WireTransport{_,_,_,_,_->error("unexpected offline HTTP")})}),sessionVault=vault,offlineRuntime=runtime)
            assertEquals(setOf("public:2"),cold.store.hiddenShelfIds())
        }
        assertFalse(requests.any{it.startsWith("DELETE") || it.contains("/reading-package")})
    }
    @Test fun verifiedMenuDetectsCorruptionAndClearUpdatesItWithoutLosingProgressOrSendingRequests() {
        setup();val book=seedComplete();compose.runOnIdle{fixture.network.online=false;vm.store.save(book.id,"saved",51)}
        val saved=vm.store.position(book.id);menu(book)
        await{compose.onAllNodesWithText("已下载").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("shelf-menu-directory-${book.id}").assertDoesNotExist();compose.onNodeWithText("目录").assertDoesNotExist()
        compose.onNodeWithTag("download-copy-${book.id}").assertTextContains("5/5",substring=true)
        val before=requests.size
        compose.runOnIdle {
            val root=File(compose.activity.noBackupFilesDir,"offline-v1/${sha(book.sourceId.toByteArray())}/${sha(book.id.toByteArray())}/${sha(fixture.version.toByteArray())}")
            val file=File(root,sha("a".toByteArray())+".bin");val raw=file.readBytes();file.writeBytes(raw.apply{this[0]=(this[0].toInt() xor 1).toByte()})
        }
        compose.onNodeWithTag("verify-download-${book.id}").performClick()
        await{compose.onAllNodesWithText("部分离线").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("download-book-${book.id}").assertIsDisplayed();assertEquals(before,requests.size)
        compose.onNodeWithTag("clear-download-${book.id}").performClick();compose.onNodeWithText("清除副本").performClick()
        await{compose.onAllNodesWithText("已取消").fetchSemanticsNodes().isNotEmpty() || compose.onAllNodesWithText("未下载").fetchSemanticsNodes().isNotEmpty()}
        await{compose.onAllNodesWithTag("download-copy-${book.id}").fetchSemanticsNodes().any{it.config.toString().contains("本机暂无完整副本")}}
        compose.runOnIdle{assertEquals(saved,vm.store.position(book.id));assertEquals(3,vm.ui.value.shelfBooks.size);assertFalse(runtime.cache(book.sourceId).status(book.id,true).complete)}
    }
    @Test fun queueRunningFailureCancelAndCompletionAreLiveAndCompletionNeedsActualResources() {
        setup();val book=vm.ui.value.shelfBooks.first();menu(book)
        compose.runOnIdle{vm.download(book)};await{vm.downloadTaskLabel(book)?.startsWith("已排队")==true};compose.onNodeWithTag("download-status-${book.id}").assertTextContains("已排队",substring=true)
        compose.onNodeWithTag("cancel-download-${book.id}").performClick();await{vm.downloadTaskLabel(book)=="已取消"}
        compose.runOnIdle{vm.download(book)};await{runtime.busy(book.sourceId,book.id)}
        val r=runtime.request(runtime.key(book.sourceId,book.id))!!
        compose.runOnIdle{runtime.update(r,"RUNNING","测试处理中")};await{compose.onAllNodesWithText("下载中").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{runtime.update(r,"FAILED","测试网络中断")};await{compose.onAllNodesWithText("下载失败").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{runtime.update(r,"COMPLETE","整本已下载")};await{compose.onAllNodesWithText("未下载").fetchSemanticsNodes().isNotEmpty()}
        seedComplete();await{compose.onAllNodesWithText("已下载").fetchSemanticsNodes().isNotEmpty()}
        compose.runOnIdle{
            val dialog=org.robolectric.shadows.ShadowDialog.getLatestDialog();val view=dialog.window!!.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap));File("build/patch101-previews/03-download-menu.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }
    }
    @Test fun singleRemoveRequiresConfirmationAndAccountSwitchInvalidatesOldConfirmation() {
        setup();val book=vm.ui.value.shelfBooks.first();val rev=vm.ui.value.connectionRevision
        menu(book);compose.onNodeWithTag("remove-book-${book.id}").performClick();compose.onNodeWithText("取消").performClick();assertEquals(3,vm.ui.value.shelfBooks.size)
        menu(book);compose.onNodeWithTag("remove-book-${book.id}").performClick()
        compose.runOnIdle{vm.connect("https://release.fixture.invalid","second-account","synthetic-password")}
        await{!vm.ui.value.connecting && vm.ui.value.connectionRevision!=rev};compose.onNodeWithTag("remove-shelf-confirm").assertDoesNotExist()
        compose.runOnIdle{vm.removeFromShelf(setOf(book.key),rev);assertTrue(ReadingStore(compose.activity,book.sourceId).hiddenShelfIds().isEmpty());assertEquals(6,vm.ui.value.shelfBooks.size)}
        val current=vm.ui.value.shelfBooks.first{it.sourceId==vm.store.sourceId};compose.onNodeWithTag("tab-shelf").performClick();menu(current);compose.onNodeWithTag("remove-book-${current.id}").performClick();compose.onNodeWithTag("confirm-remove-shelf").performClick()
        await{vm.ui.value.shelfBooks.size==5};assertFalse(vm.ui.value.shelfBooks.any{it.key==current.key});assertTrue(ReadingStore(compose.activity,current.sourceId).hiddenShelfIds().contains(current.id))
    }
}
