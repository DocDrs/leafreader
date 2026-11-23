package app.leafreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.testTag
import app.leafreader.data.*

@Composable
fun SourceConnectionDialog(state: ReaderState, ownOrigin: String, dismiss: () -> Unit,
                           connect: (String, String, String) -> Unit, signOut: () -> Unit,
                           ) {
    var selected by remember { mutableStateOf(ownOrigin) }
    var editing by remember { mutableStateOf(ownOrigin.isBlank()) }
    var draft by remember { mutableStateOf(ownOrigin) }
    var reviewed by remember { mutableStateOf<String?>(null) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    fun target(value: String) = runCatching { ServiceOrigin(value) }.getOrNull()?.takeIf { it.uri.scheme == "https" }
    val draftTarget = target(draft)
    val selectedTarget = target(selected)
    val canConnect = selectedTarget != null && reviewed == selectedTarget.value && authorized
    Dialog(onDismissRequest = { password = ""; dismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F3EB)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (editing) "编辑书源地址" else "BookHub 登录", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { password = ""; dismiss() }) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(when {
                        state.sourceLabel.contains("重新登录") -> "BookHub · 会话需重新登录"
                        state.remote -> "BookHub · 已连接"
                        else -> "BookHub · 未登录"
                    }, fontSize = 14.sp)
                    state.retentionNote?.let { Text(it, fontSize = 12.sp) }
                    if (editing) {
                        Text("此页显示连接目标，请确认服务归属。首次使用须填写你获准使用的 HTTPS 服务地址。", fontSize = 13.sp)
                        OutlinedTextField(value = draft, onValueChange = {
                            draft = it; password = ""; authorized = false; reviewed = null
                        }, singleLine = true, label = { Text("我的服务地址（HTTPS）") },
                            modifier = Modifier.fillMaxWidth().testTag("source-address-editor"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !state.connecting)
                        Text("确认登录成功后才切换服务；取消或登录失败将保留当前会话和记录。账号密码只发往核对过的 HTTPS 目标，不跟随跨域跳转。", fontSize = 11.sp)
                        if (selected.isNotBlank()) TextButton(onClick = {
                            editing = false; draft = selected; reviewed = null; password = ""; authorized = false
                        }) { Text("取消编辑，返回登录") }
                    } else {
                        Text(if (selected.isBlank()) "尚未配置书源" else "目标书源已配置 · 地址已隐藏", fontSize = 14.sp)
                        TextButton(onClick = {
                            editing = true; draft = selected; reviewed = null; password = ""; authorized = false
                        }, modifier = Modifier.testTag("edit-source-address"), enabled = !state.connecting) { Text("编辑书源地址") }
                        if (reviewed == null) Text("登录前请进入编辑页核对目标；已有会话不受影响。", fontSize = 12.sp)
                    OutlinedTextField(value = username, onValueChange = { username = it }, singleLine = true,
                        label = { Text("服务账号") }, enabled = !state.connecting)
                    OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("服务密码") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.connecting)
                    Row {
                        Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = reviewed != null && !state.connecting)
                        Text("我已核对目标，并管理此服务或已获书籍读取许可。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("密码不保存。会话令牌由设备 Keystore 加密保存以恢复登录；退出或切换账号时清除。", fontSize = 11.sp)
                    if (state.remote) OutlinedButton(onClick = { password = ""; signOut() }, enabled = !state.connecting) { Text("退出 BookHub，保留本机记录") }
                    if (state.connectionError != null) Text(state.connectionError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                }
                if (editing) Button(onClick = {
                    selected = draft.trim(); reviewed = draftTarget?.value; password = ""; authorized = false; editing = false
                }, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(50.dp).testTag("confirm-source-target"),
                    enabled = draftTarget != null && !state.connecting) { Text("确认目标，返回登录") }
                else Button(onClick = { connect(selected, username, password); password = "" },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(50.dp).testTag("bookhub-login-submit"),
                    enabled = canConnect && username.isNotBlank() && password.isNotEmpty() && !state.connecting) { Text("登录 BookHub") }
            }
        }
    }
}
