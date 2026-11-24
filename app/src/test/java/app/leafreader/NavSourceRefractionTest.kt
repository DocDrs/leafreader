package app.leafreader
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.BitmapShader
import android.graphics.Shader
import org.robolectric.util.ReflectionHelpers
import java.io.File
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h760dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavSourceRefractionTest {
 @get:Rule val compose=createAndroidComposeRule<MainActivity>()
 @Test fun recordedComposeScrollAndNativeLensResamplingStayLive() {
  lateinit var layer:GraphicsLayer;lateinit var scroll:androidx.compose.foundation.ScrollState;var recorded=0
  compose.runOnUiThread {compose.activity.setContent{MaterialTheme {
   layer=rememberGraphicsLayer();scroll=rememberScrollState()
   HomeBackdrop(Modifier.fillMaxSize().captureNavUnderlay(layer,ready={recorded++})) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll)){repeat(40){Text("Self authored row $it",Modifier.fillMaxWidth().height(84.dp).background(if(it%2==0)Color(0xFFD4E4F2)else Color(0xFFE9DBCA)))}}
   }
  }}}
  compose.waitForIdle()
  fun capture():Bitmap {
   lateinit var result:Bitmap
   compose.runOnIdle{val view=compose.activity.window.decorView;result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))}
   return result
  }
  val a=capture();compose.runOnIdle{runBlocking{scroll.scrollTo(144)}};compose.waitForIdle();val b=capture()
  assertEquals(a.width,b.width);assertFalse(a.sameAs(b));assertTrue("The native Compose underlay must actually be recorded",recorded>0)
  val guard=18;val w=a.width-66;val h=102;val y=a.height-180
  val before=Bitmap.createBitmap(a,15,y,w+guard*2,h+guard*2);val after=Bitmap.createBitmap(b,15,y,w+guard*2,h+guard*2)
  val first=NavNativeRaster.render(before,w,h,guard,1.5f,false,6.75f);val second=NavNativeRaster.render(after,w,h,guard,1.5f,false,6.75f)
  assertFalse("The same lens must reflect the actual new scroll content",first.sameAs(second))
  val dir=File("build/nav1100-previews").apply{mkdirs()}
  for((name,bitmap) in listOf("scroll-source-before" to before,"scroll-source-after" to after,"scroll-lens-before" to first,"scroll-lens-after" to second))dir.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
  listOf(a,b,before,after,first,second).forEach{it.recycle()}
 }
}
