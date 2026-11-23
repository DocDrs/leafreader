package app.leafreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BookHubLoginUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val origin = "https://bookhub.fixture.invalid"
    private fun content(body:@Composable ()->Unit) { compose.runOnUiThread { compose.activity.setContent(content=body) } }
    private fun credentials() {
        compose.onNodeWithText("服务账号").performScrollTo().performTextInput("fixture-user")
        compose.onNodeWithText("服务密码").performScrollTo().performTextInput("fixture-password")
    }
    @Test fun firstUseHasNoDefaultAndRequiresHttpsReviewAndConsent() {
        var submitted:Triple<String,String,String>? = null
        content { MaterialTheme { SourceConnectionDialog(ReaderState(), "", {}, {a,b,c->submitted=Triple(a,b,c)}, {}) } }
        compose.onNodeWithTag("source-address-editor").assertTextEquals("我的服务地址（HTTPS）", "")
        compose.onNodeWithTag("confirm-source-target").assertIsNotEnabled()
        compose.onNodeWithTag("source-address-editor").performTextInput("http://bookhub.fixture.invalid")
        compose.onNodeWithTag("confirm-source-target").assertIsNotEnabled()
        compose.onNodeWithTag("source-address-editor").performTextReplacement(origin)
        compose.onNodeWithTag("confirm-source-target").performClick()
        compose.onNodeWithText(origin, substring=true).assertDoesNotExist()
        credentials()
        compose.onNodeWithTag("bookhub-login-submit").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("bookhub-login-submit").performClick()
        assertEquals(Triple(origin,"fixture-user","fixture-password"),submitted)
    }
    @Test fun savedTargetIsHiddenUntilExplicitEditorAndEditingNeverConnects() {
        var submissions=0
        content { MaterialTheme { SourceConnectionDialog(ReaderState(remote=true,sourceLabel="BookHub · 已连接"),origin,{}, {_,_,_->submissions++},{}) } }
        compose.onNodeWithText(origin,substring=true).assertDoesNotExist()
        compose.onNodeWithTag("source-address-editor").assertDoesNotExist()
        compose.onNodeWithTag("edit-source-address").performClick()
        compose.onNodeWithTag("source-address-editor").assertTextContains(origin)
        compose.onNodeWithTag("source-address-editor").performTextReplacement("https://other.fixture.invalid")
        compose.onNodeWithText("取消编辑，返回登录").performScrollTo().performClick()
        compose.onNodeWithText(origin,substring=true).assertDoesNotExist()
        compose.onNodeWithTag("edit-source-address").performClick()
        compose.onNodeWithTag("source-address-editor").assertTextContains(origin)
        assertEquals(0,submissions)
    }
    @Test fun changedTargetRequiresNewPasswordAndConsent() {
        var submitted:String?=null
        content { MaterialTheme { SourceConnectionDialog(ReaderState(),origin,{}, {a,_,_->submitted=a},{}) } }
        compose.onNodeWithTag("edit-source-address").performClick()
        compose.onNodeWithTag("confirm-source-target").performClick()
        credentials()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("bookhub-login-submit").assertIsEnabled()
        compose.onNodeWithTag("edit-source-address").performScrollTo().performClick()
        compose.onNodeWithTag("source-address-editor").performTextReplacement("https://other.fixture.invalid")
        compose.onNodeWithTag("confirm-source-target").performClick()
        compose.onNodeWithTag("bookhub-login-submit").assertIsNotEnabled()
        compose.onNodeWithText("服务密码").performScrollTo().performTextInput("fresh")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithTag("bookhub-login-submit").performClick()
        assertEquals("https://other.fixture.invalid",submitted)
    }
}
