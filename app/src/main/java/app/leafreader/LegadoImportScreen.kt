package app.leafreader

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.leafreader.data.LegadoRules
import kotlinx.coroutines.*

internal fun readLegadoDocument(resolver:ContentResolver,uri:Uri):String {
    val bytes=resolver.openInputStream(uri)?.use{input->val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192);while(true){val n=input.read(buffer);if(n<0)break;if(out.size()+n>LegadoRules.MAX_IMPORT)throw IllegalArgumentException("JSON 超过 1 MB");out.write(buffer,0,n)};out.toByteArray()}?:throw IllegalArgumentException("无法读取所选 JSON")
    return try{Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()}catch(_:java.nio.charset.CharacterCodingException){throw IllegalArgumentException("请选择 UTF-8 JSON 文件")}
}
@Composable internal fun LegadoImportScreen(state:ReaderState,vm:ReaderViewModel,dismiss:()->Unit) {
    var draft by remember{mutableStateOf("")};var rights by remember{mutableStateOf(false)};var fileError by remember{mutableStateOf<String?>(null)};var reading by remember{mutableStateOf(false)}
    val context=LocalContext.current;val coroutine=rememberCoroutineScope()
    val focus=androidx.compose.ui.platform.LocalFocusManager.current;val keyboard=androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)coroutine.launch{reading=true;fileError=null;vm.clearCatalogImportResult();try{draft=withContext(Dispatchers.IO){readLegadoDocument(context.contentResolver,uri)}}catch(e:CancellationException){throw e}catch(e:Exception){fileError=if(e is IllegalArgumentException)e.message else "文件读取失败，请重选"}finally{reading=false}}}
    val close={vm.cancelAddCatalog();dismiss()}
    SourceDialogScaffold("导入 Legado 基础规则","legado-import-dialog",close,actions={
            Button(enabled=(state.catalogImportedCount>0||draft.isNotBlank()&&rights)&&!reading&&!state.addingCatalog,onClick={focus.clearFocus();keyboard?.hide();if(state.catalogImportedCount>0)close()else vm.importLegado(draft,rights,dismiss)},modifier=Modifier.weight(1f).heightIn(min=48.dp).testTag("legado-import-confirm")){DisplayText(if(state.catalogImportedCount>0)"完成"else"导入")}
    }) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).testTag("legado-import-scroll"),verticalArrangement=Arrangement.spacedBy(12.dp)){
                TextButton(onClick={focus.clearFocus();keyboard?.hide();picker.launch(arrayOf("application/json","text/plain","application/octet-stream"))},enabled=!reading&&!state.addingCatalog,modifier=Modifier.testTag("legado-pick-file")){DisplayText("选择本地 JSON")}
                DisplayText("CSS / 简单 JSON 路径 · 不执行脚本或登录",fontSize=12.sp,color=HomeMuted)
                OutlinedTextField(draft,{if(it.toByteArray().size<=LegadoRules.MAX_IMPORT){draft=it;fileError=null;vm.clearCatalogImportResult()}else fileError="JSON 超过 1 MB"},label={DisplayText("或粘贴 JSON")},minLines=3,maxLines=8,enabled=!reading&&!state.addingCatalog,modifier=Modifier.fillMaxWidth().testTag("legado-json"))
                Row(verticalAlignment=Alignment.CenterVertically){Checkbox(rights,{rights=it},modifier=Modifier.testTag("legado-rights"));DisplayText("我有权访问并离线保留来源内容",fontSize=13.sp,modifier=Modifier.weight(1f))}
                (fileError?:state.catalogAddError)?.let{DisplayText(it,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("legado-import-error"))}
                if(state.catalogImportErrors.isNotEmpty()){
                    DisplayText(if(state.catalogImportedCount>0)"已导入 ${state.catalogImportedCount} · 未导入 ${state.catalogImportErrors.size}"else"未导入 ${state.catalogImportErrors.size}",fontSize=13.sp,modifier=Modifier.testTag("legado-import-summary"))
                    state.catalogImportErrors.forEachIndexed{index,error->DisplayText(error.name+" · "+error.reason,fontSize=12.sp,color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("legado-rejection-$index"))}
                }
                if(reading||state.addingCatalog)LinearProgressIndicator(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
    }
}
