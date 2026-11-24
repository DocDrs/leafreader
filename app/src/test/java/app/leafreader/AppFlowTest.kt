package app.leafreader

import androidx.activity.compose.setContent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.reader.NativeReaderView
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun findReader(view: View): NativeReaderView? {
        if (view is NativeReaderView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findReader(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun reader(): NativeReaderView = findReader(compose.activity.window.decorView)!!
    private fun snapshot(name: String) {
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            val folder = File("build/robolectric-previews").apply { mkdirs() }
            File(folder, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun shelfDirectoryReadingSettingsAndResumeFlow() {
        // Layout fixture is injected only by this test and is never an app source or APK asset.
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            val factory = object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T = ReaderViewModel(
                    compose.activity.application, TestReadingSource(), sessionVault = MemorySessionVault(),linkBookHubShelf=false) as T
            }
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity, factory)[ReaderViewModel::class.java]
            compose.activity.setContent { androidx.compose.material3.MaterialTheme { ReaderApp(vm) } }
        }
        compose.waitUntil(30000) { compose.onAllNodesWithText("排版测试二").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("tab-shelf").assertIsSelected()
        compose.onNodeWithText("来源").assertDoesNotExist()
        compose.onNodeWithText("接着上次的故事").assertDoesNotExist()
        snapshot("01-shelf")
        compose.onNodeWithTag("shelf-book-fixture-layout-2").performScrollTo().performTouchInput { longClick() }
        compose.onNodeWithTag("shelf-menu-directory-fixture-layout-2").assertDoesNotExist()
        compose.onNodeWithTag("remove-book-fixture-layout-2").assertIsDisplayed()
        snapshot("02-book-actions")
        compose.runOnIdle {
            val vm = androidx.lifecycle.ViewModelProvider(compose.activity)[ReaderViewModel::class.java]
            assertNull(vm.store.position("fixture-layout-2"))
            assertEquals(0L, vm.store.lastEnteredAt("fixture-layout-2"))
        }
        compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("shelf-book-fixture-layout-2").performScrollTo().performClick()
        compose.waitUntil(30000) { findReader(compose.activity.window.decorView) != null }
        compose.onNodeWithTag("directory-list").assertDoesNotExist()
        compose.onNodeWithTag("tab-shelf").assertDoesNotExist()
        var saved = -1
        compose.runOnIdle { reader().turn(1); saved = reader().currentOffset; assertTrue(saved > 0) }
        snapshot("03-reading")
        compose.runOnIdle { reader().performClick() }
        compose.onNodeWithText("阅读设置").performClick()
        compose.onNodeWithText("A+").performClick()
        compose.onNodeWithContentDescription("行距").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(1.7f) }
        compose.onNodeWithContentDescription("段落间距").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(24f) }
        compose.onNodeWithContentDescription("左右边距").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(32f) }
        compose.onNodeWithContentDescription("上下边距").performScrollTo().performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(36f) }
        compose.onNodeWithText("仿牛皮纸").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(saved, reader().currentOffset) }
        snapshot("04-reader-after-font-change")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithText("收起").performClick()
        snapshot("05-kraft-reading")
        compose.activityRule.scenario.recreate()
        compose.waitUntil(30000) { findReader(compose.activity.window.decorView) != null }
        compose.runOnIdle { assertEquals(saved, reader().currentOffset) }
        compose.runOnIdle { reader().performClick() }
        compose.onNodeWithText("←  书架").performClick()
        compose.onNodeWithTag("tab-shelf").assertIsSelected()
        compose.onNodeWithTag("shelf-book-fixture-layout-2").performClick()
        compose.waitUntil(30000) { findReader(compose.activity.window.decorView) != null }
        compose.runOnIdle { assertEquals(saved, reader().currentOffset) }
    }
}
