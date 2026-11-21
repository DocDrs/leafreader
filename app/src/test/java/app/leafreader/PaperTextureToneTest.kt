package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import android.app.Application
import app.leafreader.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.pow
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperTextureToneTest {
    private val app:Application=ApplicationProvider.getApplicationContext()
    private fun luminance(c:Int):Double {
        fun channel(v:Int):Double {val n=v/255.0;return if(n<=.04045)n/12.92 else ((n+.055)/1.055).pow(2.4)}
        return .2126*channel(Color.red(c))+.7152*channel(Color.green(c))+.0722*channel(Color.blue(c))
    }
    @Test fun originalFullSheetKeepsWarmToneContrastAndNoTileBoundariesAcrossViewports() {
        val ink=luminance(papers[4].ink);val muted=luminance(papers[4].muted)
        for((w,h) in listOf(591 to 1279,640 to 1386,1140 to 591)) {
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);KraftPaperTexture.draw(Canvas(bitmap),app.resources,w,h)
            val samples=(0 until h step 5).flatMap{y->(0 until w step 5).map{x->bitmap.getPixel(x,y)}}
            assertTrue(samples.map{Color.red(it)}.average() in 212.0..222.0)
            assertTrue(samples.map{Color.green(it)}.average() in 193.0..204.0)
            assertTrue(samples.map{Color.blue(it)}.average() in 159.0..173.0)
            assertTrue(samples.all{(luminance(it)+.05)/(ink+.05)>6.5})
            assertTrue(samples.all{(luminance(it)+.05)/(muted+.05)>4.5})
            val columns=(0 until w).map{x->(0 until h step 3).map{y->Color.red(bitmap.getPixel(x,y))}.average()}
            assertTrue("no vertical tile seam",columns.zipWithNext().all{abs(it.first-it.second)<1.2})
            val rows=(0 until h).map{y->(0 until w step 3).map{x->Color.red(bitmap.getPixel(x,y))}.average()}
            assertTrue("no horizontal tile seam",rows.zipWithNext().all{abs(it.first-it.second)<1.2})
            bitmap.recycle()
        }
        assertEquals(1,KraftPaperTexture.generationCount)
    }
}
