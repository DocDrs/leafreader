package app.leafreader

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.leafreader.data.IndependentBookSource

@Composable internal fun SourceSettingsRow(title:String,subtitle:String,tag:String,enabled:Boolean=true,open:()->Unit) {
    val color=HomeMuted
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(enabled=enabled,role=Role.Button,onClick=open)
        .testTag(tag).padding(vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            DisplayText(title,fontSize=16.sp,lineHeight=22.sp,maxLines=2,overflow=TextOverflow.Ellipsis)
            if(subtitle.isNotBlank())DisplayText(subtitle,fontSize=12.sp,lineHeight=17.sp,color=HomeMuted,maxLines=2,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=4.dp))
        }
        Canvas(Modifier.size(20.dp).testTag("$tag-arrow")) {
            drawLine(color,Offset(size.width*.35f,size.height*.2f),Offset(size.width*.65f,size.height*.5f),2.dp.toPx(),StrokeCap.Round)
            drawLine(color,Offset(size.width*.65f,size.height*.5f),Offset(size.width*.35f,size.height*.8f),2.dp.toPx(),StrokeCap.Round)
        }
    }
    HorizontalDivider(color=HomeLine)
}

@Composable internal fun SourceManagement(kind:String,providers:List<IndependentBookSource>,dismiss:()->Unit,add:()->Unit,open:(String)->Unit) {
    val opds=kind=="opds"
    SourceDialogScaffold(if(opds)"OPDS 订阅"else"Legado 书源", "source-manager-$kind",dismiss,actions={
                Button(onClick=add,modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag(if(opds)"add-opds-subscription"else"import-legado")) {
                    DisplayText(if(opds)"添加 OPDS 订阅"else"导入 Legado 书源")
                }
    }) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("source-manager-list-$kind")) {
                    if(providers.isEmpty())item{DisplayText(if(opds)"暂无订阅"else"暂无导入书源",color=HomeMuted,fontSize=14.sp,modifier=Modifier.padding(vertical=20.dp))}
                    items(providers,key={it.id}){provider->SourceSettingsRow(provider.label,if(opds)"OPDS 1"else"基础规则","settings-source-${provider.id}"){open(provider.id)}}
                }
    }
}
