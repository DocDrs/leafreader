package app.leafreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import android.graphics.Bitmap
import android.graphics.Canvas
import app.leafreader.data.*
import kotlinx.coroutines.runBlocking
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
class SignedOutStartupTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private fun snapshot(name:String){compose.runOnIdle{
        val v=compose.activity.window.decorView;val b=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(b))
        File("build/startup-v07").apply{mkdirs()}.resolve(name+".png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()
    }}
    @Test fun freshInstallHasNoBundledBooksAndOffersSourcesWithoutForcingLogin()=runBlocking {
        assertTrue(SignedOutBookSource().books().isEmpty())
        compose.waitUntil(30000){compose.onAllNodesWithText("书架还是空的，到找书页选择一本吧。").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("tab-shelf").assertIsSelected();compose.onAllNodes(hasTestTag("shelf-book-mountain-letter-v1")).assertCountEquals(0)
        snapshot("empty-shelf")
        compose.onNodeWithTag("empty-shelf-find").performClick();compose.onNodeWithTag("tab-find").assertIsSelected()
        compose.onNodeWithText("搜索离线样书").assertDoesNotExist();compose.onNodeWithText("使用原创离线样书").assertDoesNotExist()
        compose.onNodeWithTag("find-login").assertIsDisplayed();compose.onNodeWithTag("source-gutenberg").assertIsDisplayed();snapshot("find-login")
        compose.onNodeWithTag("find-login").performClick();compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("settings-source").performClick();compose.onNodeWithTag("source-address-editor").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("使用原创离线样书").assertDoesNotExist();compose.onNodeWithText("退出 BookHub，保留本机记录").assertDoesNotExist()
        compose.onNodeWithText("关闭").performClick();compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("tab-settings").assertIsSelected();compose.onNodeWithTag("settings-source").assertIsDisplayed()
        Unit
    }
}
