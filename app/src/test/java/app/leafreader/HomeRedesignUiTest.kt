package app.leafreader

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import app.leafreader.data.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Only self-authored UI fixtures; no user reference image, books or credentials. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class HomeRedesignUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private lateinit var vm:ReaderViewModel
    private class Source(val count:Int,val missingMetadata:Boolean=false):BookSource {
        override val id="home-redesign-fixture"
        var calls=0;var fail=false;var pending:CompletableDeferred<Unit>?=null
        val titles=listOf("远行手记","风吹过书页","河岸的灯","雨后漫步","山间来信","漫长的下午","星光与归途","城市一角")
        val colors=listOf(0xFF49665F,0xFF7C6556,0xFF4B586D,0xFF7D6B43)
        override suspend fun books():List<Book>{calls++;pending?.await();if(fail)throw SourceProblem(FailureKind.NETWORK,"测试网络暂不可用，原书架保留");return (0 until count).map{Book("home-$it",if(missingMetadata)"很长的测试书名用于检查小屏幕里的两行显示和省略" else titles[it%titles.size],if(missingMetadata)"" else "自写测试作者",if(missingMetadata)"" else "沿着街道慢慢走，记录日常里值得留住的片刻。","",3,if(missingMetadata)0 else colors[it%colors.size])}}
        override suspend fun chapters(bookId:String)=listOf(ChapterRef("one","第一章"))
        override suspend fun chapter(bookId:String,chapterId:String)=Chapter(chapterId,"第一章",listOf("这段文字只用于验证本机界面与续读。".repeat(100)))
    }
    private fun setup(source:Source) {
        compose.runOnUiThread {
            compose.activity.viewModelStore.clear()
            vm=androidx.lifecycle.ViewModelProvider(compose.activity,object:androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun<T:androidx.lifecycle.ViewModel>create(modelClass:Class<T>):T=ReaderViewModel(compose.activity.application,source,sessionVault=MemorySessionVault()) as T
            })[ReaderViewModel::class.java]
            compose.activity.setContent{MaterialTheme(colorScheme=lightColorScheme(primary=Color(0xFFA94435),background=Color(0xFFF7F3EB),surface=Color(0xFFF7F3EB),onBackground=Color(0xFF2F302A),onSurface=Color(0xFF2F302A),surfaceContainer=Color(0xFFF0EAE0))){ReaderApp(vm)}}
        }
        await{!vm.ui.value.shelfLoading}
    }
    private fun await(test:()->Boolean)=compose.waitUntil(15000){compose.waitForIdle();test()}
    private fun pull()=compose.onNodeWithTag("shelf-refresh").performTouchInput { swipeDown(startY=height*.12f,endY=height*.88f,durationMillis=600) }
    private fun snapshot(name:String) {compose.runOnIdle{
        val v=compose.activity.window.decorView;val bitmap=Bitmap.createBitmap(v.width,v.height,Bitmap.Config.ARGB_8888);v.draw(Canvas(bitmap))
        File("build/home012-previews").apply{mkdirs()}.resolve("$name.png").outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
    }}
    @Test fun shortListPullCoalescesAndFailureKeepsShelfAndExactProgress() {
        val source=Source(1);setup(source);val book=vm.ui.value.shelfBooks.single()
        compose.runOnIdle{vm.store.save(book.id,"one",23);source.pending=CompletableDeferred()}
        val saved=vm.store.position(book.id)
        pull();await{source.calls==2 && vm.ui.value.shelfLoading}
        pull();compose.runOnIdle{assertEquals(2,source.calls);source.fail=true;source.pending!!.complete(Unit)}
        await{!vm.ui.value.shelfLoading}
        compose.onNodeWithTag("shelf-book-${book.id}").assertIsDisplayed()
        compose.runOnIdle{assertEquals(saved,vm.store.position(book.id));assertNotNull(vm.ui.value.shelfError);assertEquals(1,vm.ui.value.shelfBooks.size)}
        compose.onNodeWithText("刷新").assertDoesNotExist();compose.onNodeWithText("下载设置").assertDoesNotExist()
    }
    @Test fun emptyShelfSupportsPullAndAccessibleRefreshWithoutInventingBooks() {
        val source=Source(0);setup(source);pull();await{source.calls==2 && !vm.ui.value.shelfLoading}
        val actions=compose.onNodeWithTag("shelf-refresh").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnIdle{assertTrue(actions.single{it.label=="刷新书架"}.action())}
        await{source.calls==3 && !vm.ui.value.shelfLoading};compose.runOnIdle{assertTrue(vm.ui.value.shelfBooks.isEmpty())}
    }
    @Test fun threeTabsKeepScrollAndSettingsOwnsSourceEntry() {
        val source=Source(8);setup(source);snapshot("01-shelf")
        compose.onNodeWithTag("shelf-list").performScrollToIndex(6)
        compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("settings-source").assertIsDisplayed();snapshot("02-settings")
        compose.activityRule.scenario.onActivity{it.onBackPressedDispatcher.onBackPressed()}
        compose.onNodeWithTag("tab-shelf").assertIsSelected()
        compose.onNodeWithTag("shelf-book-home-5").assertIsDisplayed()
        compose.onNodeWithTag("tab-find").performClick();compose.onNodeWithText("来源").assertDoesNotExist()
        compose.onNodeWithTag("find-login").performClick();compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("settings-source").performClick();compose.onNodeWithTag("source-address-editor").assertIsDisplayed();compose.onNodeWithText("关闭").performClick()
        compose.activityRule.scenario.recreate();compose.onNodeWithTag("tab-settings").assertIsSelected()
        compose.onNodeWithTag("tab-shelf").performClick()
        compose.onNodeWithTag("shelf-list").performScrollToIndex(0)
        compose.onNodeWithTag("shelf-book-home-0").performTouchInput{longClick()};compose.onNodeWithTag("shelf-book-actions").assertIsDisplayed()
        compose.runOnIdle{assertNull(vm.store.position("home-0"))};compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("shelf-book-home-0").performClick();await{!vm.ui.value.loading && vm.ui.value.chapter!=null}
        compose.onNodeWithTag("tab-settings").assertDoesNotExist();compose.runOnIdle{vm.position(23);vm.backToShelf()}
        compose.onNodeWithTag("shelf-book-home-0").performClick();await{!vm.ui.value.loading && vm.ui.value.chapter!=null};compose.runOnIdle{assertEquals(23,vm.ui.value.offset)}
    }
    @Test @Config(qualifiers="w320dp-h480dp-xhdpi")
    fun smallScreenMissingMetadataAndLongTitleKeepMenuAndSettingsAccessible() {
        setup(Source(1,true));compose.onNodeWithText("作者未提供").assertIsDisplayed();compose.onNodeWithText("尚未开始阅读").assertIsDisplayed();snapshot("03-small-shelf")
        compose.onNodeWithTag("shelf-book-home-0").performSemanticsAction(SemanticsActions.OnLongClick){it()}
        compose.onNodeWithText("关闭").assertIsDisplayed().performClick()
        compose.onNodeWithTag("tab-settings").performClick();compose.onNodeWithTag("automatic-wifi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("settings-source").performScrollTo().performClick();compose.onNodeWithText("关闭").performClick()
        compose.onNodeWithTag("tab-settings").assertIsSelected()
    }
}
