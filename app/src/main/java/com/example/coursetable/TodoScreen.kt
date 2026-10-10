package com.example.coursetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 待办页：按课程分组，支持添加 / 完成 / 删除 */
@Composable
fun TodoScreen(repo: CourseRepository, contentPadding: PaddingValues, addRequest: Int = 0, onAddConsumed: () -> Unit = {}) {
    var adding by remember { mutableStateOf<Todo?>(null) }

    var filterCourse by remember { mutableStateOf<String?>(null) }
    // 由底栏「+」菜单触发新增（addRequest 自增一次 = 请求一次），沿用当前课程筛选
    androidx.compose.runtime.LaunchedEffect(addRequest) {
        if (addRequest > 0) { adding = Todo(title = "", courseId = filterCourse ?: ""); onAddConsumed() }   // 消费后归零，避免页面重建时重复触发
    }
    var showDone by remember { mutableStateOf(false) }

    val all = repo.todos.toList()
    val filtered = all.filter { filterCourse == null || it.courseId == filterCourse }
    val pending = filtered.filter { !it.done }.sortedWith(compareBy<Todo, LocalDate?>(nullsLast()) { it.due }.thenBy { it.createdAt })
    val done = filtered.filter { it.done }.sortedByDescending { it.createdAt }
    val courseIdsWithTodos = all.map { it.courseId }.toSet()

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = contentPadding.calculateTopPadding() + 8.dp,
                bottom = contentPadding.calculateBottomPadding() + 96.dp, start = 16.dp, end = 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text("课程待办", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
                Text(
                    "${all.count { !it.done }} 项待完成 · ${all.count { it.done }} 项已完成",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            item {
                androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = filterCourse == null, onClick = { filterCourse = null }, label = { Text("全部") }) }
                    val courses = repo.courses.toList().filter { it.id in courseIdsWithTodos }.distinctBy { it.name }
                    items(courses) { c ->
                        FilterChip(
                            selected = filterCourse == c.id, onClick = { filterCourse = if (filterCourse == c.id) null else c.id },
                            label = { Text(c.name) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(CourseColors.of(c.colorIndex))) }
                        )
                    }
                    if ("" in courseIdsWithTodos) item {
                        FilterChip(selected = filterCourse == "", onClick = { filterCourse = if (filterCourse == "") null else "" }, label = { Text("通用") })
                    }
                }
            }
            if (pending.isEmpty() && done.isEmpty()) {
                item {
                    Card(shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有待办 ✨", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(6.dp))
                            Text("点右下角 + 添加作业、实验报告、考试复习等；也可在课表长按课程 → 添加待办",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(pending, key = { it.id }) { t -> TodoRow(repo, t, onEdit = { adding = t }, modifier = Modifier.animateItem()) }
            if (done.isNotEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().clickable { showDone = !showDone }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("已完成 (${done.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(if (showDone) "收起" else "展开", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.weight(1f))
                        if (showDone) TextButton(onClick = { done.forEach { repo.deleteTodo(it.id) } }) { Text("清空已完成", color = MaterialTheme.colorScheme.error) }
                    }
                }
                if (showDone) items(done, key = { it.id }) { t -> TodoRow(repo, t, onEdit = { adding = t }, modifier = Modifier.animateItem()) }
            }
        }

    }

    adding?.let { t ->
        TodoEditDialog(repo, t, onDismiss = { adding = null })
    }
}

