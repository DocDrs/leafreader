package app.leafreader

import android.animation.ValueAnimator
import android.app.ActivityManager
import android.content.*
import android.os.Build
import android.os.PowerManager
import android.view.View
import android.view.ViewTreeObserver
import androidx.annotation.RequiresApi
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.ceil

internal object NavRefractionPolicy {
 fun enabled(api:Int,hardware:Boolean,lowRam:Boolean,powerSave:Boolean,animations:Boolean,preference:Boolean)=
  api>=33 && hardware && !lowRam && !powerSave && animations && preference
 fun bounded(width:Int,height:Int)=width>0 && height>0 && width.toLong()*height<=1_200_000L
}

/** Original AGSL lens: one resample of the actual underlay, no blur and no fabricated texture. */
internal object NavLensKernel {
 const val SOURCE="""
 uniform shader backdrop;
 uniform float2 lensSize;
 uniform float guard;
 uniform float radius;
 uniform float strength;
 layout(color) uniform half4 wash;
 float sdf(float2 p) {
   float2 q=abs(p-lensSize*0.5)-(lensSize*0.5-float2(radius));
   return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
 }
 half4 main(float2 xy) {
   float2 p=xy-float2(guard);
   float d=sdf(p);
   float2 normal=float2(sdf(p+float2(1.0,0.0))-sdf(p-float2(1.0,0.0)),
                        sdf(p+float2(0.0,1.0))-sdf(p-float2(0.0,1.0)));
   normal=normal/max(length(normal),0.001);
   float rim=clamp(1.0+d/(radius*0.65),0.0,1.0);
   float2 lens=(p-lensSize*0.5)*0.012*(1.0-rim);
   float2 displaced=xy+normal*strength*rim*rim-lens;
   displaced=clamp(displaced,float2(0.5),lensSize+float2(2.0*guard-0.5));
   half4 sampled=backdrop.eval(displaced);
   // Strong center wash protects labels; the small rim exposes the geometric displacement.
   float opacity=mix(0.90,0.38,rim*rim);
   return mix(sampled,wash,opacity);
 }
 """
}

@RequiresApi(33)
internal class NavLensEffect {
 private val shader=android.graphics.RuntimeShader(NavLensKernel.SOURCE)
 private var key:List<Any>?=null
 private var cached:RenderEffect?=null
 fun configure(w:Int,h:Int,guard:Int,density:Float,dark:Boolean):RenderEffect {
  val next=listOf(w,h,guard,density,dark)
  if(key!=next) {
   shader.setFloatUniform("lensSize",w.toFloat(),h.toFloat());shader.setFloatUniform("guard",guard.toFloat())
   shader.setFloatUniform("radius",minOf(30*density,w/2f,h/2f));shader.setFloatUniform("strength",4.5f*density)
   shader.setColorUniform("wash",if(dark)android.graphics.Color.rgb(40,55,48)else android.graphics.Color.rgb(247,249,243))
   cached=android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"backdrop").asComposeRenderEffect();key=next
  }
  return cached!!
 }
}

@Stable
internal class NavBackdrop(val layer:GraphicsLayer,val effect:NavLensEffect,val failed:()->Unit) {
 var origin by mutableStateOf(Offset.Zero)
 var ready by mutableStateOf(false)
}
internal val NavRenderBackend=SemanticsPropertyKey<String>("NavRenderBackend")

@Composable private fun hardwareRefractionAvailable(preference:Boolean):Boolean {
 val view=LocalView.current;val context=LocalContext.current;val lifecycle=LocalLifecycleOwner.current.lifecycle
 var available by remember(view,preference){mutableStateOf(false)}
 DisposableEffect(view,lifecycle,preference) {
  fun update() {
   available=runCatching {NavRefractionPolicy.enabled(Build.VERSION.SDK_INT,view.isHardwareAccelerated,
    context.getSystemService(ActivityManager::class.java)?.isLowRamDevice?:true,
    context.getSystemService(PowerManager::class.java)?.isPowerSaveMode?:true,
    ValueAnimator.areAnimatorsEnabled(),preference) && !view.isInEditMode}.getOrDefault(false)
  }
  val attach=object:View.OnAttachStateChangeListener {
   override fun onViewAttachedToWindow(v:View){update()}
   override fun onViewDetachedFromWindow(v:View){available=false}
  }
  val preDraw=object:ViewTreeObserver.OnPreDrawListener {
   override fun onPreDraw():Boolean {update();if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(this);return true}
  }
  val resume=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_RESUME)update()}
  val power=object:BroadcastReceiver(){override fun onReceive(c:Context?,i:Intent?){update()}}
  view.addOnAttachStateChangeListener(attach);view.viewTreeObserver.addOnPreDrawListener(preDraw);lifecycle.addObserver(resume)
  ContextCompat.registerReceiver(context,power,IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED),ContextCompat.RECEIVER_NOT_EXPORTED)
  update()
  onDispose {view.removeOnAttachStateChangeListener(attach);if(view.viewTreeObserver.isAlive)view.viewTreeObserver.removeOnPreDrawListener(preDraw);lifecycle.removeObserver(resume);context.unregisterReceiver(power)}
 }
 return available
}

