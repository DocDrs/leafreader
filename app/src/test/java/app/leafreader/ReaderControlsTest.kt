package app.leafreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import app.leafreader.data.ReaderSettings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h480dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(body:@Composable ()->Unit) {compose.runOnUiThread{compose.activity.setContent(content=body)}}

    @Test fun dragPreviewsWithoutCommitUntilReleaseAndAccessibleProgressCommits() {
        var commits = 0; var value = 1.55f
        content {
            var state by remember { mutableFloatStateOf(value) }
            MaterialTheme { PrecisionSlider("行距", state, 1.2f..2f, .05f, { "%.2f 倍".format(java.util.Locale.ROOT, it) }) {
                value = it; state = it; commits++
            } }
        }
        val slider = compose.onNodeWithContentDescription("行距")
        slider.performTouchInput { down(center); moveTo(centerRight) }
        compose.runOnIdle { assertEquals(0, commits); assertEquals(1.55f, value) }
        slider.performTouchInput { up() }
        compose.runOnIdle { assertEquals(1, commits); assertEquals(2f, value) }
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(1.35f) }
        compose.runOnIdle { assertEquals(2, commits); assertEquals(1.35f, value, .001f) }
    }
    @Test fun smallScreenScrollsToKraftAndResetKeepsPaperAndRestoresTypography() {
        var result = ReaderSettings(fontSp = 30f, lineSpacing = 1.9f, palette = 3, paragraphSpacingDp = 45f, horizontalMarginDp = 46f, verticalMarginDp = 60f)
        content {
            var settings by remember { mutableStateOf(result) }
            MaterialTheme { ReaderControls(settings) { result = it; settings = it } }
        }
        compose.onNodeWithText("仿牛皮纸").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(4, result.palette) }
        compose.onNodeWithText("恢复版式默认").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(ReaderSettings(palette = 4), result) }
        compose.onNodeWithContentDescription("上下边距").performScrollTo().assertIsDisplayed()
    }
}
