package app.leafreader

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.BookHubBuildDefaults
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Patch108UiTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 private val compiled="https://compiled.fixture.invalid"
 private fun show(saved:String="",default:String=compiled,connect:(String,String,String)->Unit={_,_,_->}) {
  compose.runOnUiThread { compose.activity.setContent { MaterialTheme { SourceConnectionDialog(ReaderState(),saved,{},connect,{},buildOrigin=default) } } }
 }
 @Test fun optionalCompiledTargetIsVisibleOnFirstUseAndNeverLogsInAutomatically() {
  var submitted:Triple<String,String,String>?=null
  show(connect={a,b,c->submitted=Triple(a,b,c)})
  compose.onNodeWithTag("build-default-destination").assertTextEquals("compiled.fixture.invalid")
  compose.onNodeWithTag("source-address-editor").assertDoesNotExist();assertNull(submitted)
  compose.onNodeWithText("服务账号").performTextInput("synthetic-user")
  compose.onNodeWithText("服务密码").performTextInput("synthetic-password")
  compose.onNodeWithTag("bookhub-login-submit").assertIsNotEnabled()
  compose.onNode(isToggleable()).performScrollTo().performClick()
  compose.onNodeWithTag("bookhub-login-submit").performClick()
  assertEquals(Triple(compiled,"synthetic-user","synthetic-password"),submitted)
 }
 @Test fun persistedServiceAlwaysWinsAndDefaultCannotSilentlyRetargetCredentials() {
  val saved="https://saved.fixture.invalid";var target:String?=null
  show(saved,connect={a,_,_->target=a})
  compose.onNodeWithTag("build-default-destination").assertDoesNotExist()
  compose.onNodeWithText("compiled.fixture.invalid",substring=true).assertDoesNotExist()
  compose.onNodeWithTag("edit-source-address").performClick()
  compose.onNodeWithTag("source-address-editor").assertTextContains(saved)
  compose.onNodeWithTag("confirm-source-target").performClick()
  compose.onNodeWithText("服务账号").performTextInput("synthetic-user")
  compose.onNodeWithText("服务密码").performTextInput("synthetic-password")
  compose.onNode(isToggleable()).performScrollTo().performClick();compose.onNodeWithTag("bookhub-login-submit").performClick()
  assertEquals(saved,target)
 }
 @Test fun emptyOrInvalidCompiledValueLeavesManualHttpsEntry() {
  show(default="http://untrusted.fixture.invalid")
  compose.onNodeWithTag("source-address-editor").assertTextEquals("我的服务地址（HTTPS）", "")
  compose.onNodeWithTag("confirm-source-target").assertIsNotEnabled()
 }
 @Test fun compiledGeneratorMatchesOnlyExplicitOptionalInputAndNeverSubstitutesSavedOrigin() {
  val expected=System.getenv("LEAFREADER_DEFAULT_BOOKHUB_ORIGIN").orEmpty()
  assertEquals(BookHubBuildDefaults.validated(expected),BookHubBuildDefaults.origin())
  for(value in listOf("","http://insecure.fixture.invalid","https://user:pass@fixture.invalid","https://fixture.invalid/path","https://fixture.invalid?key=fake"))assertEquals("",BookHubBuildDefaults.validated(value))
  assertEquals("https://compiled.fixture.invalid",BookHubBuildDefaults.initial("",compiled))
  val legacy="HTTPS://SAVED.fixture.invalid:443/"
  assertEquals(legacy,BookHubBuildDefaults.initial(legacy,compiled))
  assertEquals("broken saved value",BookHubBuildDefaults.initial("broken saved value",compiled))
 }
}