@Composable internal fun RefractionHomeScaffold(selected:String,select:(String)->Unit,preference:Boolean,
 frameSignal:()->Long,snackbar:@Composable ()->Unit,content:@Composable (PaddingValues)->Unit) {
 val allowed=hardwareRefractionAvailable(preference)
 var failed by remember(preference){mutableStateOf(false)}
 val effect=if(allowed && !failed && Build.VERSION.SDK_INT>=33)remember{runCatching{NavLensEffect()}.getOrNull()}else null
 val backdrop=if(effect!=null) {val layer=rememberGraphicsLayer();remember(layer,effect){NavBackdrop(layer,effect){failed=true}}}else null
 Scaffold(containerColor=Color.Transparent,contentWindowInsets=WindowInsets.safeDrawing,snackbarHost=snackbar,
  bottomBar={FloatingHomeNav(selected,select,backdrop,frameSignal)}) {padding->
  HomeBackdrop(Modifier.fillMaxSize().testTag("home-refraction-source")
   .captureNavUnderlay(backdrop?.layer,{if(backdrop!=null)backdrop.origin=it},{if(backdrop!=null && !backdrop.ready)backdrop.ready=true},{backdrop?.failed?.invoke()})) {content(padding)}
 }
}

/** Records only this app's underlay. The navigation and its text are separate siblings. */
internal fun Modifier.captureNavUnderlay(layer:GraphicsLayer?,origin:(Offset)->Unit={},ready:()->Unit={},failure:()->Unit={}):Modifier =
 onGloballyPositioned{origin(it.positionInRoot())}.drawWithContent {
  if(layer==null || !drawContext.canvas.nativeCanvas.isHardwareAccelerated)drawContent()else {
   try {val parent=this;layer.record{parent.drawContent()};drawLayer(layer);ready()}catch(_:RuntimeException){failure();drawContent()}
  }
 }

/** Render only the small navigation crop through the effect; controls are drawn afterwards. */
@Composable internal fun Modifier.refractNav(backdrop:NavBackdrop?,colors:GlassColors,frameSignal:()->Long):Modifier {
 if(backdrop==null)return semantics{this[NavRenderBackend]="static"}
 val crop=rememberGraphicsLayer();var position by remember{mutableStateOf(Offset.Zero)};var runtimeDrawn by remember(backdrop){mutableStateOf(false)}
 return onGloballyPositioned{position=it.positionInRoot()}.semantics{this[NavRenderBackend]=if(runtimeDrawn)"runtime" else "static"}.drawWithContent {
  frameSignal() // Snapshot reads invalidate this small draw node when either list scrolls.
  val guard=ceil(12*density).toInt();val width=size.width.toInt();val height=size.height.toInt()
  var drawn=false
  if(backdrop.ready && !backdrop.layer.isReleased && NavRefractionPolicy.bounded(width+2*guard,height+2*guard) && drawContext.canvas.nativeCanvas.isHardwareAccelerated && Build.VERSION.SDK_INT>=33) {
   try {
    val delta=position-backdrop.origin
    crop.record(size=IntSize(width+2*guard,height+2*guard)) {translate(guard-delta.x,guard-delta.y){drawLayer(backdrop.layer)}}
    crop.renderEffect=backdrop.effect.configure(width,height,guard,density,colors.dark)
    translate(-guard.toFloat(),-guard.toFloat()){drawLayer(crop)};drawn=true
   }catch(_:RuntimeException){backdrop.failed()}
  }
  if(runtimeDrawn!=drawn)runtimeDrawn=drawn
  if(!drawn)drawRect(glassBrush(colors))
  drawContent()
 }
}
