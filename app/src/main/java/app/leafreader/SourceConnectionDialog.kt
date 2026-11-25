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
import androidx.compose.ui.platform.testTag
import app.leafreader.data.*

@Composable
fun SourceConnectionDialog(state: ReaderState, ownOrigin: String, dismiss: () -> Unit,
                           connect: (String, String, String) -> Unit, signOut: () -> Unit,
                           buildOrigin: String = BookHubBuildDefaults.origin()) {
    val initial = remember { BookHubBuildDefaults.initial(ownOrigin, buildOrigin) }
    var usingBuildDefault by remember { mutableStateOf(ownOrigin.isBlank() && initial.isNotBlank()) }
    var selected by remember { mutableStateOf(initial) }
    var editing by remember { mutableStateOf(initial.isBlank()) }
    var draft by remember { mutableStateOf(initial) }
    var reviewed by remember { mutableStateOf<String?>(initial.takeIf { usingBuildDefault }) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    fun target(value: String) = runCatching { ServiceOrigin(value) }.getOrNull()?.takeIf { it.uri.scheme == "https" }
    val draftTarget = target(draft)
    val selectedTarget = target(selected)
    val canConnect = selectedTarget != null && reviewed == selectedTarget.value && authorized
    SourceDialogScaffold(if(editing) "编辑书源地址" else "BookHub 登录", "bookhub-source-dialog", {password="";dismiss()}, actions={
                if (editing) Button(onClick = {
                    selected = draft.trim(); usingBuildDefault = false; reviewed = draftTarget?.value; password = ""; authorized = false; editing = false
                }, modifier = Modifier.weight(1f).heightIn(min=48.dp).testTag("confirm-source-target"),
                    enabled = draftTarget != null && !state.connecting) { DisplayText("确认目标，返回登录") }
                else Button(onClick = { connect(selected, username, password); password = "" },
                    modifier = Modifier.weight(1f).heightIn(min=48.dp).testTag("bookhub-login-submit"),
                    enabled = canConnect && username.isNotBlank() && password.isNotEmpty() && !state.connecting) { DisplayText("登录 BookHub") }
    }) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DisplayText(when {
                        state.sourceLabel.contains("重新登录") -> "BookHub · 会话需重新登录"
                        state.remote -> "BookHub · 已连接"
                        else -> "BookHub · 未登录"
                    }, fontSize = 14.sp)
                    state.retentionNote?.let { DisplayText(it, fontSize = 12.sp) }
                    if (editing) {
                        DisplayText("此页显示连接目标，请确认服务归属。首次使用须填写你获准使用的 HTTPS 服务地址。", fontSize = 13.sp)
                        OutlinedTextField(value = draft, onValueChange = {
                            draft = it; password = ""; authorized = false; reviewed = null
                        }, singleLine = true, label = { DisplayText("我的服务地址（HTTPS）") },
                            modifier = Modifier.fillMaxWidth().testTag("source-address-editor"),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !state.connecting)
                        DisplayText("确认登录成功后才切换服务；取消或登录失败将保留当前会话和记录。账号密码只发往核对过的 HTTPS 目标，不跟随跨域跳转。", fontSize = 11.sp)
                        if (selected.isNotBlank()) TextButton(onClick = {
                            editing = false; draft = selected; reviewed = null; password = ""; authorized = false
                        }) { DisplayText("取消编辑，返回登录") }
                    } else {
                        if (usingBuildDefault) {
                            DisplayText("编译默认书源 · 连接目标", fontSize = 14.sp)
                            androidx.compose.material3.Text(selectedTarget?.uri?.let { it.host + if (it.port == -1) "" else ":${it.port}" }.orEmpty(),
                                fontSize = 14.sp, modifier = Modifier.testTag("build-default-destination"))
                            DisplayText("请核对服务归属。账号密码仅发往此 HTTPS 目标；也可编辑地址使用自己的服务。", fontSize = 12.sp)
                        } else DisplayText(if (selected.isBlank()) "尚未配置书源" else "目标书源已配置 · 地址已隐藏", fontSize = 14.sp)
                        TextButton(onClick = {
                            editing = true; draft = selected; reviewed = null; password = ""; authorized = false
                        }, modifier = Modifier.testTag("edit-source-address"), enabled = !state.connecting) { DisplayText("编辑书源地址") }
                        if (reviewed == null) DisplayText("登录前请进入编辑页核对目标；已有会话不受影响。", fontSize = 12.sp)
                    OutlinedTextField(value = username, onValueChange = { username = it }, singleLine = true,
                        label = { DisplayText("服务账号") }, enabled = !state.connecting)
                    OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                        label = { DisplayText("服务密码") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.connecting)
                    Row {
                        Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = reviewed != null && !state.connecting)
                        DisplayText("我已核对目标，并管理此服务或已获书籍读取许可。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    DisplayText("密码不保存。会话令牌由设备 Keystore 加密保存以恢复登录；退出或切换账号时清除。", fontSize = 11.sp)
                    if (state.remote) OutlinedButton(onClick = { password = ""; signOut() }, enabled = !state.connecting) { DisplayText("退出 BookHub，保留本机记录") }
                    if (state.connectionError != null) DisplayText(state.connectionError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    }
                    if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                }
    }
}
