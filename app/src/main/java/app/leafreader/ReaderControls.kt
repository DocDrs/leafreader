package app.leafreader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.leafreader.data.ReaderSettings
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReaderControls(s: ReaderSettings, change: (ReaderSettings) -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState())
        .padding(horizontal = 24.dp).padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("读得舒服一点", fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { change(ReaderSettings().copy(palette = s.palette)) }) { Text("恢复版式默认") }
        }
        Text("拖动时预览数值，松手后更新排版并保留阅读位置。", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("字号", modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { change(s.copy(fontSp = (s.fontSp - 1).coerceAtLeast(16f))) }, enabled = s.fontSp > 16) { Text("A−") }
            Text("${s.fontSp.toInt()}", Modifier.padding(horizontal = 16.dp))
            OutlinedButton(onClick = { change(s.copy(fontSp = (s.fontSp + 1).coerceAtMost(32f))) }, enabled = s.fontSp < 32) { Text("A+") }
        }
        PrecisionSlider("行距", s.lineSpacing, 1.2f..2f, .05f, { String.format(Locale.ROOT, "%.2f 倍", it) }) { change(s.copy(lineSpacing = it)) }
        PrecisionSlider("段落间距", s.paragraphSpacingDp, 0f..48f, 1f, { "${it.toInt()} dp" }) { change(s.copy(paragraphSpacingDp = it)) }
        PrecisionSlider("左右边距", s.horizontalMarginDp, 8f..48f, 1f, { "${it.toInt()} dp" }) { change(s.copy(horizontalMarginDp = it)) }
        PrecisionSlider("上下边距", s.verticalMarginDp, 0f..64f, 1f, { "${it.toInt()} dp" }) { change(s.copy(verticalMarginDp = it)) }
        Text("纸张", fontSize = 14.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("暖纸", "素白", "豆绿", "夜读", "仿牛皮纸").forEachIndexed { i, name ->
                FilterChip(selected = s.palette == i, onClick = { change(s.copy(palette = i)) }, label = { Text(name) })
            }
        }
    }
}

@Composable
internal fun PrecisionSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, step: Float,
    format: (Float) -> String, commit: (Float) -> Unit) {
    var draft by remember(value) { mutableFloatStateOf(value) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(format(draft), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Slider(value = draft, onValueChange = {
        draft = (range.start + ((it - range.start) / step).roundToInt() * step).coerceIn(range.start, range.endInclusive)
    }, onValueChangeFinished = { if (draft != value) commit(draft) }, valueRange = range,
        steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label; stateDescription = format(draft) })
}
