package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import app.leafreader.reader.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Actual Compose -> AndroidView -> NativeReaderView rendering, only self-authored text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w394dp-h853dp-hdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PaperTuningPreviewTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val source=object:BookSource {
        override val id="paper-tuning-only-fixture"
        override suspend fun books()=listOf(Book("paper-preview","纸页间的日常","栖页自写","仅用于纸张对照","",1,0xFF516D66))
        override suspend fun chapters(bookId:String)=listOf(ChapterRef("paper-one","第一章　午后的光"))
        override suspend fun chapter(bookId:String,chapterId:String)=Chapter(chapterId,"第一章　午后的光",List(18){i->listOf(
            "雨停了，窗边的树叶还留着水珠。我把书放在桌上，纸页接住了午后的光。",
            "风从半开的窗吹进来，翻过一页，又轻轻停住。走廊里传来脚步声，很快就远了。",
            "我沿着刚才的句子继续读。字与字之间留有呼吸的空隙，深浅的纸纹安静地留在背后。",
            "这是一段自写的测试正文，只用来核对纸色、颗粒、明暗和阅读对比，不包含外部书籍内容。"
        )[i%4]})
    }
    private fun find(v:View):NativeReaderView? {if(v is NativeReaderView)return v;if(v is ViewGroup)for(i in 0 until v.childCount)find(v.getChildAt(i))?.let{return it};return null}
    private fun render(name:String,otherThemes:Boolean=false) {
        lateinit var vm:ReaderViewModel
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory{
                @Suppress("UNCHECKED_CAST")override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault()) as T
            })[ReaderViewModel::class.java]
            vm.settings(ReaderSettings(18f,1.55f,4,14f,20f,16f))
            compose.activity.setContent{MaterialTheme{ReaderApp(vm)}}
        }
        compose.waitUntil(15000){compose.waitForIdle();!vm.ui.value.shelfLoading}
        compose.runOnIdle{vm.open(vm.ui.value.shelfBooks.single())}
        compose.waitUntil(15000){compose.waitForIdle();find(compose.activity.window.decorView)!=null}
        val folder=File("build/paper014-previews").apply{mkdirs()}
        fun snapshot(filename:String) {compose.runOnIdle{
            val root=compose.activity.window.decorView;val reader=find(root)!!;reader.updateSystemStatus(ReadingStatusSnapshot("21:18","68%"))
            val bitmap=Bitmap.createBitmap(root.width,root.height,Bitmap.Config.ARGB_8888);root.draw(Canvas(bitmap))
            File(folder,filename).outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        }}
        snapshot("$name.png")
        compose.runOnIdle{val reader=find(compose.activity.window.decorView)!!;reader.turn(1);val offset=reader.currentOffset;assertTrue(offset>0);val passes=reader.layoutPasses
            reader.updateSystemStatus(ReadingStatusSnapshot("21:18","50%"));assertEquals(offset,reader.currentOffset);assertEquals(passes,reader.layoutPasses)}
        if(otherThemes)for(palette in 0..3) {compose.runOnIdle{vm.settings(vm.ui.value.settings.copy(palette=palette))};compose.waitForIdle();snapshot("theme-$palette.png")}
    }
    @Test fun normalActualComposeReaderAndOtherThemeFingerprints()=render("normal",true)
    @Test @Config(qualifiers="w320dp-h693dp-xhdpi") fun narrowActualComposeReader()=render("narrow")
    @Test @Config(qualifiers="w760dp-h394dp-hdpi") fun landscapeActualComposeReader()=render("landscape")
}
