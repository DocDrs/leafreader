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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.boundsInRoot
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
 fun bounded(width:Int,height:Int)=width>0 && height>0 && width<=8192 && height<=8192 && width.toLong()*height<=3_000_000L
}

/** Original AGSL lens: one resample of the actual underlay, no blur and no fabricated texture. */
internal object NavLensKernel {
 const val SOURCE="""
 uniform shader backdrop;
 uniform float2 lensSize;
 uniform float guard;
 uniform float radius;
 uniform float strength;
 uniform float edgeWidth;
 uniform float2 sampleMin;
 uniform float2 sampleMax;
 uniform float clearMaterial;
 uniform float darkMode;
 uniform float feather;
 uniform float labelCount;
 uniform float4 labelRects[12];
 uniform float flow;
 uniform float flowPhase;
 uniform float flowScale;
 layout(color) uniform half4 wash;
 float sdf(float2 p) {
   float2 q=abs(p-lensSize*0.5)-(lensSize*0.5-float2(radius));
   return length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0)-radius;
 }
 float3 linearRgb(float3 c) { return mix(c/12.92,pow((c+0.055)/1.055,float3(2.4)),step(float3(0.04045),c)); }
 float3 srgb(float3 c) { return mix(c*12.92,1.055*pow(max(c,float3(0.0)),float3(1.0/2.4))-0.055,step(float3(0.0031308),c)); }
 half4 main(float2 xy) {
   float2 p=xy-float2(guard);
   float d=sdf(p);
   float2 normal=float2(sdf(p+float2(1.0,0.0))-sdf(p-float2(1.0,0.0)),
                        sdf(p+float2(0.0,1.0))-sdf(p-float2(0.0,1.0)));
   normal=normal/max(length(normal),0.001);
   float rim=clamp(1.0+d/max(edgeWidth,1.0),0.0,1.0);
   float2 lens=(p-lensSize*0.5)*0.022*(1.0-rim);
   float2 displaced=xy+normal*strength*rim*rim-lens;
   float wave=sin(p.x/max(lensSize.x,1.0)*6.28318+flowPhase);
   float band=exp(-max(p.y,0.0)/max(lensSize.y*0.55,1.0));
   displaced+=flow*flowScale*float2(wave*0.65*band,0.45+0.55*rim);
   displaced=clamp(displaced,sampleMin+float2(0.5),sampleMax-float2(0.5));
   half4 sampled=backdrop.eval(displaced);
   // Strong center wash protects labels; the small rim exposes the geometric displacement.
   float opacity=mix(0.74,0.12,rim*rim);
   if(clearMaterial<0.5)return mix(sampled,wash,opacity);
   // Thin glass over the real scene; only glyph footprints receive an adaptive luminance floor/ceiling.
   float tint=mix(darkMode>0.5?0.18:0.10,0.04,rim*rim);
   float3 rgb=float3(mix(sampled.rgb,wash.rgb,tint));
   float highlight=pow(max(wave,0.0),10.0)*abs(flow)*rim*0.055;
   rgb=mix(rgb,float3(1.0),highlight);
   float coverage=0.0;
   for(int i=0;i<12;i++)if(float(i)<labelCount) {
    float4 r=labelRects[i];float2 q=max(r.xy-p,p-r.zw);
    float distance=length(max(q,float2(0.0)))+min(max(q.x,q.y),0.0);
    coverage=max(coverage,1.0-smoothstep(0.0,max(feather,1.0),distance));
   }
   float3 lin=linearRgb(rgb);float luminance=dot(lin,float3(0.2126,0.7152,0.0722));
   float target=darkMode>0.5?0.07:0.44;
   float amount=darkMode>0.5?(luminance-target)/max(luminance,0.001):(target-luminance)/max(1.0-luminance,0.001);
   float3 guarded=mix(lin,darkMode>0.5?float3(0.0):float3(1.0),clamp(amount,0.0,1.0));
   return half4(srgb(mix(lin,guarded,coverage)),1.0);
 }
 """
}

