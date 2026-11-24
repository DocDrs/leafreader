package app.leafreader

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.*
import app.leafreader.offline.*
import kotlinx.coroutines.CompletableDeferred
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverShelfUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun asynchronousRealPixelsReplacePlaceholdersWithoutReadingOrBlockingShelfAndRemainOffline() {
        val w=CoverFixtures();val release=CompletableDeferred<Unit>();w.afterCover={release.await()}
        val pictures=listOf(0xff366553.toInt(),0xff735645.toInt(),0xff485773.toInt()).map{w.color=it;w.image()}
        val wire=WireTransport { uri,method,headers,body,limit ->
            if(uri.path.endsWith("/bookshelf"))WireResponse(200,BookHubFixtures.envelope(JSONObject().put("items",JSONArray().apply{
                listOf("远行手记","雨后书页","山间来信").forEachIndexed{i,title->put(BookHubFixtures.book("public:${i+1}",title).put("format","EPUB"))}
            }).put("hasMore",false).put("nextCursor",JSONObject.NULL)).toByteArray())
            else if(uri.path.endsWith("/cover")){assertEquals("Bearer fixture-access",headers["Authorization"]);release.await();val n=uri.path.substringAfter("public:").substringBefore('/').toInt();WireResponse(200,pictures[n-1],"image/png")}
            else w.execute(uri,method,headers,body,limit)
        }
        val client=BookHubClient(ServiceOrigin("https://covers.fixture.invalid"),wire).also{it.restore(w.client().snapshot()!!)}
        val vault=MemorySessionVault().apply{value=client.snapshot()};lateinit var vm:ReaderViewModel
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear();val app=ApplicationProvider.getApplicationContext<Application>();val runtime=OfflineDownloads(app,w.base.network,vault,false)
            vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T=ReaderViewModel(app,connectionGate=BookHubConnectionGate(LoginOriginVerifier{},{BookHubClient(it,wire)}),sessionVault=vault,offlineRuntime=runtime,linkBookHubShelf=false) as T
            })[ReaderViewModel::class.java]
            compose.activity.setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
        }
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.shelfLoading}
        compose.onNodeWithTag("shelf-placeholder-public:1",useUnmergedTree=true).assertIsDisplayed()
        compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("tab-shelf").performClick();compose.runOnIdle{release.complete(Unit)}
        compose.waitUntil(15000){compose.waitForIdle();(1..3).all{n->compose.onAllNodesWithTag("shelf-real-cover-public:$n",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
        compose.onNodeWithTag("shelf-real-cover-public:1",useUnmergedTree=true).assertIsDisplayed()
        compose.runOnIdle{assertNull(vm.ui.value.book);assertTrue(vm.ui.value.shelfBooks.all{vm.store.position(it.id)==null && vm.store.lastEnteredAt(it.id)==0L})}
        compose.runOnIdle {
            val view=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(bitmap))
            File("build/covers013-previews").apply{mkdirs()}.resolve("real-cover-shelf.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle();w.base.network.online=false
        }
        compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("tab-shelf").performClick()
        compose.waitUntil(15000){compose.waitForIdle();(1..3).all{n->compose.onAllNodesWithTag("shelf-real-cover-public:$n",useUnmergedTree=true).fetchSemanticsNodes().isNotEmpty()}}
    }
}
