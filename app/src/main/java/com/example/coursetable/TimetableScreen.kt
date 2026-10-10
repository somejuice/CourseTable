package com.example.coursetable

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TimetableScreen(
    repo: CourseRepository,
    contentPadding: PaddingValues,
    onAddCourse: (day: Int, period: Int) -> Unit,
    onEditCourse: (Course) -> Unit,
    clipboard: Course?,
    onCopy: (Course) -> Unit
) {
    val settings = repo.settings
    // 长按菜单状态
    // 菜单状态带上周次：相邻页会预加载，同一门课在多页出现，不带周次会同时弹出多个菜单
    var courseMenu by remember { mutableStateOf<Pair<Int, String>?>(null) }   // (week, courseId)
    var cellMenu by remember { mutableStateOf<Triple<Int, Int, Int>?>(null) }  // (week, day, period)
    val pasteTo: (day: Int, period: Int) -> Unit = { d, p ->
        clipboard?.let { c ->
            val span = c.endPeriod - c.startPeriod
            repo.upsert(
                c.copy(
                    id = java.util.UUID.randomUUID().toString(),
                    day = d, startPeriod = p,
                    endPeriod = (p + span).coerceAtMost(settings.periodsPerDay)
                )
            )
        }
    }
    val today = LocalDate.now()
    val currentWeek = repo.currentWeek(today)
    var selectedWeek by rememberSaveable { mutableStateOf(currentWeek.coerceIn(1, settings.totalWeeks)) }
    var showWeekPicker by rememberSaveable { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var todoFor by remember { mutableStateOf<Course?>(null) }        // 从课程菜单添加待办
    var adjustDate by remember { mutableStateOf<LocalDate?>(null) }  // 点击日期：设置放假 / 调休
    var editSelfStudy by remember { mutableStateOf<Boolean?>(null) }  // true 早自习 false 晚自习

    val dayCount = if (settings.showWeekend) 7 else 5
    val periods = settings.periodsPerDay

    // 左右滑动切换周次
    val pagerState = rememberPagerState(initialPage = selectedWeek - 1) { settings.totalWeeks }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // 页面状态是唯一数据源：selectedWeek 只跟随 pager，不再反向驱动（避免动画中途被打断停在错误周）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.settledPage }.collect { selectedWeek = it + 1 }
    }
    fun goToWeek(w: Int) {
        val target = (w - 1).coerceIn(0, settings.totalWeeks - 1)
        selectedWeek = target + 1
        scope.launch {
            if (kotlin.math.abs(pagerState.currentPage - target) > 3) pagerState.scrollToPage(target)
            else pagerState.animateScrollToPage(target)
        }
    }

    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        // 顶部：周次切换
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 72.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f).clickable { showWeekPicker = !showWeekPicker }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "第 $selectedWeek 周",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (showWeekPicker) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val subtitle = when {
                    currentWeek == 0 -> "学期尚未开始"
                    currentWeek > settings.totalWeeks -> "学期已结束"
                    selectedWeek == currentWeek -> "本周 · ${today.format(DateTimeFormatter.ofPattern("M月d日"))} ${WeekdayNames[today.dayOfWeek.value - 1]}"
                    else -> "非本周 · 当前第 $currentWeek 周"
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selectedWeek != currentWeek && currentWeek in 1..settings.totalWeeks) {
                AssistChip(onClick = { goToWeek(currentWeek) }, label = { Text("回本周") })
                Spacer(Modifier.width(4.dp))
            }
            IconButton(onClick = { if (selectedWeek > 1) goToWeek(selectedWeek - 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一周")
            }
            IconButton(onClick = { if (selectedWeek < settings.totalWeeks) goToWeek(selectedWeek + 1) }) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一周")
            }
        }

        if (showWeekPicker) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp)
            ) {
                items((1..settings.totalWeeks).toList()) { w ->
                    FilterChip(
                        selected = w == selectedWeek,
                        onClick = { goToWeek(w); showWeekPicker = false },
                        label = { Text(if (w == currentWeek) "${w}·本周" else "$w") }
                    )
                }
            }
        }

        // 表头：星期与日期
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { it }
        ) { page ->
            val week = page + 1
            val courseList = repo.courses
            val adjustList = repo.customAdjusts
            val holidayAuto = repo.settings.holidayAuto
            // 每一列按"具体日期"取课：放假列为空，调休列显示补课日的课程
            val dayAdjusts = remember(week, adjustList.toList(), holidayAuto, dayCount) {
                (1..dayCount).associateWith { d -> repo.dayAdjust(repo.dateOf(week, d)) }
            }
            val weekCourses = remember(week, courseList.toList(), dayCount, dayAdjusts) {
                (1..dayCount).flatMap { d -> repo.coursesForDate(repo.dateOf(week, d)).map { d to it } }
            }
            val todoList = repo.todos
            val todoCounts = remember(todoList.toList()) { todoList.filter { !it.done }.groupingBy { it.courseId }.eachCount() }
            Column(Modifier.fillMaxSize()) {
        val timeColumnWidth = 40.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
            Box(Modifier.width(timeColumnWidth), contentAlignment = Alignment.Center) {
                Text(
                    repo.dateOf(week, 1).monthValue.toString() + "月",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            for (d in 1..dayCount) {
                val date = repo.dateOf(week, d)
                val isToday = date == today
                val adj = dayAdjusts[d]
                Column(
                    Modifier.weight(1f).padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).clickable { adjustDate = date },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (adj != null) {
                        Text(
                            if (adj.holiday) "休" else "班·" + (if (adj.asWeek > 0) "${adj.asWeek}w" else "") + WeekdayNames[adj.asWeekday - 1].removePrefix("周"),
                            fontSize = 9.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold,
                            color = if (adj.holiday) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                                .background(if (adj.holiday) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer)
                                .padding(horizontal = 4.dp, vertical = 1.dp)
                        )
                    } else Spacer(Modifier.height(12.dp))
                    Text(
                        WeekdayNames[d - 1],
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                    )
                    Box(
                        Modifier
                            .padding(top = 2.dp)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isToday) MaterialTheme.colorScheme.primary else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            date.dayOfMonth.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // 课表网格
        val rowHeight = 64.dp
        val scroll = rememberScrollState()
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 4.dp)
        ) {
            val columnWidth = (maxWidth - timeColumnWidth) / dayCount
            val morning = repo.settings.morning
            val evening = repo.settings.evening
            val selfRow = 44.dp
            val topExtra = if (morning.enabled) selfRow else 0.dp
            val bottomExtra = if (evening.enabled) selfRow else 0.dp
            val gridHeight = rowHeight * periods + topExtra + bottomExtra
            Box(Modifier.fillMaxWidth().height(gridHeight + contentPadding.calculateBottomPadding() + 24.dp)) {
                // 节次列 + 网格背景
                Column(Modifier.width(timeColumnWidth)) {
                    if (morning.enabled) SelfStudyLabel("早", morning, Modifier.height(selfRow).fillMaxWidth().clickable { editSelfStudy = true })
                    for (p in 1..periods) {
                        val (start, end) = PeriodTimes.of(p)
                        Column(
                            Modifier.height(rowHeight).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text("$p", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                            Text(start, fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(end, fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (evening.enabled) SelfStudyLabel("晚", evening, Modifier.height(selfRow).fillMaxWidth().clickable { editSelfStudy = false })
                }
                // 早 / 晚自习行
                for (d in 1..dayCount) {
                    val date = repo.dateOf(week, d)
                    if (!repo.selfStudyOn(date)) continue
                    if (morning.enabled) SelfStudyCell(
                        morning,
                        Modifier.offset(x = timeColumnWidth + columnWidth * (d - 1), y = 0.dp).size(columnWidth, selfRow).clickable { editSelfStudy = true }
                    )
                    if (evening.enabled) SelfStudyCell(
                        evening,
                        Modifier.offset(x = timeColumnWidth + columnWidth * (d - 1), y = topExtra + rowHeight * periods).size(columnWidth, selfRow).clickable { editSelfStudy = false }
                    )
                }
                // 空白格：单个 Canvas 绘制 + 单个手势节点（原来每格一个 clickable Box，节点过多导致翻周卡顿）
                val stripeA = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                val stripeB = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.08f)
                androidx.compose.foundation.Canvas(
                    Modifier
                        .offset(x = timeColumnWidth, y = topExtra)
                        .size(columnWidth * dayCount, rowHeight * periods)
                        .pointerInput(dayCount, periods, columnWidth, rowHeight, week) {
                            val cw = columnWidth.toPx(); val rh = rowHeight.toPx()
                            fun cell(o: androidx.compose.ui.geometry.Offset): Pair<Int, Int>? {
                                val dd = (o.x / cw).toInt() + 1; val pp = (o.y / rh).toInt() + 1
                                return if (dd in 1..dayCount && pp in 1..periods) dd to pp else null
                            }
                            detectTapGestures(
                                onTap = { o -> cell(o)?.let { (dd, pp) -> onAddCourse(dd, pp) } },
                                onLongPress = { o -> cell(o)?.let { (dd, pp) -> cellMenu = Triple(week, dd, pp) } }
                            )
                        }
                ) {
                    val cw = columnWidth.toPx(); val rh = rowHeight.toPx(); val pad = 1.dp.toPx(); val r = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
                    for (dd in 0 until dayCount) for (pp in 0 until periods) {
                        drawRoundRect(
                            color = if (pp / 2 % 2 == 0) stripeA else stripeB,
                            topLeft = androidx.compose.ui.geometry.Offset(dd * cw + pad, pp * rh + pad),
                            size = androidx.compose.ui.geometry.Size(cw - 2 * pad, rh - 2 * pad),
                            cornerRadius = r
                        )
                    }
                }
                cellMenu?.let { (w, d, p) ->
                    if (w == week) Box(Modifier.offset(x = timeColumnWidth + columnWidth * (d - 1), y = topExtra + rowHeight * (p - 1)).size(columnWidth, rowHeight)) {
                        DropdownMenu(expanded = true, onDismissRequest = { cellMenu = null }) {
                            DropdownMenuItem(
                                text = { Text(if (clipboard != null) "粘贴「${clipboard.name}」" else "粘贴（剪贴板为空）") },
                                enabled = clipboard != null,
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = { pasteTo(d, p); cellMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("添加课程") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { cellMenu = null; onAddCourse(d, p) }
                            )
                        }
                    }
                }
                // 放假列标注
                for (d in 1..dayCount) {
                    val adj = dayAdjusts[d] ?: continue
                    if (!adj.holiday) continue
                    Box(
                        Modifier
                            .offset(x = timeColumnWidth + columnWidth * (d - 1))
                            .size(columnWidth, gridHeight)
                            .padding(1.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            adj.name.toCharArray().joinToString("\n") + "\n\n放\n假",
                            fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
                // 课程块
                weekCourses.forEach { (colDay, course) ->
                    val span = (course.endPeriod - course.startPeriod + 1).coerceAtLeast(1)
                    val color = CourseColors.of(course.colorIndex)
                    val menuKey = week to "$colDay:${course.id}"
                    val todoN = todoCounts[course.id] ?: 0
                    Box(
                        Modifier
                            .offset(x = timeColumnWidth + columnWidth * (colDay - 1), y = topExtra + rowHeight * (course.startPeriod - 1))
                            .size(columnWidth, rowHeight * span)
                            .padding(1.5.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(color.copy(alpha = 0.92f))
                            .combinedClickable(
                                onClick = { detailCourse = course },
                                onLongClick = { courseMenu = menuKey }
                            )
                            .padding(horizontal = 4.dp, vertical = 5.dp)
                    ) {
                        if (todoN > 0) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-3).dp).size(14.dp).clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) { Text(if (todoN > 9) "9+" else "$todoN", color = color, fontSize = 8.sp, lineHeight = 8.sp, fontWeight = FontWeight.Bold) }
                        }
                        if (courseMenu == menuKey) DropdownMenu(expanded = true, onDismissRequest = { courseMenu = null }) {
                            DropdownMenuItem(
                                text = { Text("添加待办") },
                                leadingIcon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                                onClick = { courseMenu = null; todoFor = course }
                            )
                            DropdownMenuItem(
                                text = { Text("复制") },
                                leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                onClick = { onCopy(course); courseMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text(if (clipboard != null) "粘贴到此处（覆盖位置）" else "粘贴（剪贴板为空）") },
                                enabled = clipboard != null,
                                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                                onClick = { pasteTo(course.day, course.startPeriod); courseMenu = null }
                            )
                            DropdownMenuItem(
                                text = { Text("编辑") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = { courseMenu = null; onEditCourse(course) }
                            )
                            DropdownMenuItem(
                                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = { repo.delete(course.id); courseMenu = null }
                            )
                        }
                        Column {
                            Text(
                                course.name,
                                color = Color.White,
                                fontSize = 11.sp,
                                lineHeight = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = if (span >= 2) 4 else 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (course.location.isNotBlank()) {
                                Text(
                                    "@${course.location}",
                                    color = Color.White.copy(alpha = 0.9f),
                                    fontSize = 9.sp,
                                    lineHeight = 11.sp,
                                    maxLines = if (span >= 2) 3 else 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }
                }
        }
    }

    todoFor?.let { c ->
        TodoEditDialog(repo, Todo(title = "", courseId = c.id), onDismiss = { todoFor = null })
    }

    adjustDate?.let { date -> DayAdjustDialog(repo, date, onDismiss = { adjustDate = null }) }

    editSelfStudy?.let { isMorning ->
        SelfStudyDialog(
            title = if (isMorning) "早自习" else "晚自习",
            initial = if (isMorning) repo.settings.morning else repo.settings.evening,
            onSave = { v -> repo.updateSettings { if (isMorning) it.copy(morning = v) else it.copy(evening = v) } },
            onDismiss = { editSelfStudy = null }
        )
    }

    detailCourse?.let { course ->
        ModalBottomSheet(onDismissRequest = { detailCourse = null }) {
            CourseDetailSheet(
                course = course,
                onEdit = { detailCourse = null; onEditCourse(course) },
                onDelete = { repo.delete(course.id); detailCourse = null }
            )
        }
    }
}

@Composable
fun CourseDetailSheet(course: Course, onEdit: () -> Unit, onDelete: () -> Unit) {
    val color = CourseColors.of(course.colorIndex)
    Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(course.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(16.dp))
        val (s, _) = PeriodTimes.of(course.startPeriod)
        val (_, e) = PeriodTimes.of(course.endPeriod)
        DetailRow(Icons.Filled.DateRange, "${WeekdayNames[course.day - 1]} 第${course.startPeriod}-${course.endPeriod}节 ($s-$e)")
        DetailRow(Icons.Filled.DateRange, course.weekDescription())
        if (course.location.isNotBlank()) DetailRow(Icons.Filled.LocationOn, course.location)
        if (course.teacher.isNotBlank()) DetailRow(Icons.Filled.Person, course.teacher)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(
                onClick = onDelete,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("删除")
            }
            Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Edit, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("编辑")
            }
        }
    }
}

@Composable
private fun DetailRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}


/** 设置某一天：正常上课 / 放假 / 调休（按某天课表上课） */
@Composable
fun DayAdjustDialog(repo: CourseRepository, date: LocalDate, onDismiss: () -> Unit) {
    val current = repo.dayAdjust(date)
    val builtin = HolidayCalendar.builtin[date]
    var mode by remember { mutableStateOf(if (current == null) 0 else if (current.holiday) 1 else 2) }
    var asWeekday by remember { mutableStateOf(current?.asWeekday?.takeIf { it in 1..7 } ?: 1) }
    var asWeek by remember { mutableStateOf(current?.asWeek ?: 0) }   // 0 = 本周
    val totalWeeks = repo.settings.totalWeeks
    val thisWeek = repo.currentWeek(date)
    var name by remember { mutableStateOf(current?.name ?: "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${date.format(DateTimeFormatter.ofPattern("M月d日"))} ${WeekdayNames[date.dayOfWeek.value - 1]}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (builtin != null) Text(
                    "法定安排：${builtin.name}" + if (builtin.holiday) "放假" else "调休上班",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("正常", "放假", "调休").forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = mode == i, onClick = { mode = i },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)
                        ) { Text(l) }
                    }
                }
                if (mode != 0) {
                    androidx.compose.material3.OutlinedTextField(
                        value = name, onValueChange = { name = it }, label = { Text(if (mode == 1) "假期名称（如 校庆）" else "说明（如 国庆调休）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                }
                if (mode == 2) {
                    Text("当天按哪一周的课表上课：", style = MaterialTheme.typography.bodyMedium)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        item { FilterChip(selected = asWeek == 0, onClick = { asWeek = 0 }, label = { Text("本周(第${thisWeek}周)", fontSize = 12.sp) }) }
                        items((1..totalWeeks).toList()) { w ->
                            FilterChip(selected = asWeek == w, onClick = { asWeek = w }, label = { Text("第${w}周", fontSize = 12.sp) })
                        }
                    }
                    Text("按哪一天的课表上课：", style = MaterialTheme.typography.bodyMedium)
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (d in 1..7) FilterChip(
                            selected = asWeekday == d, onClick = { asWeekday = d },
                            label = { Text(WeekdayNames[d - 1].removePrefix("周"), fontSize = 12.sp) }
                        )
                    }
                }
                Text("自定义设置优先于内置法定假期；调休日的补课星期以学校通知为准。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                when (mode) {
                    0 -> if (builtin != null) repo.setCustomAdjust(DayAdjust(date, holiday = false, name = "正常上课", asWeekday = date.dayOfWeek.value))
                         else repo.removeCustomAdjust(date)
                    1 -> repo.setCustomAdjust(DayAdjust(date, holiday = true, name = name.ifBlank { "放假" }))
                    else -> repo.setCustomAdjust(DayAdjust(date, holiday = false, name = name.ifBlank { "调休" }, asWeekday = asWeekday, asWeek = asWeek))
                }
                onDismiss()
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (current?.custom == true) androidx.compose.material3.TextButton(onClick = { repo.removeCustomAdjust(date); onDismiss() }) { Text("恢复默认") }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}


@Composable
private fun SelfStudyLabel(tag: String, ss: SelfStudy, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(tag, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
        Text(ss.start, fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(ss.end, fontSize = 9.sp, lineHeight = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