@RequiresApi(33)
internal class NavLensEffect {
 private val shader=android.graphics.RuntimeShader(NavLensKernel.SOURCE)
 private var key:List<Any>?=null
 private var cached:RenderEffect?=null
 fun configure(w:Int,h:Int,guard:Int,density:Float,dark:Boolean,cornerDp:Float=30f,
  sampleMin:Offset=Offset.Zero,sampleMax:Offset=Offset((w+2*guard).toFloat(),(h+2*guard).toFloat()),
  clear:Boolean=false,labels:List<Rect> = emptyList(),motion:Float=0f,phase:Float=0f):RenderEffect {
  val next=listOf(w,h,guard,density,dark,cornerDp,sampleMin,sampleMax,clear,labels,motion,phase)
  if(key!=next) {
   shader.setFloatUniform("lensSize",w.toFloat(),h.toFloat());shader.setFloatUniform("guard",guard.toFloat())
   shader.setFloatUniform("radius",minOf(cornerDp*density,w/2f,h/2f));shader.setFloatUniform("strength",12f*density)
   shader.setFloatUniform("edgeWidth",minOf(22*density,w/2f,h/2f).coerceAtLeast(1f))
   shader.setFloatUniform("sampleMin",sampleMin.x,sampleMin.y);shader.setFloatUniform("sampleMax",sampleMax.x,sampleMax.y)
   shader.setFloatUniform("clearMaterial",if(clear)1f else 0f);shader.setFloatUniform("darkMode",if(dark)1f else 0f)
   shader.setFloatUniform("feather",4*density);shader.setFloatUniform("labelCount",labels.size.toFloat())
   shader.setFloatUniform("labelRects",glassLabelFloats(labels));shader.setFloatUniform("flow",motion.coerceIn(-1f,1f))
   shader.setFloatUniform("flowPhase",phase);shader.setFloatUniform("flowScale",5*density)
   shader.setColorUniform("wash",if(dark)android.graphics.Color.rgb(40,55,48)else android.graphics.Color.rgb(247,249,243))
   cached=android.graphics.RenderEffect.createRuntimeShaderEffect(shader,"backdrop").asComposeRenderEffect();key=next
  }
  return cached!!
 }
}

@Stable
internal class NavBackdrop(val layer:GraphicsLayer,val failed:()->Unit,var frameSignal:()->Long) {
 var origin by mutableStateOf(Offset.Zero)
 var ready by mutableStateOf(false)
 val parts=mutableStateMapOf<Any,GlassBackdropPart>()
 var flow:GlassFlowState?=null
}
@Stable internal class GlassReadability { val labels=mutableStateMapOf<Any,Rect>() }
internal val LocalGlassReadability=staticCompositionLocalOf<GlassReadability?>{null}
internal fun glassLabelFloats(labels:List<Rect>):FloatArray=FloatArray(48).apply {
 labels.take(12).forEachIndexed{i,r->this[i*4]=r.left;this[i*4+1]=r.top;this[i*4+2]=r.right;this[i*4+3]=r.bottom}
}
@Composable internal fun Modifier.glassReadable():Modifier {
 val registry=LocalGlassReadability.current?:return this;val view=LocalView.current;val key=remember{Any()}
 DisposableEffect(registry,key){onDispose{registry.labels.remove(key)}}
 return onGloballyPositioned{registry.labels[key]=it.boundsInRoot().translate(glassScreenOffset(view))}
}

@Stable internal class GlassBackdropPart(val layer:GraphicsLayer) {
 var screen by mutableStateOf(Offset.Zero)
 var visible by mutableStateOf(Rect.Zero)
 var ready by mutableStateOf(false)
}

/** Register only an actual cover or separator; never the surrounding title, control or overlay. */
@Composable internal fun Modifier.glassPart():Modifier {
 val scene=LocalGlassBackdrop.current?:return this
 val layer=rememberGraphicsLayer();val view=LocalView.current
 val part=remember(scene,layer){GlassBackdropPart(layer)};val key=remember{Any()}
 DisposableEffect(scene,part){scene.parts[key]=part;onDispose{if(scene.parts[key]===part)scene.parts.remove(key)}}
 return onGloballyPositioned{val origin=glassScreenOffset(view);part.screen=origin+it.positionInRoot();part.visible=it.boundsInRoot().translate(origin)}.drawWithContent {
  if(!drawContext.canvas.nativeCanvas.isHardwareAccelerated)drawContent()else try {
   val parent=this;layer.record{parent.drawContent()};drawLayer(layer);if(!part.ready)part.ready=true
  }catch(_:RuntimeException){scene.failed();drawContent()}
 }
}

