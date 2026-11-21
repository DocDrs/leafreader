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
                           targetChanged: () -> Unit = {},
                           builtIn: ConfirmedBookHubEntry? = BuiltInBookHub.confirmedEntry) {
    val initial = remember { InitialBookHubTarget.migrate(ownOrigin, builtIn) }
    var customOrigin by remember { mutableStateOf(initial.customOrigin) }
    var useCustom by remember { mutableStateOf(initial.useCustom || builtIn == null) }
    var advanced by remember { mutableStateOf(initial.useCustom || builtIn == null) }
    // Credentials are intentionally not rememberSaveable and never stored by the app.
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var authorized by remember { mutableStateOf(false) }
    val selected = if (useCustom) customOrigin else builtIn?.origin?.value.orEmpty()
    val parsed = runCatching { ServiceOrigin(selected) }.getOrNull()
    val domain = parsed?.uri?.let { it.host + if (it.port == -1) "" else ":${it.port}" }
    val canConnect = parsed != null && parsed.uri.scheme == "https" && (!useCustom || authorized)
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color(0xFFF7F3EB)) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp)) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("BookHub 登录", fontSize = 22.sp, modifier = Modifier.weight(1f))
                    TextButton(onClick = { password = ""; dismiss() }) { Text("关闭") }
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("当前：${state.sourceLabel}", fontSize = 14.sp)
                    if (domain != null) {
                        Text(if (useCustom) "目标：已配置服务" else "目标：${builtIn!!.provider}", fontSize = 15.sp)
                        Text(domain, fontSize = 13.sp)
                        Text("账号密码只发往此 HTTPS 服务器，不跟随跨域跳转。", fontSize = 11.sp)
                    } else {
                        Text("配置 BookHub 服务", fontSize = 15.sp)
                        Text("请输入你获准使用的 HTTPS 服务地址，再填写该服务的账号和密码。", fontSize = 12.sp)
                    }
                    OutlinedTextField(value = username, onValueChange = { username = it }, singleLine = true,
                        label = { Text("服务账号") }, enabled = !state.connecting)
                    OutlinedTextField(value = password, onValueChange = { password = it }, singleLine = true,
                        label = { Text("服务密码") }, visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), enabled = !state.connecting)
                    TextButton(onClick = { advanced = !advanced }, enabled = !state.connecting) {
                        Text(if (advanced) "收起高级设置" else "高级设置")
                    }
                    if (advanced) {
                        Text("更换服务器会清除当前会话；各服务器的本机阅读进度分别保留。", fontSize = 11.sp)
                        OutlinedTextField(value = customOrigin, onValueChange = {
                            customOrigin = it; useCustom = true; password = ""; authorized = false; targetChanged()
                        }, singleLine = true, label = { Text("我的服务地址（HTTPS）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), enabled = !state.connecting)
                        if (builtIn != null && useCustom) TextButton(onClick = {
                            useCustom = false; password = ""; authorized = false; targetChanged()
                        }, enabled = !state.connecting) { Text("使用内置 BookHub") }
                    }
                    if (useCustom) Row {
                        Checkbox(checked = authorized, onCheckedChange = { authorized = it }, enabled = !state.connecting)
                        Text("我管理此服务或已获许可，允许栖页读取其中的书籍。", fontSize = 12.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                    Text("密码不保存。会话令牌由设备 Keystore 加密保存以恢复登录；退出或切换账号时清除。", fontSize = 11.sp)
                    if (state.remote) OutlinedButton(onClick = { password = ""; signOut() }, enabled = !state.connecting) { Text("退出 BookHub，保留本机记录") }
                    if (state.connectionError != null) Text(state.connectionError, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    if (state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(20.dp))
                }
                Button(onClick = { connect(selected, username, password); password = "" },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp).height(50.dp).testTag("bookhub-login-submit"),
                    enabled = canConnect && username.isNotBlank() && password.isNotEmpty() && !state.connecting) { Text("登录 BookHub") }
            }
        }
    }
}
