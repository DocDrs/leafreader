package app.leafreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable internal fun OpdsSubscriptionDialog(state:ReaderState,vm:ReaderViewModel,dismiss:()->Unit) {
    var name by remember{mutableStateOf("")};var url by remember{mutableStateOf("")};var rights by remember{mutableStateOf(false)}
    val close={vm.cancelAddCatalog();dismiss()}
    SourceDialogScaffold("添加 OPDS 订阅","opds-add-dialog",close,actions={
                Button(enabled=name.isNotBlank()&&url.isNotBlank()&&rights&&!state.addingCatalog,onClick={vm.addOpds(name,url,rights,dismiss)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("opds-add-confirm")){DisplayText("添加")}
    }) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(name,{name=it},label={DisplayText("名称")},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("opds-name"))
                    OutlinedTextField(url,{url=it},label={DisplayText("HTTPS 目录地址")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth().testTag("opds-url"))
                    TextButton(onClick={name="Wolne Lektury";url="https://wolnelektury.pl/opds/"},enabled=!state.addingCatalog,modifier=Modifier.testTag("opds-public-example")){DisplayText("填入已核实公开示例")}
                    Row(verticalAlignment=Alignment.CenterVertically) {Checkbox(checked=rights,onCheckedChange={rights=it},modifier=Modifier.testTag("opds-rights"));DisplayText("我有权下载并离线保留此源内容",fontSize=13.sp,modifier=Modifier.weight(1f))}
                    DisplayText("OPDS 1 · 公开或自有免登录目录",color=HomeMuted,fontSize=12.sp)
                    state.catalogAddError?.let{DisplayText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("opds-add-error"))}
                    if(state.addingCatalog)LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(16.dp))
                }
    }
}