/** Activity-owned background plus current registered cover/line nodes. Text and overlays are siblings. */
@Composable internal fun Modifier.captureGlassBackdrop(scene:NavBackdrop?):Modifier {
 val view=LocalView.current
 return onGloballyPositioned{if(scene!=null)scene.origin=glassScreenOffset(view)+it.positionInRoot()}.drawWithContent {
  if(scene!=null && drawContext.canvas.nativeCanvas.isHardwareAccelerated)try {
   val parent=this
   scene.layer.record {
    parent.drawContent()
    for(part in scene.parts.values)if(part.ready && !part.layer.isReleased) {
     val delta=part.screen-scene.origin
     val visible=part.visible.translate(-scene.origin)
     if(!visible.isEmpty && visible.left<size.width && visible.top<size.height && visible.right>0 && visible.bottom>0)
      clipRect(visible.left,visible.top,visible.right,visible.bottom){translate(delta.x,delta.y){drawLayer(part.layer)}}
    }
   }
   if(!scene.ready)scene.ready=true
  }catch(_:RuntimeException){scene.failed()}
  drawContent()
 }
}

internal val LocalGlassBackdrop=staticCompositionLocalOf<NavBackdrop?>{null}
internal val GlassReadableRegions=SemanticsPropertyKey<List<Rect>>("GlassReadableRegions")
internal val GlassMotionObservation=SemanticsPropertyKey<GlassMotionSample>("GlassMotionObservation")
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

/** One activity-owned source, shared with sibling windows. Never captures overlays or their scrims. */
@Composable internal fun GlassScene(preference:Boolean,frameSignal:()->Long,motionSample:()->GlassMotionSample={GlassMotionSample("none",false)},content:@Composable ()->Unit) {
 val allowed=hardwareRefractionAvailable(preference)
 var failed by remember(preference){mutableStateOf(false)}
 val capable=if(allowed && !failed && Build.VERSION.SDK_INT>=33)remember{runCatching{NavLensEffect()}.isSuccess}else false
 val layer=rememberGraphicsLayer()
 val backdrop=if(capable)remember(layer){NavBackdrop(layer,{failed=true},frameSignal)}else null
 val flow=rememberGlassFlow(capable,motionSample)
 if(backdrop!=null){backdrop.frameSignal=frameSignal;backdrop.flow=flow}
 CompositionLocalProvider(LocalGlassBackdrop provides backdrop){content()}
}

internal fun glassScreenOffset(view:View):Offset {
 val p=IntArray(2);view.getLocationOnScreen(p);return Offset(p[0].toFloat(),p[1].toFloat())
}
internal data class GlassSampleWindow(val delta:Offset,val min:Offset,val max:Offset)
internal fun glassSampleWindow(surfaceScreen:Offset,sourceScreen:Offset,sourceSize:IntSize,
 width:Int,height:Int,guard:Int):GlassSampleWindow? {
 val delta=surfaceScreen-sourceScreen
 val min=Offset(maxOf(0f,guard-delta.x),maxOf(0f,guard-delta.y))
 val max=Offset(minOf((width+2*guard).toFloat(),guard-delta.x+sourceSize.width),
  minOf((height+2*guard).toFloat(),guard-delta.y+sourceSize.height))
 return if(min.x+1>max.x || min.y+1>max.y)null else GlassSampleWindow(delta,min,max)
}

