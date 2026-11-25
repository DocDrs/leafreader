package app.leafreader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.geometry.Offset

/** Drive the same upward scroll a user makes; performScrollTo alone includes the translucent nav overlay. */
internal fun ComposeContentTestRule.showHomeSetting(tag:String):SemanticsNodeInteraction {
    val node=onNodeWithTag(tag);node.performScrollTo();waitForIdle()
    repeat(3){
        val bar=onNodeWithTag("floating-home-nav").fetchSemanticsNode().boundsInRoot
        if(node.fetchSemanticsNode().boundsInRoot.bottom>bar.top) {
            onNodeWithTag("settings-page").performTouchInput{swipe(Offset(width*.4f,height*.62f),Offset(width*.4f,height*.24f),300)}
            waitForIdle()
        }
    }
    node.assertIsDisplayed();return node
}
