package com.example.coursetable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 高级：脚本 / 规则编辑器。
 * 允许用户直接修改任意规则的课表抓取脚本（script）或空闲教室脚本（freeRoomScript），
 * 保存后作为同 id 的自定义规则覆盖内置规则；也可新建规则。
 */
class ScriptEditorActivity : ComponentActivity() {
    companion object {
        const val EXTRA_FIELD = "field"     // "script" | "freeRoomScript"
        const val EXTRA_RULE_ID = "ruleId"
        const val EXTRA_NEW = "new"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = CourseRepository(applicationContext)
        setContent {
            CourseTableTheme(settings = repo.settings) {
                ScriptEditorScreen(
                    initialField = intent.getStringExtra(EXTRA_FIELD) ?: "script",
                    initialRuleId = intent.getStringExtra(EXTRA_RULE_ID),
                    createNew = intent.getBooleanExtra(EXTRA_NEW, false),
                    onClose = { finish() }
                )
            }
        }
    }
}

private val NEW_RULE_TEMPLATE = ImportRule(
    id = "my_school", name = "我的学校", vendor = "custom", version = 1, author = "",
    match = listOf("jwxt.example.edu.cn"),
    hint = "登录后进入课表页面再点击抓取",
    script = """// 课表抓取脚本：async 函数体，可用 fetch / document / jQuery，同源 Cookie 自动携带
// 需 return { courses: [ { name, teacher, location, day(1-7), startPeriod, endPeriod, weeks:"1-16" 或 [1,2,3], weekType? } ] }
const courses = [];
document.querySelectorAll("table td").forEach(td => {
  // TODO: 解析页面
});
return { courses };
""",
    freeRoomScript = """// 空闲教室脚本（可选）：参数 __p = { date, week, weekday, startPeriod, endPeriod, keyword, xnm, xqm }
// 需 return { rooms: [ { name, building, campus, type, seats } ] }
return { rooms: [] };
"""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(initialField: String, initialRuleId: String?, createNew: Boolean, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var rulesTick by remember { mutableIntStateOf(0) }
    val rules = remember(rulesTick) { RuleEngine.allRules(context) }
    var creating by remember { mutableStateOf(createNew) }
    var ruleId by remember { mutableStateOf(initialRuleId ?: rules.firstOrNull()?.id) }
    val base: ImportRule = remember(ruleId, rulesTick, creating) {
        if (creating) NEW_RULE_TEMPLATE else rules.firstOrNull { it.id == ruleId } ?: rules.firstOrNull() ?: NEW_RULE_TEMPLATE
    }
    var field by remember { mutableIntStateOf(if (initialField == "freeRoomScript") 1 else 0) }

    // 可编辑副本
    var id by remember(base) { mutableStateOf(base.id) }
    var name by remember(base) { mutableStateOf(base.name) }
    var match by remember(base) { mutableStateOf(base.match.joinToString(" ")) }
    var hint by remember(base) { mutableStateOf(base.hint) }
    var script by remember(base) { mutableStateOf(base.script) }
    var freeRoomScript by remember(base) { mutableStateOf(base.freeRoomScript) }
    var freeRoomHint by remember(base) { mutableStateOf(base.freeRoomHint) }
    var showMeta by remember { mutableStateOf(createNew) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }

    val dirty = id != base.id || name != base.name || match != base.match.joinToString(" ") || hint != base.hint ||
        script != base.script || freeRoomScript != base.freeRoomScript || freeRoomHint != base.freeRoomHint || creating

    fun save() {
        if (id.isBlank() || name.isBlank()) { Toast.makeText(context, "id 与名称不能为空", Toast.LENGTH_SHORT).show(); return }
        if (script.isBlank()) { Toast.makeText(context, "课表脚本不能为空", Toast.LENGTH_SHORT).show(); return }
        val rule = ImportRule(
            id = id.trim(), name = name.trim(), vendor = base.vendor, version = base.version + (if (!creating) 1 else 0),
            author = base.author.ifBlank { "本机修改" }, match = match.split(Regex("[\\s,，]+")).filter { it.isNotBlank() },
            hint = hint, script = script, builtin = false, freeRoomScript = freeRoomScript, freeRoomHint = freeRoomHint
        )
        RuleEngine.addCustom(context, rule)
        rulesTick++; creating = false; ruleId = rule.id
        Toast.makeText(context, "已保存为自定义规则「${rule.name}」", Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text("脚本编辑"); Text(if (creating) "新建规则" else base.name + if (base.builtin) "（内置）" else "（自定义）", style = MaterialTheme.typography.bodySmall) } },
                navigationIcon = { IconButton(onClick = { if (dirty) confirmDiscard = true else onClose() }) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    IconButton(onClick = {
                        val text = if (field == 0) script else freeRoomScript
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("script", text))
                        Toast.makeText(context, "已复制脚本", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Share, contentDescription = "复制") }
                    if (!creating && !base.builtin) IconButton(onClick = { confirmRestore = true }) { Icon(Icons.Filled.Delete, contentDescription = "删除自定义") }
                    IconButton(onClick = { save() }, enabled = dirty) { Icon(Icons.Filled.Check, contentDescription = "保存") }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 12.dp)) {
            // 规则选择
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                rules.forEach { r ->
                    FilterChip(selected = !creating && r.id == base.id, onClick = { creating = false; ruleId = r.id }, label = { Text(r.name + if (!r.builtin) " ✎" else "") })
                }
                AssistChip(onClick = { creating = true }, label = { Text("+ 新建") })
            }
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("课表脚本", "空闲教室脚本", "规则信息").forEachIndexed { i, label ->
                    SegmentedButton(selected = if (i == 2) showMeta else (!showMeta && field == i), onClick = { if (i == 2) showMeta = true else { showMeta = false; field = i } },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)) { Text(label, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (showMeta) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(id, { id = it }, Modifier.fillMaxWidth(), label = { Text("id（与内置相同即覆盖）") }, singleLine = true, enabled = creating || !base.builtin)
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("名称") }, singleLine = true)
                    OutlinedTextField(match, { match = it }, Modifier.fillMaxWidth(), label = { Text("匹配网址关键字（空格分隔，空=手动选择）") })
                    OutlinedTextField(hint, { hint = it }, Modifier.fillMaxWidth(), label = { Text("课表抓取提示") })
                    OutlinedTextField(freeRoomHint, { freeRoomHint = it }, Modifier.fillMaxWidth(), label = { Text("空闲教室提示") })
                    Text("vendor: ${base.vendor.ifBlank { "-" }} · 版本 ${base.version} · ${if (base.builtin) "内置规则，保存后生成同 id 自定义规则" else "自定义规则"}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                val value = if (field == 0) script else freeRoomScript
                Text(
                    if (field == 0) "async 函数体 · return { courses:[…] } · ${value.lines().size} 行"
                    else "async 函数体 · 参数 __p · return { rooms:[…] } · ${value.lines().size} 行",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { if (field == 0) script = it else freeRoomScript = it },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                    placeholder = { Text(if (field == 0) "// 在此编写课表抓取脚本" else "// 留空表示该规则不支持空闲教室查询") }
                )
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { if (field == 0) script = base.script else freeRoomScript = base.freeRoomScript }, enabled = value != (if (field == 0) base.script else base.freeRoomScript)) { Text("撤销修改") }
                    if (field == 1 && value.isBlank()) TextButton(onClick = { freeRoomScript = NEW_RULE_TEMPLATE.freeRoomScript }) { Text("插入模板") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { save() }, enabled = dirty) { Text("保存") }
                }
            }
        }
    }

    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false }, title = { Text("放弃修改？") }, text = { Text("有未保存的修改。") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; onClose() }) { Text("放弃") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = false; save() }) { Text("保存并退出") } }
    )
    if (confirmRestore) AlertDialog(
        onDismissRequest = { confirmRestore = false }, title = { Text("删除自定义规则「${base.name}」？") },
        text = { Text("若存在同 id 的内置规则，将恢复为内置版本。") },
        confirmButton = { TextButton(onClick = { RuleEngine.removeCustom(context, base.id); confirmRestore = false; rulesTick++ }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("取消") } }
    )
}
