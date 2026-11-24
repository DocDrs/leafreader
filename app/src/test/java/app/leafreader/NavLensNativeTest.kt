package app.leafreader

import android.graphics.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

/** Host-only native AGSL oracle. This bypass is NEVER used by the APK or claimed as device GPU testing. */
internal object NavNativeRaster {
 fun render(input:Bitmap,w:Int,h:Int,guard:Int,density:Float,dark:Boolean,strength:Float):Bitmap {
  val shader=RuntimeShader(NavLensKernel.SOURCE)
  shader.setFloatUniform("lensSize",w.toFloat(),h.toFloat());shader.setFloatUniform("guard",guard.toFloat())
  shader.setFloatUniform("radius",minOf(30*density,w/2f,h/2f));shader.setFloatUniform("strength",strength)
  shader.setColorUniform("wash",if(dark)Color.rgb(40,55,48)else Color.rgb(247,249,243))
  shader.setInputShader("backdrop",BitmapShader(input,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP))
  val out=Bitmap.createBitmap(input.width,input.height,Bitmap.Config.ARGB_8888)
  val canvas=Canvas(out)
  // Android's public software Canvas rejects RuntimeShader. Enable Skia's host raster oracle only.
  ReflectionHelpers.callInstanceMethod<Void>(canvas,"setHwFeaturesInSwModeEnabled",ReflectionHelpers.ClassParameter.from(Boolean::class.javaPrimitiveType,true))
  canvas.drawPaint(Paint().apply{this.shader=shader});return out
 }
}
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NavLensNativeTest {
 private fun source(w:Int,h:Int)=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888).apply{for(y in 0 until h)for(x in 0 until w)setPixel(x,y,Color.rgb((x*13)%256,(y*19)%256,if(x/5%2==0)0 else 255))}
 @Test fun realAgslGeometricDisplacementChangesPixelsBeyondTintAlone() {
  val b=source(336,132);val flat=NavNativeRaster.render(b,300,96,18,1.5f,false,0f);val lens=NavNativeRaster.render(b,300,96,18,1.5f,false,6.75f)
  var changed=0;for(y in 18 until 114)for(x in 18 until 318)if(flat.getPixel(x,y)!=lens.getPixel(x,y))changed++
  assertTrue("Actual child-shader sampling must change pixels",changed>500);listOf(b,flat,lens).forEach{it.recycle()}
 }
 @Test fun tinyAndNormalFramesRemainOpaqueAndBothThemeUniformsApply() {
  for((w,h) in listOf(1 to 1,300 to 96,420 to 120)) {
   val b=source(w+36,h+36);val light=NavNativeRaster.render(b,w,h,18,1.5f,false,6.75f);val dark=NavNativeRaster.render(b,w,h,18,1.5f,true,6.75f)
   assertEquals(255,Color.alpha(light.getPixel(18+w/2,18+h/2)));assertEquals(255,Color.alpha(dark.getPixel(18+w/2,18+h/2)));assertNotEquals(light.getPixel(18+w/2,18+h/2),dark.getPixel(18+w/2,18+h/2));listOf(b,light,dark).forEach{it.recycle()}
  }
 }
 @Test fun renderEffectCacheReusesOnlyEquivalentDimensionsAndTheme() {
  val e=NavLensEffect();val a=e.configure(300,96,18,1.5f,false);assertSame(a,e.configure(300,96,18,1.5f,false));assertNotSame(a,e.configure(420,96,18,1.5f,false));assertNotSame(a,e.configure(300,96,18,1.5f,true))
 }
 @Test fun unsupportedConditionsAlwaysChooseStaticAndCropAllocationsAreBounded() {
  assertTrue(NavRefractionPolicy.enabled(33,true,false,false,true,true))
  for(v in listOf(listOf(26,true,false,false,true,true),listOf(32,true,false,false,true,true),listOf(35,false,false,false,true,true),listOf(35,true,true,false,true,true),listOf(35,true,false,true,true,true),listOf(35,true,false,false,false,true),listOf(35,true,false,false,true,false)))
   assertFalse(NavRefractionPolicy.enabled(v[0] as Int,v[1] as Boolean,v[2] as Boolean,v[3] as Boolean,v[4] as Boolean,v[5] as Boolean))
  assertTrue(NavRefractionPolicy.bounded(1000,1200));assertFalse(NavRefractionPolicy.bounded(1001,1200));assertFalse(NavRefractionPolicy.bounded(0,100));assertFalse(NavRefractionPolicy.bounded(Int.MAX_VALUE,Int.MAX_VALUE))
 }
}