@Composable internal fun RefractionHomeScaffold(selected:String,select:(String)->Unit,preference:Boolean,
 frameSignal:()->Long,snackbar:@Composable ()->Unit,content:@Composable (PaddingValues)->Unit) {
 val backdrop=LocalGlassBackdrop.current
 Scaffold(containerColor=Color.Transparent,contentWindowInsets=WindowInsets.safeDrawing,snackbarHost=snackbar,
  bottomBar={FloatingHomeNav(selected,select,backdrop,frameSignal)}) {padding->
  HomeBackdrop(Modifier.fillMaxSize().testTag("home-refraction-source"),backdrop){content(padding)}
 }
}

/** Records only this app's underlay. The navigation and its text are separate siblings. */
internal fun Modifier.captureNavUnderlay(layer:GraphicsLayer?,origin:(Offset)->Unit={},ready:()->Unit={},failure:()->Unit={},windowOrigin:()->Offset={Offset.Zero}):Modifier =
 onGloballyPositioned{origin(windowOrigin()+it.positionInRoot())}.drawWithContent {
  if(layer==null || !drawContext.canvas.nativeCanvas.isHardwareAccelerated)drawContent()else {
   try {val parent=this;layer.record{parent.drawContent()};drawLayer(layer);ready()}catch(_:RuntimeException){failure();drawContent()}
  }
 }

/** Render only the small navigation crop through the effect; controls are drawn afterwards. */
@Composable internal fun Modifier.refractNav(backdrop:NavBackdrop?,colors:GlassColors,frameSignal:()->Long,cornerDp:Float=30f,clear:Boolean=false,readability:GlassReadability?=null,fluid:Boolean=false,protectBottomPx:Int=0):Modifier {
 if(backdrop==null)return semantics{this[NavRenderBackend]="static";this[GlassReadableRegions]=readability?.labels?.values?.toList().orEmpty()}
 val view=LocalView.current
 val effect=remember(backdrop){if(Build.VERSION.SDK_INT>=33)runCatching{NavLensEffect()}.getOrNull()else null}
 val crop=rememberGraphicsLayer();var position by remember{mutableStateOf(Offset.Zero)};var runtimeDrawn by remember(backdrop){mutableStateOf(false)}
 return onGloballyPositioned{position=glassScreenOffset(view)+it.positionInRoot()}.semantics{this[NavRenderBackend]=if(runtimeDrawn)"runtime" else "static";this[GlassReadableRegions]=readability?.labels?.values?.toList().orEmpty()}.drawWithContent {
  frameSignal()
  val guard=ceil(24*density).toInt();val width=size.width.toInt();val height=size.height.toInt()
  var drawn=false
  if(effect!=null && backdrop.ready && !backdrop.layer.isReleased && NavRefractionPolicy.bounded(width+2*guard,height+2*guard) && drawContext.canvas.nativeCanvas.isHardwareAccelerated && Build.VERSION.SDK_INT>=33) {
   val sample=glassSampleWindow(position,backdrop.origin,backdrop.layer.size,width,height,guard)
   val glyphs=readability?.labels?.values?.map{it.translate(-position).inflate(4*density)}?.filter{!it.isEmpty && it.right>0 && it.bottom>0 && it.left<width && it.top<height}.orEmpty()
   val labels=if(protectBottomPx>0)glyphs+Rect(0f,maxOf(0,height-protectBottomPx).toFloat(),width.toFloat(),height.toFloat())else glyphs
   val clearReady=clear && glyphs.isNotEmpty() && labels.size<=12
   val motion=if(fluid)backdrop.flow?.value?:0f else 0f;val phase=if(fluid)backdrop.flow?.phase?:0f else 0f
   if(sample!=null)try {
    crop.record(size=IntSize(width+2*guard,height+2*guard)) {translate(guard-sample.delta.x,guard-sample.delta.y){drawLayer(backdrop.layer)}}
    crop.renderEffect=effect.configure(width,height,guard,density,colors.dark,cornerDp,sample.min,sample.max,clearReady,labels.take(12),motion,phase)
    translate(-guard.toFloat(),-guard.toFloat()){drawLayer(crop)};drawn=true
   }catch(_:RuntimeException){backdrop.failed()}
  }
  if(runtimeDrawn!=drawn)runtimeDrawn=drawn
  if(!drawn)drawRect(glassBrush(colors))
  drawContent()
 }
}
