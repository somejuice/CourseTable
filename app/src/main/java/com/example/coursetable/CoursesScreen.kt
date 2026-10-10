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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.LocationOn
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun CoursesScreen(
    repo: CourseRepository,
    contentPadding: PaddingValues,
    onOpenDDay: () -> Unit = {},
    onEditCourse: (Course) -> Unit
) {
    val today = LocalDate.now()
    val now = LocalTime.now()
    val week = repo.currentWeek(today)
    val settings = repo.settings
    val inSemester = week in 1..settings.totalWeeks
    val todayCourses = repo.coursesForDate(today)
    val todayAdjust = repo.dayAdjust(today)
    val tomorrow = today.plusDays(1)
    val tomorrowWeek = repo.currentWeek(tomorrow)
    val tomorrowCourses = repo.coursesForDate(tomorrow)
    val tomorrowAdjust = repo.dayAdjust(tomorrow)

    val nextCourse = todayCourses.firstOrNull { c ->
        val end = LocalTime.parse(PeriodTimes.of(c.endPeriod).second)
        now.isBefore(end)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = contentPadding.calculateTopPadding() + 8.dp,
            bottom = contentPadding.calculateBottomPadding() + 24.dp,
            start = 16.dp, end = 16.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                "今日课程",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 4.dp)
            )
            Text(
                "${today.format(DateTimeFormatter.ofPattern("M月d日"))} ${WeekdayNames[today.dayOfWeek.value - 1]}" +
                        (if (inSemester) " · 第 $week 周" else " · 假期中") +
                        (todayAdjust?.let { " · " + it.describe() } ?: ""),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (repo.ddays.isNotEmpty()) item { DDayStrip(repo, onManage = onOpenDDay) }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val loggedIn = JwSession.tick.intValue.let { JwSession.isLoggedIn(ctx) && JwSession.rule(ctx)?.supportsFreeRoom == true }
            if (loggedIn) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.material3.AssistChip(
                    onClick = { ctx.startActivity(android.content.Intent(ctx, FreeRoomsActivity::class.java)) },
                    label = { Text("空闲教室查询") },
                    leadingIcon = { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.LocationOn, contentDescription = null, Modifier.size(16.dp)) }
                )
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(18.dp)) {
                    if (nextCourse != null) {
                        val ongoing = now >= LocalTime.parse(PeriodTimes.of(nextCourse.startPeriod).first)
                        Text(
                            if (ongoing) "正在上课" else "下一节课",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(nextCourse.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${PeriodTimes.of(nextCourse.startPeriod).first} - ${PeriodTimes.of(nextCourse.endPeriod).second}" +
                                    (if (nextCourse.location.isNotBlank()) " · ${nextCourse.location}" else ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            when {
                                todayAdjust?.holiday == true -> "${todayAdjust.name}放假，好好休息 🎉"
                                todayCourses.isEmpty() -> "今天没有课，好好休息 🎉"
                                else -> "今天的课都上完啦 ✨"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            "共 ${repo.courses.size} 门课程 · 本学期 ${settings.totalWeeks} 周",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                        )
                    }
                }
            }
        }

        val selfToday = repo.selfStudyOn(today)
        if (selfToday && settings.morning.enabled) item { SelfStudyRow(settings.morning, dimmed = now.isAfter(LocalTime.parse(settings.morning.end))) }
        if (todayCourses.isNotEmpty()) {
            items(todayCourses, key = { "t" + it.id }) { c ->
                val finished = now.isAfter(LocalTime.parse(PeriodTimes.of(c.endPeriod).second))
                CourseRow(c, dimmed = finished, highlight = c.id == nextCourse?.id) { onEditCourse(c) }
            }
        }

        if (selfToday && settings.evening.enabled) item { SelfStudyRow(settings.evening, dimmed = now.isAfter(LocalTime.parse(settings.evening.end))) }

        // 待办
        val pending = repo.todos.toList().filter { !it.done }.sortedWith(compareBy(nullsLast()) { it.due })
        if (pending.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("待办事项", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.width(8.dp))
                    Text("${pending.size} 项未完成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(pending.take(5), key = { "todo" + it.id }) { t -> TodoRow(repo, t) }
            if (pending.size > 5) item { Text("更多请到「待办」页查看", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text(
                "明日课程" + (tomorrowAdjust?.let { "（${it.describe()}）" } ?: ""),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold
            )
        }
        if (tomorrowCourses.isEmpty()) {
            item { Text(if (tomorrowAdjust?.holiday == true) "明天放假" else "明天没有课", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(tomorrowCourses, key = { "m" + it.id }) { c -> CourseRow(c) { onEditCourse(c) } }
        }

        item {
            Spacer(Modifier.height(8.dp))
            Text("全部课程 (${repo.courses.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }
        val all = repo.courses.toList().sortedWith(compareBy({ it.day }, { it.startPeriod }))
        items(all, key = { "a" + it.id }) { c -> CourseRow(c, showDay = true) { onEditCourse(c) } }
    }
}

@Composable
private fun CourseRow(
    course: Course,
    dimmed: Boolean = false,
    highlight: Boolean = false,
    showDay: Boolean = false,
    onClick: () -> Unit
) {
    val color = CourseColors.of(course.colorIndex)
    val (s, _) = PeriodTimes.of(course.startPeriod)
    val (_, e) = PeriodTimes.of(course.endPeriod)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (highlight) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainer
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(44.dp)
                    .clip(CircleShape)
                    .background(if (dimmed) color.copy(alpha = 0.35f) else color)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.width(62.dp)) {
                Text(s, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                Text(e, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    course.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
                val meta = buildList {
                    if (showDay) add("${WeekdayNames[course.day - 1]} ${course.startPeriod}-${course.endPeriod}节")
                    else add("第${course.startPeriod}-${course.endPeriod}节")
                    if (course.location.isNotBlank()) add(course.location)
                    if (course.teacher.isNotBlank()) add(course.teacher)
                    if (showDay) add(course.weekDescription())
                }
                Text(meta.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (highlight) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary)
                        .clickable(enabled = false) {}
                )
            }
        }
    }
}

@Suppress("unused")
private val White = Color.White


@Composable
private fun SelfStudyRow(ss: SelfStudy, dimmed: Boolean) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = if (dimmed) 0.4f else 0.8f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${ss.start}\n${ss.end}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(14.dp))
            Text(ss.name + if (ss.location.isNotBlank()) " · ${ss.location}" else "", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}
