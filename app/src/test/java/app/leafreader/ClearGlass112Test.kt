package app.leafreader

import android.graphics.*
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ClearGlass112Test {
 private fun luminance(color:Int):Double {
  fun channel(v:Int):Double {val c=v/255.0;return if(c<=.04045)c/12.92 else ((c+.055)/1.055).pow(2.4)}
  return .2126*channel(Color.red(color))+.7152*channel(Color.green(color))+.0722*channel(Color.blue(color))
 }
 @Test fun clearBodyPreservesSubstantiallyMoreActualColorThanV111AtTheSamePixel() {
  val old=javaClass.getResourceAsStream("/glass-v111-reference.agsl")!!.bufferedReader().readText()
  val input=Bitmap.createBitmap(396,216,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.rgb(30,110,160))}
  val before=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,kernel=old)
  val next=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,clear=true)
  val p=before.getPixel(198,108);val q=next.getPixel(198,108)
  assertTrue(Color.red(p)-Color.red(q)>100);assertTrue(Color.blue(q)-Color.red(q)>80)
  listOf(input,before,next).forEach{it.recycle()}
 }
 @Test fun smallGlyphFootprintsKeepStrongForegroundReadableOverBlackWhiteAndBusyBackplates() {
  for(dark in listOf(false,true))for(color in listOf(Color.BLACK,Color.WHITE,Color.RED,Color.BLUE,Color.YELLOW)) {
   val b=Bitmap.createBitmap(396,216,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
   val out=NavNativeRaster.render(b,324,144,36,1.5f,dark,18f,clear=true,labels=listOf(Rect(130f,44f,194f,100f)))
   val background=luminance(out.getPixel(198,108));val foreground=luminance(if(dark)Color.rgb(238,242,235)else Color.rgb(41,60,53))
   val contrast=(maxOf(background,foreground)+.05)/(minOf(background,foreground)+.05)
   assertTrue("Glyph contrast for theme=$dark source=$color was $contrast",contrast>=4.5)
   b.recycle();out.recycle()
  }
 }
 @Test fun flowChangesSamplingAndReversalButZeroFlowExactlyRestoresTheSameStaticFrame() {
  val input=Bitmap.createBitmap(396,216,Bitmap.Config.ARGB_8888).apply{for(y in 0 until height)for(x in 0 until width)setPixel(x,y,Color.rgb(x*17%256,y*9%256,(x+y)*7%256))}
  val still=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,clear=true)
  val forward=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,clear=true,motion=.8f,phase=1f)
  val reverse=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,clear=true,motion=-.8f,phase=1f)
  val stopped=NavNativeRaster.render(input,324,144,36,1.5f,false,18f,clear=true,motion=0f,phase=5f)
  assertFalse(still.sameAs(forward));assertFalse(forward.sameAs(reverse));assertTrue(still.sameAs(stopped))
  listOf(input,still,forward,reverse,stopped).forEach{it.recycle()}
 }
 @Test fun visibleAnchorTrackerHandlesDirectionVariableRowsAndRejectsTeleportsOrDifferentTabs() {
  val t=GlassMotionTracker();t.delta(GlassMotionSample("shelf",false,mapOf(0 to 0,1 to 160)))
  assertEquals(30f,t.delta(GlassMotionSample("shelf",true,mapOf(0 to -30,1 to 130))),0f)
  assertEquals(140f,t.delta(GlassMotionSample("shelf",true,mapOf(1 to -10,2 to 110))),0f)
  assertEquals(-20f,t.delta(GlassMotionSample("shelf",true,mapOf(1 to 10,2 to 130))),0f)
  assertEquals(0f,t.delta(GlassMotionSample("find",true,mapOf(1 to 0))),0f)
  assertEquals(0f,t.delta(GlassMotionSample("find",true,mapOf(50 to 0))),0f)
  assertEquals(0f,t.delta(GlassMotionSample("find",false,mapOf(50 to -70))),0f)
 }
 @Test fun boundedDynamicsStartReverseAndSettleWithoutAnIdleRipple() {
  val d=GlassFlowDynamics();repeat(10){d.impulse(20f,.016f);assertTrue(d.step(.016f,0f))};assertTrue(d.value>0)
  repeat(12){d.impulse(-20f,.016f);d.step(.016f,0f)};assertTrue(d.value<0);assertTrue(d.value>=-1f)
  d.stop();repeat(100){d.step(.016f,.2f)};assertEquals(0f,d.value,0f);assertFalse(d.step(.016f,2f))
  val phase=d.phase;repeat(20){d.step(.016f,10f)};assertEquals(phase,d.phase,0f)
  d.impulse(100f,.016f);d.step(.016f,0f);d.reset();assertEquals(0f,d.value,0f);assertEquals(0f,d.phase,0f)
 }
}
