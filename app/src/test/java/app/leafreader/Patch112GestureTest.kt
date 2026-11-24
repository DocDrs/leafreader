package app.leafreader

import android.app.Application
import android.app.Activity
import android.graphics.Bitmap
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ScrollView
import android.view.accessibility.AccessibilityNodeInfo
import org.robolectric.Robolectric
import app.leafreader.reader.ReaderTouchBounds
import app.leafreader.data.ChapterGraphic
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.core.app.ApplicationProvider
import app.leafreader.data.ReaderSettings
import app.leafreader.reader.NativeReaderView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34],qualifiers="w360dp-h760dp-hdpi")
class Patch112GestureTest {
 private val app:Application=ApplicationProvider.getApplicationContext()
 private fun reader()=NativeReaderView(app).apply{layout(0,0,540,1140);bind("自写触摸回归正文，左右滑动和点击应保持同一阅读位置规则。\n".repeat(200),"自写章节",0,1,ReaderSettings())}
 private fun drag(v:NativeReaderView,from:Float,to:Float) {
  val t=SystemClock.uptimeMillis();val y=v.height*.5f
  for(i in 0..8){val action=when(i){0->MotionEvent.ACTION_DOWN;8->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE};val e=MotionEvent.obtain(t,t+i*20,action,from+(to-from)*i/8,y,0);v.dispatchTouchEvent(e);e.recycle()}
 }
 @Test fun shortInteriorLeftSwipeFromLeftHandRegionAdvancesOnePage() {
  val v=reader();assertTrue(v.pageCount>3);drag(v,v.width*.20f,v.width*.10f);assertEquals(1,v.currentPage)
 }
 private fun tap(v:NativeReaderView,x:Float) {val t=SystemClock.uptimeMillis();for(a in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)){val e=MotionEvent.obtain(t,t+if(a==MotionEvent.ACTION_UP)80 else 0,a,x,v.height*.5f,0);v.dispatchTouchEvent(e);e.recycle()}}
 private fun send(v:View,action:Int,x:Float,y:Float,t:Long=SystemClock.uptimeMillis()):Boolean {val e=MotionEvent.obtain(t,t+10,action,x,y,0);val result=v.dispatchTouchEvent(e);e.recycle();return result}
 @Test fun interiorBothDirectionsTapsAndMenuUseExactlyOneActionPerGesture() {
  val v=reader();var positions=0;var menus=0;v.onPosition={positions++};v.onMenu={menus++}
  drag(v,v.width*.65f,v.width*.45f);assertEquals(1,v.currentPage);assertEquals(1,positions)
  drag(v,v.width*.35f,v.width*.55f);assertEquals(0,v.currentPage);assertEquals(2,positions)
  tap(v,v.width*.84f);assertEquals(1,v.currentPage);tap(v,v.width*.16f);assertEquals(0,v.currentPage);tap(v,v.width*.5f);assertEquals(1,menus)
 }
 @Test fun shortDragsVerticalIntentAndDirectionChangesNeverBecomeATap() {
  val v=reader();var menus=0;v.onMenu={menus++};val x=v.width*.5f;val y=v.height*.5f
  drag(v,x,x+android.view.ViewConfiguration.get(v.context).scaledTouchSlop*1.25f);assertEquals(0,v.currentPage);assertEquals(0,menus)
  send(v,MotionEvent.ACTION_DOWN,x,y);send(v,MotionEvent.ACTION_MOVE,x+5,y+150);send(v,MotionEvent.ACTION_MOVE,x-180,y+20);send(v,MotionEvent.ACTION_UP,x-180,y+20)
  assertEquals(0,v.currentPage);assertEquals(0,menus)
 }
 @Test fun canceledMultitouchAndReboundGesturesCannotTurnANewChapter() {
  val v=reader();val x=v.width*.65f;val y=v.height*.5f
  send(v,MotionEvent.ACTION_DOWN,x,y);send(v,MotionEvent.ACTION_MOVE,x-150,y);send(v,MotionEvent.ACTION_CANCEL,x-150,y);assertFalse(send(v,MotionEvent.ACTION_UP,x-150,y));assertEquals(0,v.currentPage)
  send(v,MotionEvent.ACTION_DOWN,x,y);send(v,MotionEvent.ACTION_POINTER_DOWN,x,y);send(v,MotionEvent.ACTION_UP,x-150,y);assertEquals(0,v.currentPage)
  send(v,MotionEvent.ACTION_DOWN,x,y);send(v,MotionEvent.ACTION_MOVE,x-150,y);v.bind("另一个章节正文。".repeat(500),"新章",0,2,ReaderSettings());send(v,MotionEvent.ACTION_UP,x-150,y);assertEquals(0,v.currentPage)
 }
 @Test fun bothOutermostEdgesRejectDownMoveAndUpWithoutExcludingSystemGestures() {
  val v=reader();var menu=0;v.onMenu={menu++}
  for(x in listOf(0f,v.width-1f)) {
   assertFalse(send(v,MotionEvent.ACTION_DOWN,x,v.height*.5f));assertFalse(send(v,MotionEvent.ACTION_MOVE,v.width*.5f,v.height*.5f));assertFalse(send(v,MotionEvent.ACTION_UP,v.width*.5f,v.height*.5f))
  }
  assertEquals(0,v.currentPage);assertEquals(0,menu);assertTrue(v.systemGestureExclusionRects.isEmpty())
 }
 @Test fun edgeGeometryCoversSystemInsetsAndUsesWindowRatherThanPanelCoordinates() {
  val full=ReaderTouchBounds.interior(600f,600f,0f,80f,100f,24f);assertFalse(full.contains(79f));assertTrue(full.contains(80f));assertFalse(full.contains(500f))
  val inset=ReaderTouchBounds.interior(300f,700f,150f,80f,100f,24f);assertTrue(inset.contains(0f));assertTrue(inset.contains(299f))
  val partial=ReaderTouchBounds.interior(300f,700f,40f,80f,400f,24f);assertFalse(partial.contains(39f));assertTrue(partial.contains(41f));assertFalse(partial.contains(260f))
  val tiny=ReaderTouchBounds.interior(20f,20f,0f,40f,40f,24f);assertFalse(tiny.contains(10f))
 }
 @Test fun aPanelInsetFromWindowEdgesAcceptsItsOwnLeftmostPixel() {
  val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get();val root=FrameLayout(activity);val v=NativeReaderView(activity)
  root.addView(v,FrameLayout.LayoutParams(600,1140).apply{leftMargin=180;topMargin=100});activity.setContentView(root)
  val decor=activity.window.decorView;decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920)
  v.bind("自写窗口坐标测试。".repeat(500),"坐标",0,1,ReaderSettings());v.turn(1)
  assertTrue(send(v,MotionEvent.ACTION_DOWN,1f,400f));send(v,MotionEvent.ACTION_MOVE,150f,400f);send(v,MotionEvent.ACTION_UP,150f,400f);assertEquals(0,v.currentPage);controller.close()
 }
 @Test fun menusAndSheetsDisablePagingWhileCenterMenuAndNormalAccessibilityRemainUsable() {
  val v=reader();var menus=0;v.onMenu={menus++};v.setReadingInput(false,true)
  drag(v,v.width*.65f,v.width*.4f);tap(v,v.width*.16f);tap(v,v.width*.5f);assertEquals(0,v.currentPage);assertEquals(1,menus)
  assertFalse(v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));v.setReadingInput(false,false);assertFalse(send(v,MotionEvent.ACTION_DOWN,200f,500f));assertFalse(v.performClick())
  v.setReadingInput(true,true);assertTrue(v.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD,null));assertEquals(1,v.currentPage);v.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT,null);assertEquals(0,v.currentPage)
 }
 @Test fun boundaryAndPicturePagesUseTheExistingTurnPathExactlyOnce() {
  val v=reader();var boundaries=0;v.onBoundary={boundaries++};repeat(v.pageCount-1){v.turn(1)};drag(v,v.width*.7f,v.width*.3f);assertEquals(1,boundaries);assertFalse(send(v,MotionEvent.ACTION_UP,200f,500f));assertEquals(1,boundaries)
  val image=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888);v.bind("\uFFFC\uFFFC","图片章",0,99,ReaderSettings(),images=listOf(ChapterGraphic("a",0,image),ChapterGraphic("b",1,image)),imageId="a",onlyImages=true)
  drag(v,v.width*.65f,v.width*.4f);assertEquals("b",v.currentGraphicId);assertEquals(1,v.currentPage);image.recycle()
 }
 @Test fun verticalScrollParentCanInterceptWithoutANativePageTurn() {
  val scroll=ScrollView(app);val v=reader();scroll.addView(v,android.view.ViewGroup.LayoutParams(540,2400));scroll.measure(View.MeasureSpec.makeMeasureSpec(540,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));scroll.layout(0,0,540,600);v.layout(0,0,540,2400)
  val t=SystemClock.uptimeMillis();for(i in 0..8){val a=when(i){0->MotionEvent.ACTION_DOWN;8->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE};send(scroll,a,270f,450f-i*35f,t+i*20)}
  assertTrue(scroll.scrollY>0);assertEquals(0,v.currentPage)
 }

}
