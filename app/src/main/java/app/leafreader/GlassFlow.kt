package app.leafreader

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.exp

internal data class GlassMotionSample(val owner:String,val moving:Boolean,val items:Map<Int,Int> = emptyMap(),val offset:Int?=null)
internal fun glassMotionSample(owner:String,list:LazyListState)=GlassMotionSample(owner,list.isScrollInProgress,list.layoutInfo.visibleItemsInfo.associate{it.index to it.offset})
internal fun glassMotionSample(owner:String,scroll:ScrollState)=GlassMotionSample(owner,scroll.isScrollInProgress,offset=scroll.value)

/** Common visible anchors avoid inventing velocity from variable row heights or index jumps. */
internal class GlassMotionTracker {
 private var previous:GlassMotionSample?=null
 fun delta(sample:GlassMotionSample):Float {
  val old=previous;previous=sample
  if(old==null || old.owner!=sample.owner || !sample.moving)return 0f
  if(old.offset!=null && sample.offset!=null)return (sample.offset-old.offset).toFloat()
  val common=sample.items.keys.firstOrNull{it in old.items}?:return 0f
  return (old.items.getValue(common)-sample.items.getValue(common)).toFloat()
 }
}

/** Driven only by observed scroll. Finite decay; no clock-only ripples when idle. */
internal class GlassFlowDynamics {
 var value=0f;private set
 var phase=0f;private set
 private var target=0f
 fun impulse(deltaDp:Float,seconds:Float) {
  if(!deltaDp.isFinite() || !seconds.isFinite() || seconds<=0)return
  target=(deltaDp/seconds/1400f).coerceIn(-1f,1f)
  phase=((phase+deltaDp*.035f)%(2*Math.PI).toFloat())
 }
 fun stop(){target=0f}
 fun reset(){target=0f;value=0f;phase=0f}
 fun step(seconds:Float,quiet:Float):Boolean {
  if(quiet>.08f)target=0f
  val dt=seconds.coerceIn(0f,.05f);val rate=if(target==0f)12f else 25f
  value+=(target-value)*(1f-exp(-rate*dt))
  if(abs(value)<.001f && target==0f)value=0f
  return value!=0f || target!=0f
 }
}
@Stable internal class GlassFlowState {var value by mutableFloatStateOf(0f);var phase by mutableFloatStateOf(0f)}

@Composable internal fun rememberGlassFlow(enabled:Boolean,sample:()->GlassMotionSample):GlassFlowState {
 val result=remember{GlassFlowState()};val current by rememberUpdatedState(sample)
 val density=androidx.compose.ui.platform.LocalDensity.current.density
 val lifecycle=LocalLifecycleOwner.current.lifecycle
 LaunchedEffect(enabled,density,lifecycle) {
  result.value=0f;result.phase=0f
  if(!enabled)return@LaunchedEffect
  lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
  try {coroutineScope {
   val tracker=GlassMotionTracker();val dynamics=GlassFlowDynamics();var job:Job?=null
   var lastEvent=SystemClock.uptimeMillis();var lastSample=lastEvent;var owner=""
   snapshotFlow{current()}.collect{input->
    val now=SystemClock.uptimeMillis();val elapsed=((now-lastSample)/1000f).coerceAtLeast(1f/120f);lastSample=now
    if(owner!=input.owner){owner=input.owner;dynamics.reset();result.value=0f;result.phase=0f}
    val delta=tracker.delta(input)
    if(!input.moving)dynamics.stop()
    else if(delta!=0f){dynamics.impulse(delta/density,elapsed);lastEvent=now}
    result.phase=dynamics.phase
    if((delta!=0f || result.value!=0f) && job?.isActive!=true)job=launch {
     var previous=0L
     do {
      val frame=withFrameNanos{it};val dt=if(previous==0L)1f/60f else (frame-previous)/1_000_000_000f;previous=frame
      val active=dynamics.step(dt,(SystemClock.uptimeMillis()-lastEvent)/1000f)
      result.value=dynamics.value;result.phase=dynamics.phase
     }while(active)
    }
   }
  }}finally{result.value=0f;result.phase=0f}
  }
 }
 return result
}
