package com.example.coursetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseEditorScreen(
    initial: Course,
    isNew: Boolean,
    totalWeeks: Int,
    periodsPerDay: Int,
    templates: List<Course> = emptyList(),
    onSave: (Course) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    var name by remember { mutableStateOf(initial.name) }
    var teacher by remember { mutableStateOf(initial.teacher) }
    var location by remember { mutableStateOf(initial.location) }
    var day by remember { mutableIntStateOf(initial.day) }
    var startPeriod by remember { mutableIntStateOf(initial.startPeriod) }
    var endPeriod by remember { mutableIntStateOf(initial.endPeriod) }
    var startWeek by remember { mutableIntStateOf(initial.startWeek) }
    var endWeek by remember { mutableIntStateOf(initial.endWeek.coerceAtMost(totalWeeks)) }
    var weekType by remember { mutableIntStateOf(initial.weekType) }
    var colorIndex by remember { mutableIntStateOf(initial.colorIndex) }
    var nameError by remember { mutableStateOf(false) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(if (isNew) "添加课程" else "编辑课程") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
                },
                actions = {
                    if (!isNew) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = {
                        if (name.isBlank()) { nameError = true; return@IconButton }
                        onSave(
                            initial.copy(
                                name = name.trim(), teacher = teacher.trim(), location = location.trim(),
                                day = day, startPeriod = minOf(startPeriod, endPeriod), endPeriod = maxOf(startPeriod, endPeriod),
                                startWeek = minOf(startWeek, endWeek), endWeek = maxOf(startWeek, endWeek),
                                weekType = weekType, colorIndex = colorIndex
                            )
                        )
                    }) { Icon(Icons.Filled.Check, contentDescription = "保存") }
                }
            )

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it; nameError = false },
                    label = { Text("课程名称 *") }, isError = nameError,
                    supportingText = if (nameError) ({ Text("请输入课程名称") }) else null,
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = location, onValueChange = { location = it },
                    label = { Text("上课地点") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = teacher, onValueChange = { teacher = it },
                    label = { Text("授课教师") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )

                if (templates.isNotEmpty()) {
                    SectionTitle("从已录入课程一键填充")
                    Text(
                        "点击下方课程，自动填入名称、地点、教师、周次与颜色（星期与节次保持当前选择）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(templates, key = { it.id }) { t ->
                            val c = CourseColors.of(t.colorIndex)
                            Card(
                                onClick = {
                                    name = t.name; teacher = t.teacher; location = t.location
                                    colorIndex = t.colorIndex
                                    startWeek = t.startWeek.coerceIn(1, totalWeeks)
                                    endWeek = t.endWeek.coerceIn(1, totalWeeks)
                                    weekType = t.weekType
                                    nameError = false
                                },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            ) {
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(t.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        val sub = listOf(t.location, t.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                                        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    }
                }

                SectionTitle("星期")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((1..7).toList()) { d ->
                        FilterChip(selected = day == d, onClick = { day = d }, label = { Text(WeekdayNames[d - 1]) })
                    }
                }

                SectionTitle("节次  第 ${minOf(startPeriod, endPeriod)} - ${maxOf(startPeriod, endPeriod)} 节")
                LabeledSlider("开始", startPeriod, 1, periodsPerDay) { startPeriod = it; if (endPeriod < it) endPeriod = it }
                LabeledSlider("结束", endPeriod, 1, periodsPerDay) { endPeriod = it; if (startPeriod > it) startPeriod = it }

                SectionTitle("周次  第 ${minOf(startWeek, endWeek)} - ${maxOf(startWeek, endWeek)} 周")
                LabeledSlider("开始", startWeek, 1, totalWeeks) { startWeek = it; if (endWeek < it) endWeek = it }
                LabeledSlider("结束", endWeek, 1, totalWeeks) { endWeek = it; if (startWeek > it) startWeek = it }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("每周", "单周", "双周").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = weekType == i,
                            onClick = { weekType = i },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)
                        ) { Text(label) }
                    }
                }

                SectionTitle("颜色")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (i in 0 until CourseColors.size) {
                        val c = CourseColors.of(i)
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(c)
                                .border(
                                    width = if (colorIndex == i) 3.dp else 0.dp,
                                    color = if (colorIndex == i) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { colorIndex = i },
                            contentAlignment = Alignment.Center
                        ) {
                            if (colorIndex == i) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (name.isBlank()) { nameError = true; return@Button }
                        onSave(
                            initial.copy(
                                name = name.trim(), teacher = teacher.trim(), location = location.trim(),
                                day = day, startPeriod = minOf(startPeriod, endPeriod), endPeriod = maxOf(startPeriod, endPeriod),
                                startWeek = minOf(startWeek, endWeek), endWeek = maxOf(startWeek, endWeek),
                                weekType = weekType, colorIndex = colorIndex
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("保存") }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun LabeledSlider(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(36.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt().coerceIn(min, max)) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = (max - min - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f)
        )
        Text("$value", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
    }
}

@Suppress("unused")
private val EmptyPadding = PaddingValues(0.dp)
