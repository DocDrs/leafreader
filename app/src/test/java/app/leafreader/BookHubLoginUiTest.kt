package app.leafreader

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import app.leafreader.data.*
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
    private val fixture = ConfirmedBookHubEntry(ServiceOrigin("https://bookhub.fixture.invalid"), "测试服务方", "fixture only")

    private fun content(body:@Composable ()->Unit) {compose.runOnUiThread{compose.activity.setContent(content=body)}}

    @Test fun publicDistributionRequiresExplicitServiceAddressBeforeLogin() {
        var submitted=false;var target:String?=null
        content { MaterialTheme { SourceConnectionDialog(ReaderState(),"",{},{origin,_,_->submitted=true;target=origin},{}) } }
        assertNull(BuiltInBookHub.confirmedEntry)
        compose.onNodeWithText("我的服务地址（HTTPS）").assertIsDisplayed()
        compose.onNodeWithText("服务账号").performTextInput("fixture-user")
        compose.onNodeWithText("服务密码").performTextInput("fixture-password")
        compose.onNodeWithText("登录 BookHub").assertIsNotEnabled();assertFalse(submitted)
        compose.onNodeWithText("我的服务地址（HTTPS）").performScrollTo().performTextInput("https://public-login.fixture.invalid")
        compose.onNodeWithText("服务密码").performScrollTo().performTextInput("fixture-password")
        compose.onNodeWithText("登录 BookHub").assertIsNotEnabled()
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("登录 BookHub").assertIsEnabled().performClick();assertTrue(submitted);assertEquals("https://public-login.fixture.invalid",target)
    }

    @Test fun confirmedFixtureShowsDomainAndOnlyUsernamePasswordByDefault() {
        var submitted: Triple<String, String, String>? = null
        content {
            MaterialTheme { SourceConnectionDialog(ReaderState(), "", {}, { a, b, c -> submitted = Triple(a, b, c) }, {}, builtIn = fixture) }
        }
        compose.onNodeWithText("bookhub.fixture.invalid").assertIsDisplayed()
        compose.onNodeWithText("我的服务地址（HTTPS）").assertDoesNotExist()
        compose.onNodeWithText("服务账号").performTextInput("fixture-user")
        compose.onNodeWithText("服务密码").performTextInput("fixture-password")
        compose.onNodeWithText("登录 BookHub").assertIsEnabled().performClick()
        assertEquals(Triple("https://bookhub.fixture.invalid", "fixture-user", "fixture-password"), submitted)
    }

    @Test fun absentIdentityNeverEnablesAccountOnlyLoginOrInstallsAnUnknownDefault() {
        var submitted = false
        content {
            MaterialTheme { SourceConnectionDialog(ReaderState(), "", {}, { _, _, _ -> submitted = true }, {}, builtIn = null) }
        }
        compose.onNodeWithText("配置 BookHub 服务").assertIsDisplayed()
        compose.onNodeWithText("服务账号").performTextInput("fixture-user")
        compose.onNodeWithText("服务密码").performTextInput("fixture-password")
        compose.onNodeWithText("登录 BookHub").assertIsNotEnabled()
        assertFalse(submitted)
        assertNull(BuiltInBookHub.confirmedEntry)
    }

    @Test fun legacyOriginIsPreservedAndChangingTargetsClearsThePassword() {
        var changes = 0
        content {
            MaterialTheme { SourceConnectionDialog(ReaderState(), "https://legacy.fixture.invalid", {}, { _, _, _ -> }, {},
                targetChanged = { changes++ }, builtIn = fixture) }
        }
        compose.onNodeWithText("legacy.fixture.invalid").assertIsDisplayed()
        compose.onNodeWithText("我的服务地址（HTTPS）").assertIsDisplayed()
        assertEquals(0, changes)
        compose.onNodeWithText("服务账号").performTextInput("fixture-user")
        compose.onNodeWithText("服务密码").performTextInput("fixture-password")
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.onNodeWithText("登录 BookHub").assertIsEnabled()
        compose.onNodeWithText("使用内置 BookHub").performScrollTo().performClick()
        compose.onNodeWithText("bookhub.fixture.invalid").assertIsDisplayed()
        compose.onNodeWithText("登录 BookHub").assertIsNotEnabled()
        assertEquals(0, changes)
    }
}