/** 单条待办（在待办页与今日页复用） */
@Composable
fun TodoRow(repo: CourseRepository, t: Todo, onEdit: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val course = repo.courses.firstOrNull { it.id == t.courseId }
    val today = LocalDate.now()
    val dueText = t.due?.let { d ->
        val days = ChronoUnit.DAYS.between(today, d)
        when {
            t.done -> d.format(DateTimeFormatter.ofPattern("M月d日"))
            days < 0 -> "已逾期 ${-days} 天"
            days == 0L -> "今天截止"
            days == 1L -> "明天截止"
            else -> "${d.format(DateTimeFormatter.ofPattern("M月d日"))} · ${days}天后"
        }
    }
    val overdue = !t.done && t.due != null && t.due.isBefore(today)
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = if (t.done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant),
        modifier = modifier.fillMaxWidth().then(if (onEdit != null) Modifier.clickable { onEdit() } else Modifier)
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedCheck(checked = t.done) { repo.toggleTodo(t.id) }
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(
                    t.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    textDecoration = if (t.done) TextDecoration.LineThrough else null,
                    color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (course != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(CourseColors.of(course.colorIndex)))
                        Spacer(Modifier.width(4.dp))
                        Text(course.name, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else Text("通用", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (dueText != null) {
                        Text("  ·  ", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(dueText, fontSize = 12.sp, color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = if (overdue) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                if (t.note.isNotBlank()) Text(t.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            IconButton(onClick = { repo.deleteTodo(t.id) }) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** 新建 / 编辑待办对话框 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoEditDialog(repo: CourseRepository, initial: Todo, onDismiss: () -> Unit) {
    val isNew = repo.todos.none { it.id == initial.id }
    var title by remember { mutableStateOf(initial.title) }
    var note by remember { mutableStateOf(initial.note) }
    var courseId by remember { mutableStateOf(initial.courseId) }
    var due by remember { mutableStateOf(initial.due) }
    var courseMenu by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    val courses = remember { repo.courses.toList().distinctBy { it.name }.sortedWith(compareBy({ it.day }, { it.startPeriod })) }
    val courseName = courses.firstOrNull { it.id == courseId }?.name ?: repo.courses.firstOrNull { it.id == courseId }?.name

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "添加待办" else "编辑待办") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { title = it }, label = { Text("内容（如：第三章作业）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
                )
                Box {
                    OutlinedButton(onClick = { courseMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(courseName?.let { "课程：$it" } ?: "课程：通用（不关联课程）")
                    }
                    DropdownMenu(expanded = courseMenu, onDismissRequest = { courseMenu = false }) {
                        DropdownMenuItem(text = { Text("通用（不关联课程）") }, onClick = { courseId = ""; courseMenu = false })
                        courses.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.name) },
                                leadingIcon = { Box(Modifier.size(12.dp).clip(CircleShape).background(CourseColors.of(c.colorIndex))) },
                                onClick = { courseId = c.id; courseMenu = false }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showDate = true }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.DateRange, contentDescription = null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp))
                        Text(due?.format(DateTimeFormatter.ofPattern("M月d日")) ?: "截止日期（可选）")
                    }
                    if (due != null) TextButton(onClick = { due = null }) { Text("清除") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("今天" to 0L, "明天" to 1L, "下周" to 7L).forEach { (l, d) ->
                        FilterChip(selected = due == LocalDate.now().plusDays(d), onClick = { due = LocalDate.now().plusDays(d) }, label = { Text(l) })
                    }
                    // 下次上这门课
                    val nextClass = courses.firstOrNull { it.id == courseId }?.let { c -> nextClassDate(repo, c) }
                    if (nextClass != null) FilterChip(selected = due == nextClass, onClick = { due = nextClass }, label = { Text("下次课") })
                }
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（可选）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = {
                repo.upsertTodo(initial.copy(title = title.trim(), note = note.trim(), courseId = courseId, due = due))
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = (due ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { due = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("取消") } }
        ) { DatePicker(state = state) }
    }
}

/** 这门课下一次上课的日期（考虑放假 / 调休） */
fun nextClassDate(repo: CourseRepository, c: Course): LocalDate? {
    val today = LocalDate.now()
    for (i in 1..60L) {
        val d = today.plusDays(i)
        if (repo.coursesForDate(d).any { it.id == c.id || (it.name == c.name) }) return d
    }
    return null
}


/** 带弹性动画的勾选框：圆形填充从中心弹出，对勾缩放淡入 */
@Composable
fun AnimatedCheck(checked: Boolean, onToggle: () -> Unit) {
    val p by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = if (checked) androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 520f)
                        else androidx.compose.animation.core.tween(160),
        label = "check"
    )
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onToggle),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
            val r = size.minDimension / 2f
            drawCircle(outline.copy(alpha = 1f - p.coerceIn(0f, 1f)), radius = r - 1.dp.toPx(), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
            if (p > 0f) drawCircle(primary, radius = r * p.coerceAtLeast(0f))
        }
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Check, contentDescription = if (checked) "已完成" else "未完成",
            tint = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.size(15.dp).graphicsLayer { scaleX = p; scaleY = p; alpha = p.coerceIn(0f, 1f) }
        )
    }
}
