package app.leafreader

import android.os.Build
import android.view.RoundedCorner
import android.view.ViewTreeObserver
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/** Full-window forms own their insets once; only the middle is allowed to scroll. */
@Composable internal fun SourceDialogScaffold(
    title:String, tag:String, dismiss:()->Unit,
    actions:@Composable RowScope.()->Unit,
    content:@Composable ColumnScope.()->Unit
) {
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        val view=LocalView.current
        val dark=glassColors().dark
        DisposableEffect(view,dark) {
            (view.parent as? DialogWindowProvider)?.window?.let{window->
                // Compose UI 1.7 leaves ADJUST_PAN on Android 12+. Explicit ownership avoids
                // the platform moving the whole form while Compose also handles IME insets.
                WindowCompat.setDecorFitsSystemWindows(window,false)
                window.setSoftInputMode(if(Build.VERSION.SDK_INT>=31)WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING else WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
                WindowCompat.getInsetsController(window,view).apply{isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
            }
            onDispose{}
        }
        val rounded=sourceDialogRoundedInsets()
        Surface(Modifier.fillMaxSize(),color=HomeCanvas,contentColor=HomeInk) {
            BoxWithConstraints(Modifier.fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.union(WindowInsets.ime).union(rounded))
                .padding(horizontal=20.dp).testTag(tag).semantics{paneTitle=title}) {
                // With a tall keyboard in landscape, keep both actions reachable rather than
                // measuring a large fixed title before the footer. Back still dismisses the IME first.
                val compact=maxHeight<220.dp
                Column(Modifier.fillMaxSize()) {
                    if(!compact)Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
                        DisplayText(title,fontSize=21.sp,lineHeight=27.sp,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.weight(1f))
                        TextButton(onClick=dismiss,modifier=Modifier.heightIn(min=48.dp).testTag("$tag-close")){DisplayText("关闭")}
                    }
                    content()
                    Row(Modifier.fillMaxWidth().padding(vertical=8.dp).testTag("$tag-actions"),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        if(compact)TextButton(onClick=dismiss,modifier=Modifier.heightIn(min=48.dp).testTag("$tag-close")){DisplayText("关闭")}
                        actions()
                    }
                }
            }
        }
    }
}

internal data class SourceCornerPadding(val left:Int=0,val top:Int=0,val right:Int=0,val bottom:Int=0)
internal fun sourceCornerPadding(topLeft:Int,topRight:Int,bottomLeft:Int,bottomRight:Int)=SourceCornerPadding(
    maxOf(topLeft,bottomLeft,0),maxOf(topLeft,topRight,0),maxOf(topRight,bottomRight,0),maxOf(bottomLeft,bottomRight,0))

@Composable private fun sourceDialogRoundedInsets():WindowInsets {
    val view=LocalView.current
    var corners by remember(view){mutableStateOf(SourceCornerPadding())}
    DisposableEffect(view) {
        fun update(){
            corners=if(Build.VERSION.SDK_INT>=31){
                val insets=view.rootWindowInsets
                sourceCornerPadding(insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?:0,
                    insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius?:0,
                    insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius?:0,
                    insets?.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius?:0)
            }else SourceCornerPadding()
        }
        val observer=view.viewTreeObserver
        val listener=ViewTreeObserver.OnGlobalLayoutListener{update()}
        observer.addOnGlobalLayoutListener(listener);update()
        onDispose{if(observer.isAlive)observer.removeOnGlobalLayoutListener(listener)}
    }
    return WindowInsets(corners.left,corners.top,corners.right,corners.bottom)
}
