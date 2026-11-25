package app.leafreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/** Readable frosted-gradient approximation. No backdrop blur, refraction, or reader-layer effect. */
internal data class GlassColors(val dark:Boolean,val ink:Color,val muted:Color,val accent:Color,val canvas:Color)
@Composable internal fun glassColors():GlassColors {
 val dark=MaterialTheme.colorScheme.surface.luminance()<.35f
 return if(dark)GlassColors(true,Color(0xFFEEF2EB),Color(0xFFB6C1B9),Color(0xFFFFB69E),Color(0xFF17221F))
 else GlassColors(false,Color(0xFF293C35),Color(0xFF69776F),Color(0xFF994B3B),Color(0xFFF5F6F1))
}
internal val HomeInk:Color @Composable get()=glassColors().ink
internal val HomeMuted:Color @Composable get()=glassColors().muted
internal val HomeCanvas:Color @Composable get()=glassColors().canvas
internal val HomeLine:Color @Composable get()=glassColors().ink.copy(alpha=.09f)
internal fun glassBrush(c:GlassColors)=Brush.linearGradient(if(c.dark)listOf(Color(0xFF394942),Color(0xFD283730),Color(0xFC23302B)) else listOf(Color(0xFFFFFFFF),Color(0xFDF7F9F3),Color(0xFCECEFE7)))
internal fun Modifier.softGlass(shape:Shape,c:GlassColors,paintBackground:Boolean=true):Modifier=shadow(18.dp,shape,clip=false,
 ambientColor=Color.Black.copy(alpha=if(c.dark).18f else .07f),spotColor=Color(0xFF284438).copy(alpha=.12f))
 .clip(shape).then(if(paintBackground)Modifier.background(glassBrush(c))else Modifier)
 .border(1.dp,Brush.linearGradient(listOf(Color.White.copy(alpha=if(c.dark).30f else .96f),Color.White.copy(alpha=.08f),Color.White.copy(alpha=if(c.dark).14f else .65f))),shape)

@Composable internal fun HomeBackdrop(modifier:Modifier=Modifier,backdrop:NavBackdrop?=null,content:@Composable BoxScope.()->Unit) {
 val c=glassColors()
 Box(modifier) {
  Box(Modifier.matchParentSize().captureGlassBackdrop(backdrop).background(Brush.verticalGradient(listOf(if(c.dark)c.canvas else Color(0xFFF7F5F0),if(c.dark)Color(0xFF1C2A24)else Color(0xFFF1EEE8),if(c.dark)c.canvas else Color(0xFFF7F5F0))))) {
  Canvas(Modifier.matchParentSize()) {
   drawRect(Brush.radialGradient(listOf((if(c.dark)Color(0xFF668573)else Color(0xFFD2C6B4)).copy(alpha=if(c.dark).15f else .08f),Color.Transparent),center=Offset(size.width*1.05f,size.height*.04f),radius=size.width*1.2f))
   drawRect(Brush.radialGradient(listOf(Color(0xFFC7AB95).copy(alpha=.08f),Color.Transparent),center=Offset(-size.width*.2f,size.height*.75f),radius=size.width*.9f))
  }
  }
  content()
 }
}

@Composable internal fun FloatingHomeNav(selected:String,select:(String)->Unit,backdrop:NavBackdrop?=null,frameSignal:()->Long={0L}) {
 val c=glassColors();val readability=remember{GlassReadability()}
 val systemBottom=WindowInsets.navigationBars.getBottom(androidx.compose.ui.platform.LocalDensity.current)
 CompositionLocalProvider(LocalGlassReadability provides readability) {
  Box(Modifier.fillMaxWidth().softGlass(RectangleShape,c,paintBackground=backdrop==null)
   .refractNav(backdrop,c,frameSignal,cornerDp=0f,clear=true,readability=readability,fluid=true,protectBottomPx=systemBottom)
   .testTag("floating-home-nav")) {
   HomeNavTargets(selected,select,c,Modifier.fillMaxWidth()
    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal+WindowInsetsSides.Bottom))
    .padding(horizontal=8.dp,vertical=5.dp))
  }
 }
}

