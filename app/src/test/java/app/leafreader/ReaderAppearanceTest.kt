package app.leafreader

import android.app.Application
import android.graphics.*
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.Chapter
import app.leafreader.data.ReaderSettings
import app.leafreader.reader.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReaderAppearanceTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val text = Chapter("art-preview", "第一章　雨后的纸页", List(20) {
        listOf("雨停的时候，窗外的树叶还在滴水。我把书放到桌边，纸页映着午后的光。",
            "那些深浅不一的细小纤维，像一条条安静的河流。文字仍然清楚，读到哪里，就停在哪里。",
            "远处有人撑伞走过石桥。风吹动窗帘，新的一页仍沿着刚才的句子展开。", "这一段文字由栖页自写，只用来核对纸张质感、顶部留白和底部安全位置。")[it%4]
    }).text
    private fun view(w: Int, h: Int, settings: ReaderSettings = ReaderSettings(palette = 4)): NativeReaderView = NativeReaderView(app).apply {
        layout(0,0,w,h); bind(text,"雨后纸页 · 第一章",0,1,settings)
        updateProgressContext(0,3,true,null); updateSystemStatus(ReadingStatusSnapshot("21:18","68%"))
    }
    private class FlatCanvas(bitmap: Bitmap) : Canvas(bitmap) {
        override fun drawPaint(paint: Paint) { if (paint.shader == null) super.drawPaint(paint) }
    }
    private fun render(view: NativeReaderView, flat: Boolean = false): Bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888).also {
        view.draw(if (flat) FlatCanvas(it) else Canvas(it))
    }
    private fun save(bitmap: Bitmap, name: String) {
        val folder = File("build/appearance-previews").apply { mkdirs() }
        File(folder,name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
    }
    private fun label(canvas: Canvas, text: String, x: Float, y: Float) {
        canvas.drawText(text,x,y,Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(60,52,38);textSize=32f;typeface=Typeface.DEFAULT_BOLD })
    }

    @Test fun packagedPaperIsVisibleCachedAndReallyDrawnBehindTheSameNativeText() {
        val reader=view(1080,2280); val anchor=reader.currentOffset; val passes=reader.layoutPasses
        val flat=render(reader,true); val textured=render(reader)
        val samples=(80 until 2200 step 7).map { Color.red(textured.getPixel(20,it)).toDouble() }
        val mean=samples.average(); val std=sqrt(samples.map { (it-mean)*(it-mean) }.average())
        assertTrue("paper keeps restrained visible variation rather than a flat fill or heavy clouds",std in 1.0..3.5)
        assertTrue((80 until 2200 step 7).all { flat.getPixel(20,it)==papers[4].background })
        assertEquals(anchor,reader.currentOffset); assertEquals(passes,reader.layoutPasses)
        assertEquals(1,KraftPaperTexture.generationCount)
        val background=Bitmap.createBitmap(1080,2280,Bitmap.Config.ARGB_8888)
        KraftPaperTexture.draw(Canvas(background),app.resources)
        for (y in 80 until 2200 step 37) assertEquals(background.getPixel(20,y),textured.getPixel(20,y))
        save(textured,"kraft-native-reader.png")
        val comparison=Bitmap.createBitmap(2184,2352,Bitmap.Config.ARGB_8888);val canvas=Canvas(comparison)
        canvas.drawColor(Color.rgb(246,243,235));label(canvas,"同色纯色对照",24f,46f);label(canvas,"新版真实纸纹",1120f,46f)
        canvas.drawBitmap(flat,0f,72f,null);canvas.drawBitmap(textured,1104f,72f,null)
        save(comparison,"kraft-comparison.png")
        println("Real native renderer, identical text/layout; only the resource-shader draw is omitted on the flat side. Background red std=$std")
        background.recycle();flat.recycle();textured.recycle();comparison.recycle()
    }

    @Test fun footerInsetsAreIndependentAndBatteryIsAnIconWithoutVisiblePercentText() {
        val density=app.resources.displayMetrics.density
        for ((w,h) in listOf(720 to 1560,1080 to 2280,1920 to 1080)) {
            var reference: FooterPlacement?=null
            for (margin in listOf(8f,48f)) {
                val reader=view(w,h,ReaderSettings(32f,1.9f,4,32f,margin,50f));reader.turn(1)
                val anchor=reader.currentOffset;val passes=reader.layoutPasses
                var lastFilled=-1
                for (percent in listOf(0,50,100)) {
                    reader.updateSystemStatus(ReadingStatusSnapshot("下午11:59","$percent%"))
                    val image=render(reader); val f=reader.footerPlacement!!
                    assertEquals("下午11:59",f.timeText);assertFalse(f.timeText.contains('%'));assertEquals(percent,f.batteryPercent)
                    assertTrue(f.leftX>=minOf(30*density,w*.28f));assertTrue(f.battery.right<=w-f.inset+.1f)
                    val paint=Paint().apply { textSize=f.textSize }
                    assertTrue(f.leftX+paint.measureText(f.leftText)<f.timeX)
                    assertTrue(f.battery.top>=reader.currentGeometry!!.footerTop);assertTrue(f.battery.bottom<=h)
                    if (reference!=null) assertEquals(reference!!.leftX,f.leftX,.01f) else reference=f
                    var filled=0
                    for (y in f.battery.top.toInt()..f.battery.bottom.toInt()) for (x in f.battery.left.toInt()..f.battery.right.toInt())
                        if (Color.red(image.getPixel(x.coerceIn(0,w-1),y.coerceIn(0,h-1)))<120) filled++
                    assertTrue(filled>lastFilled);lastFilled=filled
                    assertTrue(reader.contentDescription.contains("电量$percent%"))
                    assertEquals(anchor,reader.currentOffset);assertEquals(passes,reader.layoutPasses)
                    image.recycle()
                }
                reader.updateSystemStatus(ReadingStatusSnapshot("—","—"));val unknown=render(reader)
                assertNull(reader.footerPlacement!!.batteryPercent);unknown.recycle()
            }
        }
        val canvas=Canvas(Bitmap.createBitmap(720,1560,Bitmap.Config.ARGB_8888))
        val f=ReaderFooter().draw(canvas,720,1560,1479f,density,33f,120f,"本章 50%  全书约 20%",ReadingStatusSnapshot("21:18","60%"),Paint(Paint.ANTI_ALIAS_FLAG),Color.BLACK)
        assertTrue(f.inset>=124f);assertTrue(f.battery.right<=720-124f)
    }

    private fun devicePreview(w: Int,h: Int): Bitmap {
        val safeTop=90;val safeBottom=72
        val reader=view(w,h-safeTop-safeBottom)
        val image=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);val canvas=Canvas(image)
        canvas.drawColor(Color.rgb(42,41,36));canvas.save()
        canvas.clipPath(Path().apply { addRoundRect(RectF(0f,0f,w.toFloat(),h.toFloat()),96f,96f,Path.Direction.CW) })
        canvas.drawColor(papers[4].background);canvas.save();canvas.clipRect(0,safeTop,w,h-safeBottom)
        canvas.translate(0f,safeTop.toFloat());reader.draw(canvas);canvas.restore()
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.rgb(42,41,36) }
        canvas.drawRoundRect(RectF(w/2f-90,0f,w/2f+90,48f),24f,24f,paint)
        canvas.drawRoundRect(RectF(w/2f-70,h-30f,w/2f+70,h-21f),5f,5f,paint)
        canvas.restore();return image
    }
    @Test fun compactHeaderReturnsHeightToBodyAndRoundedCutoutPreviewsRemainReadable() {
        val reader=view(1080,2280);val density=app.resources.displayMetrics.density;val g=reader.currentGeometry!!
        assertTrue(g.top<=50*density)
        val oldTextHeight=2280-(2280-g.footerTop)-25*density-(40+25)*density
        assertTrue(g.textHeight>=oldTextHeight+15*density)
        val first=render(reader);var firstInk=Int.MAX_VALUE
        for(y in 0 until (24*density).toInt()) for(x in 70 until 1000) if(Color.red(first.getPixel(x,y))<120) firstInk=minOf(firstInk,y)
        assertTrue(firstInk<10*density);first.recycle()
        reader.turn(1);val anchor=reader.currentOffset;reader.layout(0,0,1920,1080);assertEquals(anchor,reader.currentOffset)
        val normal=devicePreview(1080,2280);val narrow=devicePreview(720,1560)
        val image=Bitmap.createBitmap(1848,2352,Bitmap.Config.ARGB_8888);val canvas=Canvas(image);canvas.drawColor(Color.rgb(246,243,235))
        label(canvas,"常规 · 模拟安全区",20f,46f);label(canvas,"窄屏 · 模拟圆角/切口",1120f,46f)
        canvas.drawBitmap(normal,0f,72f,null);canvas.drawBitmap(narrow,1128f,72f,null)
        save(image,"reader-insets.png");normal.recycle();narrow.recycle();image.recycle()
        val landscape=view(1920,1080);val landscapeImage=render(landscape);save(landscapeImage,"reader-landscape.png");landscapeImage.recycle()
    }
}
