package com.example.coursetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// ============ D-Day 倒计时 ============

val DDayPresets = listOf("期末考试", "英语四级", "英语六级", "考研初试", "期中考试", "计算机二级", "毕业答辩")

/** 今日页顶部的倒计时横向卡片 */
@Composable
fun DDayStrip(repo: CourseRepository, onManage: () -> Unit) {
    val today = LocalDate.now()
    val list = repo.ddays.toList().filter { it.daysLeft(today) >= -1 }.sortedWith(compareByDescending<DDay> { it.pinned }.thenBy { it.date })
    if (list.isEmpty()) return
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(list, key = { it.id }) { d ->
            val left = d.daysLeft(today)
            val urgent = left in 0..7
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (urgent) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
                ),
                modifier = Modifier.clickable(onClick = onManage)
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(d.name, style = MaterialTheme.typography.labelMedium,
                        color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            when { left > 0 -> "$left"; left == 0L -> "今天"; else -> "已过" },
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                            color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        if (left > 0) Text(" 天", fontSize = 12.sp, modifier = Modifier.padding(bottom = 3.dp),
                            color = if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(d.date.format(DateTimeFormatter.ofPattern("M月d日")), fontSize = 11.sp,
                        color = (if (urgent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer).copy(alpha = 0.75f))
                }
            }
        }
    }
}

/** 设置页中的 D-Day 列表 */
@Composable
fun DDayList(repo: CourseRepository) {
    var editing by remember { mutableStateOf<DDay?>(null) }
    val today = LocalDate.now()
    val list = repo.ddays.toList().sortedWith(compareByDescending<DDay> { it.pinned }.thenBy { it.date })
    Column {
        if (list.isEmpty()) Text("还没有倒计时，添加期末考、四六级、考研等重要日期",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        list.forEach { d ->
            val left = d.daysLeft(today)
            Row(
                Modifier.fillMaxWidth().clickable { editing = d }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(d.name + if (d.pinned) " 📌" else "", style = MaterialTheme.typography.bodyLarge)
                    Text(d.date.format(DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", java.util.Locale.CHINA)) + if (d.note.isNotBlank()) " · ${d.note}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    when { left > 0 -> "$left 天"; left == 0L -> "今天"; else -> "已过 ${-left} 天" },
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    color = when { left < 0 -> MaterialTheme.colorScheme.onSurfaceVariant; left <= 7 -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.primary }
                )
            }
        }
        Row(Modifier.fillMaxWidth().clickable { editing = DDay(name = "", date = today.plusDays(30)) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text("添加倒计时", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
        }
    }
    editing?.let { d -> DDayEditDialog(repo, d) { editing = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DDayEditDialog(repo: CourseRepository, initial: DDay, onDismiss: () -> Unit) {
    val isNew = repo.ddays.none { it.id == initial.id }
    var name by remember { mutableStateOf(initial.name) }
    var note by remember { mutableStateOf(initial.note) }
    var date by remember { mutableStateOf(initial.date) }
    var pinned by remember { mutableStateOf(initial.pinned) }
    var showDate by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加倒计时" else "编辑倒计时") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DDayPresets.forEach { p -> FilterChip(selected = name == p, onClick = { name = p }, label = { Text(p, fontSize = 12.sp) }) }
                }
                OutlinedButton(onClick = { showDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                    Text(date.format(DateTimeFormatter.ofPattern("yyyy年M月d日")))
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("置顶显示", modifier = Modifier.weight(1f))
                    Switch(checked = pinned, onCheckedChange = { pinned = it })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                repo.upsertDDay(initial.copy(name = name.trim(), note = note.trim(), date = date, pinned = pinned)); onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (!isNew) TextButton(onClick = { repo.deleteDDay(initial.id); onDismiss() }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }
}

// ============ 早 / 晚自习 ============

/** 早晚自习编辑对话框（名称、时间、地点） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SelfStudyDialog(title: String, initial: SelfStudy, onSave: (SelfStudy) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var location by remember { mutableStateOf(initial.location) }
    var start by remember { mutableStateOf(initial.start) }
    var end by remember { mutableStateOf(initial.end) }
    var enabled by remember { mutableStateOf(initial.enabled) }
    var picking by remember { mutableStateOf<Boolean?>(null) } // true 开始 false 结束
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("在课表中显示", modifier = Modifier.weight(1f))
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { picking = true }, modifier = Modifier.weight(1f)) { Text(start) }
                    Text("–")
                    OutlinedButton(onClick = { picking = false }, modifier = Modifier.weight(1f)) { Text(end) }
                }
                OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("地点（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onSave(SelfStudy(enabled, name.ifBlank { initial.name }, start, end, location.trim())); onDismiss() }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
    picking?.let { isStart ->
        val cur = if (isStart) start else end
        val (h, m) = cur.split(":").let { it[0].toInt() to it[1].toInt() }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = null },
            title = { Text(if (isStart) "开始时间" else "结束时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val v = "%02d:%02d".format(state.hour, state.minute)
                    if (isStart) start = v else end = v
                    picking = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("取消") } }
        )
    }
}

/** 课表中的自习行格子 */
@Composable
fun SelfStudyCell(ss: SelfStudy, modifier: Modifier) {
    Box(
        modifier.padding(1.5.dp).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f))
            .padding(horizontal = 4.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ss.name, fontSize = 10.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onTertiaryContainer, maxLines = 1)
            if (ss.location.isNotBlank()) Text("@${ss.location}", fontSize = 8.sp, lineHeight = 10.sp, color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.85f), maxLines = 1)
        }
    }
}
