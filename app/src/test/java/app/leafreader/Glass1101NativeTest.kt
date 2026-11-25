package app.leafreader

import android.graphics.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class Glass1101NativeTest {
 @Test fun dialogWindowCoordinatesMapToActivitySourceAndClampAtPhysicalEdges() {
  val crop=glassSampleWindow(Offset(88f,450f),Offset(10f,30f),IntSize(1080,2200),600,320,36)!!
  assertEquals(Offset(78f,420f),crop.delta);assertEquals(Offset.Zero,crop.min);assertEquals(Offset(672f,392f),crop.max)
  val edge=glassSampleWindow(Offset(10f,2130f),Offset(10f,30f),IntSize(1080,2200),1080,100,36)!!
  assertEquals(Offset(36f,0f),edge.min);assertEquals(Offset(1116f,136f),edge.max)
  assertNull(glassSampleWindow(Offset(0f,9000f),Offset.Zero,IntSize(400,800),300,200,36))
 }
 private fun source():Bitmap=Bitmap.createBitmap(792,252,Bitmap.Config.ARGB_8888).apply{
  val c=Canvas(this);c.drawColor(Color.rgb(241,243,235));val p=Paint()
  for(i in 0..11){p.color=Color.rgb((50+i*21)%180,90+(i*17)%100,70+(i*31)%100);c.drawRect((i*65).toFloat(),0f,(i*65+32).toFloat(),252f,p);p.color=Color.DKGRAY;c.drawRect((i*65+40).toFloat(),54f,(i*65+58).toFloat(),58f,p)}
 }
 private fun delta(a:Bitmap,b:Bitmap):Long {var n=0L;for(y in 36 until a.height-36)for(x in 36 until a.width-36){val p=a.getPixel(x,y);val q=b.getPixel(x,y);n+=kotlin.math.abs(Color.red(p)-Color.red(q))+kotlin.math.abs(Color.green(p)-Color.green(q))+kotlin.math.abs(Color.blue(p)-Color.blue(q))};return n}
 @Test fun strongerKernelHasMeasurablyMoreEdgeDisplacementThanV110WithSameSource() {
  val original=javaClass.getResourceAsStream("/glass-v110-reference.agsl")!!.bufferedReader().readText();val b=source()
  for(dark in listOf(false,true))for(corner in listOf(0f,28f)){
   val oldFlat=NavNativeRaster.render(b,720,180,36,1.5f,dark,0f,original,corner);val old=NavNativeRaster.render(b,720,180,36,1.5f,dark,6.75f,original,corner)
   val flat=NavNativeRaster.render(b,720,180,36,1.5f,dark,0f,cornerDp=corner);val next=NavNativeRaster.render(b,720,180,36,1.5f,dark,18f,cornerDp=corner)
   if(corner>0)assertTrue("Displacement, not only brighter highlights",delta(flat,next)>delta(oldFlat,old)*1.2)
   assertEquals(b.width,next.width);assertEquals(b.height,next.height);listOf(oldFlat,old,flat,next).forEach{it.recycle()}
  };b.recycle()
 }
 @Test fun independentSurfaceEffectsDoNotMutateEachOthersDimensionsOrCache() {
  val nav=NavLensEffect();val modal=NavLensEffect();val n=nav.configure(1080,228,36,1.5f,false,0f)
  val m=modal.configure(900,600,36,1.5f,false,28f);assertNotSame(n,m);assertSame(n,nav.configure(1080,228,36,1.5f,false,0f));assertSame(m,modal.configure(900,600,36,1.5f,false,28f))
  assertNotSame(m,modal.configure(600,320,36,1.5f,true,28f,Offset(2f,0f),Offset(672f,392f)))
 }
}
