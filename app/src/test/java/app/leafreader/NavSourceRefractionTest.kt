package app.leafreader
import androidx.compose.runtime.*
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
  lateinit var layer:GraphicsLayer;lateinit var scroll:androidx.compose.foundation.ScrollState;lateinit var scene:NavBackdrop;var showSource by mutableStateOf(false);var sourceFailures=0;var fixtureDark by mutableStateOf(false)
  compose.runOnUiThread {compose.activity.setContent{MaterialTheme(colorScheme=if(fixtureDark)darkColorScheme(surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB))else lightColorScheme(surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A))) {
   layer=rememberGraphicsLayer();scroll=rememberScrollState();scene=remember(layer){NavBackdrop(layer,{sourceFailures++},{scroll.value.toLong()})}
   CompositionLocalProvider(LocalGlassBackdrop provides scene) {
   Box(Modifier.fillMaxSize()) {
   HomeBackdrop(Modifier.fillMaxSize(),scene) {
    Column(Modifier.fillMaxSize().verticalScroll(scroll)){repeat(40){i->
     Row(Modifier.fillMaxWidth().height(84.dp).padding(horizontal=20.dp),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
      androidx.compose.foundation.Canvas(Modifier.size(48.dp,68.dp).glassPart()){drawRect(if(i%2==0)Color(0xFF487569)else Color(0xFFA0644B));repeat(5){k->drawLine(Color(0xFFE1DAB0),androidx.compose.ui.geometry.Offset(5f,k*18f+12),androidx.compose.ui.geometry.Offset(size.width-4,k*18f+23),4f)}}
      if(!showSource)Column(Modifier.padding(start=12.dp)){Text("自写书目 $i",color=HomeInk);Text("自写作者",color=HomeMuted)}
     };HorizontalDivider(color=HomeLine,modifier=Modifier.glassPart())
    }}
   }
   // The host source-only fixture renders the same actual registered cover/line components.
   // It does NOT read back or claim to render the device GPU RenderNode graph.
   }
   }
  }}}
  compose.waitForIdle()
  fun capture():Bitmap {
   lateinit var result:Bitmap
   compose.runOnIdle{val view=compose.activity.window.decorView;result=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888);view.draw(Canvas(result))}
   return result
  }
  val normal=capture();compose.runOnIdle{showSource=true};compose.waitForIdle();val a=capture()
  assertFalse("The source-only Compose fixture must omit visible title/author text",normal.sameAs(a));normal.recycle()
  compose.runOnIdle{runBlocking{scroll.scrollTo(144)}};compose.waitForIdle();val b=capture()
  assertEquals(0,sourceFailures);assertEquals(a.width,b.width);assertFalse(a.sameAs(b));assertTrue("The production background is recorded",scene.ready);assertEquals(80,scene.parts.size);assertTrue(scene.parts.values.filter{!it.visible.isEmpty}.all{it.ready})
  val guard=18;val w=a.width-66;val h=102;val y=a.height-180
  val before=Bitmap.createBitmap(a,15,y,w+guard*2,h+guard*2);val after=Bitmap.createBitmap(b,15,y,w+guard*2,h+guard*2)
  val first=NavNativeRaster.render(before,w,h,guard,1.5f,false,6.75f);val second=NavNativeRaster.render(after,w,h,guard,1.5f,false,6.75f)
  assertFalse("The same lens must reflect the actual new scroll content",first.sameAs(second))
  val dir=File("build/nav1100-previews").apply{mkdirs()}
  for((name,bitmap) in listOf("scroll-source-before" to before,"scroll-source-after" to after,"scroll-lens-before" to first,"scroll-lens-after" to second))dir.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
  // Same actual Compose scene, full-width bottom bar and rounded foreground surfaces.
  val original=javaClass.getResourceAsStream("/glass-v110-reference.agsl")!!.bufferedReader().readText()
  val fullDir=File("build/glass1101-previews").apply{mkdirs()}
  for((name,frame)in listOf("backplate-before" to a,"backplate-after" to b))fullDir.resolve("$name.png").outputStream().use{frame.compress(Bitmap.CompressFormat.PNG,100,it)}
  data class Pane(val name:String,val x:Int,val y:Int,val width:Int,val height:Int,val corner:Int)
  for((name,x,sy,sw,sh,corner)in listOf(
   Pane("nav",0,a.height-150,a.width,150,0),
   Pane("menu",18,a.height-440,a.width-36,390,28),
   Pane("confirm",50,a.height/2-100,a.width-100,200,28))) {
   val sx=x;val yy=sy;val ww=sw;val hh=sh;val r=corner.toFloat();val g=36
   val sample=glassSampleWindow(androidx.compose.ui.geometry.Offset(sx.toFloat(),yy.toFloat()),androidx.compose.ui.geometry.Offset.Zero,androidx.compose.ui.unit.IntSize(a.width,a.height),ww,hh,g)!!
   val outputs=mutableListOf<Bitmap>()
   for((phase,frame)in listOf("before" to a,"after" to b)) {
    val raw=Bitmap.createBitmap(ww+g*2,hh+g*2,Bitmap.Config.ARGB_8888);Canvas(raw).drawBitmap(frame,(g-sx).toFloat(),(g-yy).toFloat(),null)
    for(dark in listOf(false,true)) {
     val old=NavNativeRaster.render(raw,ww,hh,g,1.5f,dark,6.75f,original,if(r==0f)30f else r)
     val fresh=NavNativeRaster.render(raw,ww,hh,g,1.5f,dark,18f,cornerDp=r,sampleMin=sample.min,sampleMax=sample.max)
     outputs+=fresh
     for((version,img)in listOf("v110" to old,"v111" to fresh))fullDir.resolve("$name-$phase-${if(dark) "dark" else "light"}-$version.png").outputStream().use{img.compress(Bitmap.CompressFormat.PNG,100,it)}
     if(name=="menu") {
      val old111Material=javaClass.getResourceAsStream("/glass-v111-reference.agsl")!!.bufferedReader().readText()
      val previous=NavNativeRaster.render(raw,ww,hh,g,1.5f,dark,18f,kernel=old111Material,cornerDp=r,sampleMin=sample.min,sampleMax=sample.max)
      val clearMaterial=NavNativeRaster.render(raw,ww,hh,g,1.5f,dark,18f,cornerDp=r,sampleMin=sample.min,sampleMax=sample.max,clear=true)
      val target=File("build/clear112-previews").apply{mkdirs()}
      for((version,img)in listOf("v111" to previous,"v112" to clearMaterial))target.resolve("menu-$phase-${if(dark) "dark" else "light"}-$version.png").outputStream().use{img.compress(Bitmap.CompressFormat.PNG,100,it)}
      previous.recycle();clearMaterial.recycle()
     }
     old.recycle()
    };raw.recycle()
   }
   assertFalse("Each surface changes with the actual new backplate",outputs[0].sameAs(outputs[2]));outputs.forEach{it.recycle()}
  }
  // Controlled host sequence: real Compose source positions + production scroll dynamics + same AGSL.
  // Not a device recording and not a claim of end-to-end GPU rendering.
  val clearDir=File("build/clear112-previews").apply{mkdirs()}
  val trace=mutableListOf<Triple<Int,Float,Float>>();val dynamics=GlassFlowDynamics();var last=0
  val positions=listOf(0,0,18,42,80,128,184,240,220,186,142,100,66,40)+List(24){40}
  for((i,pos)in positions.withIndex()) {
   val delta=(pos-last)/1.5f;if(delta!=0f)dynamics.impulse(delta,.04f)else dynamics.stop()
   dynamics.step(.04f,if(delta==0f).2f else 0f);trace+=Triple(pos,dynamics.value,dynamics.phase);last=pos
  }
  assertEquals(0f,trace.last().second,0f)
  val sources=mutableMapOf<Boolean,MutableList<Bitmap>>()
  for(dark in listOf(false,true)) {
   compose.runOnIdle{fixtureDark=dark};compose.waitForIdle()
   val frames=mutableListOf<Bitmap>()
   for((pos,_,_)in trace) {
    compose.runOnIdle{runBlocking{scroll.scrollTo(pos)}};compose.waitForIdle();val raw=capture()
    val crop=Bitmap.createBitmap(raw.width+72,222,Bitmap.Config.ARGB_8888)
    Canvas(crop).drawBitmap(raw,36f,(36-(raw.height-150)).toFloat(),null);frames+=crop;raw.recycle()
   };sources[dark]=frames
  }
  fun owner(v:android.view.View):android.view.View? {if(v.javaClass.name.contains("AndroidComposeView"))return v;if(v is android.view.ViewGroup)for(i in 0 until v.childCount)owner(v.getChildAt(i))?.let{return it};return null}
  val old111=javaClass.getResourceAsStream("/glass-v111-reference.agsl")!!.bufferedReader().readText()
  for(dark in listOf(false,true)) {
   val readable=GlassReadability()
   fun content(fallback:Boolean) {
    compose.runOnUiThread{compose.activity.setContent{
     MaterialTheme(colorScheme=if(dark)darkColorScheme(surface=Color(0xFF223029),onSurface=Color(0xFFEEF2EB))else lightColorScheme(surface=Color(0xFFF7F3EB),onSurface=Color(0xFF2F302A))) {
      CompositionLocalProvider(LocalGlassReadability provides readable) {
       Box(Modifier.fillMaxSize(),contentAlignment=androidx.compose.ui.Alignment.BottomCenter) {
        if(fallback)FloatingHomeNav("shelf",{})else HomeNavTargets("shelf",{},glassColors(),Modifier.fillMaxWidth()
         .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
         .padding(horizontal=8.dp,vertical=5.dp))
       }
      }
     }
    }}
    compose.runOnIdle{androidx.core.view.ViewCompat.dispatchApplyWindowInsets(owner(compose.activity.window.decorView)!!,androidx.core.view.WindowInsetsCompat.Builder().setInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),androidx.core.graphics.Insets.of(0,0,0,48)).setVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars(),true).build())};compose.waitForIdle()
   }
   fun foreground():Bitmap {lateinit var full:Bitmap;compose.runOnIdle{val v=owner(compose.activity.window.decorView)!!;full=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(full))};val cropped=Bitmap.createBitmap(full,0,full.height-150,full.width,150);full.recycle();return cropped}
   content(false);val fg=foreground();assertEquals(0,android.graphics.Color.alpha(fg.getPixel(1,149)));val glyphs=readable.labels.values.map{it.translate(androidx.compose.ui.geometry.Offset(0f,-(a.height-150).toFloat())).inflate(6f)};assertEquals(3,glyphs.size)
   fg.compress(Bitmap.CompressFormat.PNG,100,clearDir.resolve("foreground-${if(dark) "dark" else "light"}.png").outputStream())
   for((i,state)in trace.withIndex()) {
    val source=sources.getValue(dark)[i];val min=androidx.compose.ui.geometry.Offset(36f,0f);val max=androidx.compose.ui.geometry.Offset(36f+a.width,186f)
    val old=NavNativeRaster.render(source,a.width,150,36,1.5f,dark,18f,kernel=old111,cornerDp=0f,sampleMin=min,sampleMax=max)
    val clear=NavNativeRaster.render(source,a.width,150,36,1.5f,dark,18f,cornerDp=0f,sampleMin=min,sampleMax=max,clear=true,labels=glyphs+androidx.compose.ui.geometry.Rect(0f,102f,a.width.toFloat(),150f),motion=state.second,phase=state.third)
    for((version,img)in listOf("v111" to old,"v112" to clear)) {
     val pane=Bitmap.createBitmap(img,36,36,a.width,150);Canvas(pane).drawBitmap(fg,0f,0f,null)
     clearDir.resolve("${if(dark) "dark" else "light"}-$version-${i.toString().padStart(2,'0')}.png").outputStream().use{pane.compress(Bitmap.CompressFormat.PNG,100,it)};pane.recycle();img.recycle()
    };source.recycle()
   }
   content(true);val fallback=foreground();clearDir.resolve("fallback-${if(dark) "dark" else "light"}.png").outputStream().use{fallback.compress(Bitmap.CompressFormat.PNG,100,it)};fallback.recycle();fg.recycle()
  }
  clearDir.resolve("trace.json").writeText(org.json.JSONArray().apply{trace.forEachIndexed{i,(pos,flow,phase)->put(org.json.JSONObject().put("frame",i).put("offset",pos).put("flow",flow).put("phase",phase))}}.toString())
  listOf(a,b,before,after,first,second).forEach{it.recycle()}
 }
}
