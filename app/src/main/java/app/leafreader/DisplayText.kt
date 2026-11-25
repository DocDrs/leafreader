package app.leafreader

import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import app.leafreader.text.ChineseDisplay

/** Final rendering boundary only. Inputs, source metadata, keys and request parameters stay original. */
@Composable internal fun DisplayText(text:String,modifier:Modifier=Modifier,color:Color=Color.Unspecified,
    fontSize:TextUnit=TextUnit.Unspecified,fontStyle:FontStyle?=null,fontWeight:FontWeight?=null,
    fontFamily:FontFamily?=null,lineHeight:TextUnit=TextUnit.Unspecified,textAlign:TextAlign?=null,
    maxLines:Int=Int.MAX_VALUE,overflow:TextOverflow=TextOverflow.Clip,style:TextStyle=LocalTextStyle.current) {
    val context=LocalContext.current.applicationContext
    val display=remember(text,context){ChineseDisplay.text(context,text)}
    androidx.compose.material3.Text(display,modifier=modifier,color=color,fontSize=fontSize,fontStyle=fontStyle,
        fontWeight=fontWeight,fontFamily=fontFamily,lineHeight=lineHeight,textAlign=textAlign,maxLines=maxLines,overflow=overflow,style=style)
}