/** Same stationary targets for runtime glass and static fallback. The material never transforms these. */
@Composable internal fun HomeNavTargets(selected:String,select:(String)->Unit,c:GlassColors,modifier:Modifier) {
 Row(modifier,verticalAlignment=Alignment.CenterVertically) {
  listOf("shelf" to "书架","find" to "找书","settings" to "设置").forEachIndexed { i,(id,label)->
   val active=selected==id
   Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(if(active)c.ink.copy(alpha=if(c.dark).12f else .07f)else Color.Transparent)
    .selectable(active,role=Role.Tab,onClick={select(id)}).heightIn(min=58.dp).padding(vertical=7.dp).testTag("tab-$id"),
    horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
    Column(Modifier.glassReadable(),horizontalAlignment=Alignment.CenterHorizontally) {
     NavGlyph(i,c.ink)
     DisplayText(label,color=c.ink,fontSize=11.sp,lineHeight=16.sp,fontWeight=if(active)FontWeight.Bold else FontWeight.Medium,modifier=Modifier.padding(top=3.dp))
    }
   }
  }
 }
}
@Composable internal fun HomeSearchGlyph()=NavGlyph(1,LocalContentColor.current)

@Composable private fun NavGlyph(index:Int,color:Color) {
 Canvas(Modifier.size(22.dp)) {
  val u=size.width;val s=Stroke(1.65.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round)
  fun line(a:Float,b:Float,c:Float,d:Float)=drawLine(color,Offset(a*u,b*u),Offset(c*u,d*u),s.width,StrokeCap.Round)
  when(index) {
   0->{drawRoundRect(color,Offset(u*.18f,u*.14f),Size(u*.64f,u*.72f),androidx.compose.ui.geometry.CornerRadius(u*.06f),style=s);line(.34f,.15f,.34f,.85f);line(.47f,.34f,.67f,.34f);line(.47f,.48f,.67f,.48f)}
   1->{drawCircle(color,u*.29f,Offset(u*.43f,u*.43f),style=s);line(.65f,.65f,.87f,.87f)}
   else->{drawCircle(color,u*.32f,style=s);drawCircle(color,u*.11f,style=s);for(k in 0..7){val a=k*Math.PI/4;line((.5+.33*kotlin.math.cos(a)).toFloat(),(.5+.33*kotlin.math.sin(a)).toFloat(),(.5+.44*kotlin.math.cos(a)).toFloat(),(.5+.44*kotlin.math.sin(a)).toFloat())}}
  }
 }
}

@Composable internal fun GlassRemoveConfirmation(enabled:Boolean,dismiss:()->Unit,remove:()->Unit) {
 val c=glassColors();val backdrop=LocalGlassBackdrop.current
 Dialog(onDismissRequest=dismiss) {
  Column(Modifier.widthIn(max=360.dp).fillMaxWidth().softGlass(RoundedCornerShape(28.dp),c,paintBackground=backdrop==null)
   .refractNav(backdrop,c,backdrop?.frameSignal?:{0L},cornerDp=28f)
   .testTag("remove-shelf-confirm").padding(horizontal=24.dp).padding(top=24.dp,bottom=14.dp)) {
   DisplayText("移出书架？",color=c.ink,fontSize=22.sp,lineHeight=29.sp,fontWeight=FontWeight.SemiBold)
   Row(Modifier.fillMaxWidth().padding(top=16.dp),horizontalArrangement=Arrangement.End,verticalAlignment=Alignment.CenterVertically) {
    TextButton(onClick=dismiss,colors=ButtonDefaults.textButtonColors(contentColor=c.muted),modifier=Modifier.heightIn(min=48.dp)){DisplayText("取消")}
    Spacer(Modifier.width(8.dp))
    TextButton(onClick=remove,enabled=enabled,colors=ButtonDefaults.textButtonColors(contentColor=c.accent),
     modifier=Modifier.heightIn(min=48.dp).clip(RoundedCornerShape(18.dp)).background(c.accent.copy(alpha=.09f)).testTag("confirm-remove-shelf")){DisplayText("移除",fontWeight=FontWeight.SemiBold)}
   }
  }
 }
}
