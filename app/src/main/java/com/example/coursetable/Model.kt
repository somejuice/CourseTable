package com.example.coursetable

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 周次类型：0 全部周，1 单周，2 双周 */
data class Course(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val teacher: String = "",
    val location: String = "",
    val day: Int = 1,            // 1 = 周一 ... 7 = 周日
    val startPeriod: Int = 1,
    val endPeriod: Int = 2,
    val startWeek: Int = 1,
    val endWeek: Int = 16,
    val weekType: Int = 0,
    val colorIndex: Int = 0
) {
    fun occursInWeek(week: Int): Boolean {
        if (week < startWeek || week > endWeek) return false
        return when (weekType) {
            1 -> week % 2 == 1
            2 -> week % 2 == 0
            else -> true
        }
    }

    fun weekDescription(): String {
        val range = if (startWeek == endWeek) "第${startWeek}周" else "${startWeek}-${endWeek}周"
        return when (weekType) {
            1 -> "$range(单)"
            2 -> "$range(双)"
            else -> range
        }
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("teacher", teacher); put("location", location)
        put("day", day); put("startPeriod", startPeriod); put("endPeriod", endPeriod)
        put("startWeek", startWeek); put("endWeek", endWeek); put("weekType", weekType)
        put("colorIndex", colorIndex)
    }

    companion object {
        fun fromJson(o: JSONObject) = Course(
            id = o.optString("id", UUID.randomUUID().toString()),
            name = o.optString("name"),
            teacher = o.optString("teacher"),
            location = o.optString("location"),
            day = o.optInt("day", 1),
            startPeriod = o.optInt("startPeriod", 1),
            endPeriod = o.optInt("endPeriod", 2),
            startWeek = o.optInt("startWeek", 1),
            endWeek = o.optInt("endWeek", 16),
            weekType = o.optInt("weekType", 0),
            colorIndex = o.optInt("colorIndex", 0)
        )
    }
}

/** 课程待办 */
data class Todo(
    val id: String = java.util.UUID.randomUUID().toString(),
    val courseId: String = "",      // 可为空：通用待办
    val title: String,
    val note: String = "",
    val due: LocalDate? = null,
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("courseId", courseId); put("title", title); put("note", note)
        put("due", due?.toString() ?: ""); put("done", done); put("createdAt", createdAt)
    }
    companion object {
        fun fromJson(o: JSONObject) = Todo(
            id = o.optString("id", java.util.UUID.randomUUID().toString()),
            courseId = o.optString("courseId", ""), title = o.optString("title", ""), note = o.optString("note", ""),
            due = o.optString("due", "").takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            done = o.optBoolean("done", false), createdAt = o.optLong("createdAt", System.currentTimeMillis())
        )
    }
}

/** 早 / 晚自习（显示在课表首尾的固定行） */
data class SelfStudy(
    val enabled: Boolean = false,
    val name: String = "自习",
    val start: String = "07:20",
    val end: String = "07:50",
    val location: String = ""
) {
    fun toJson() = JSONObject().apply { put("enabled", enabled); put("name", name); put("start", start); put("end", end); put("location", location) }
    companion object {
        fun fromJson(o: JSONObject?, def: SelfStudy) = if (o == null) def else SelfStudy(
            o.optBoolean("enabled", def.enabled), o.optString("name", def.name), o.optString("start", def.start), o.optString("end", def.end), o.optString("location", def.location)
        )
        val MORNING = SelfStudy(name = "早自习", start = "07:20", end = "07:50")
        val EVENING = SelfStudy(name = "晚自习", start = "19:00", end = "21:00")
    }
}

/** D-Day 倒计时 */
data class DDay(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val date: LocalDate,
    val note: String = "",
    val pinned: Boolean = false
) {
    fun daysLeft(today: LocalDate = LocalDate.now()): Long = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    fun toJson() = JSONObject().apply { put("id", id); put("name", name); put("date", date.toString()); put("note", note); put("pinned", pinned) }
    companion object {
        fun fromJson(o: JSONObject) = DDay(
            o.optString("id", java.util.UUID.randomUUID().toString()), o.optString("name", ""), LocalDate.parse(o.getString("date")), o.optString("note", ""), o.optBoolean("pinned", false)
        )
    }
}

data class AppSettings(
    val semesterStart: LocalDate = defaultSemesterStart(),
    val totalWeeks: Int = 20,
    val periodsPerDay: Int = 12,
    val showWeekend: Boolean = true,
    val dynamicColor: Boolean = true,
    val darkMode: Int = 0, // 0 跟随系统 1 浅色 2 深色
    val reminderEnabled: Boolean = false,
    val reminderMode: Int = 0,       // 0 通知提醒 1 闹钟提醒
    val reminderLeadMinutes: Int = 10,
    // 上课自动模式（各项独立）
    val classSilent: Boolean = false,
    val classDnd: Boolean = false,
    val classAirplane: Boolean = false,
    // 通知栏常驻
    val persistentNotification: Boolean = false,
    // 自动标注法定假期 / 调休
    val holidayAuto: Boolean = true,
    // 早晚自习
    val morning: SelfStudy = SelfStudy.MORNING,
    val evening: SelfStudy = SelfStudy.EVENING,
    val selfStudyWeekend: Boolean = false,
    // 壁纸：文件名（空=无）、遮罩不透明度、模糊半径(dp)
    val wallpaper: String = "",
    val wallpaperDim: Float = 0.35f,
    val wallpaperBlur: Int = 0
) {
    companion object {
        fun defaultSemesterStart(): LocalDate {
            val today = LocalDate.now()
            val start = if (today.monthValue >= 8 || today.monthValue <= 1) {
                val year = if (today.monthValue <= 1) today.year - 1 else today.year
                LocalDate.of(year, 9, 1)
            } else {
                LocalDate.of(today.year, 2, 20)
            }
            return start.with(DayOfWeek.MONDAY)
        }
    }
}

object PeriodTimes {
    val defaults = listOf(
        "08:00" to "08:45", "08:55" to "09:40", "10:00" to "10:45", "10:55" to "11:40",
        "14:00" to "14:45", "14:55" to "15:40", "16:00" to "16:45", "16:55" to "17:40",
        "19:00" to "19:45", "19:55" to "20:40", "20:50" to "21:35", "21:45" to "22:30",
        "22:40" to "23:25", "23:30" to "23:59"
    )

    /** 可自定义的节次时间（时:分），由 CourseRepository 加载/保存 */
    val times = mutableStateListOf<Pair<String, String>>().apply { addAll(defaults) }

    fun of(period: Int): Pair<String, String> = times.getOrElse(period - 1) { "--:--" to "--:--" }

    fun format(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

    fun serialize(): String = times.joinToString(",") { "${it.first}-${it.second}" }

    fun deserialize(text: String) {
        val parsed = text.split(",").mapNotNull { seg ->
            val parts = seg.split("-")
            if (parts.size == 2 && parts[0].length == 5 && parts[1].length == 5) parts[0] to parts[1] else null
        }
        if (parsed.size == defaults.size) {
            times.clear(); times.addAll(parsed)
        }
    }
}

val WeekdayNames = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

class CourseRepository(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("course_table", Context.MODE_PRIVATE)

    val courses = mutableStateListOf<Course>()
    val todos = mutableStateListOf<Todo>()
    val customAdjusts = mutableStateListOf<DayAdjust>()
    val ddays = mutableStateListOf<DDay>()
    var settings by mutableStateOf(AppSettings())
        private set

    private var ready = false

    init {
        load()
        ready = true
    }

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val rescheduleRunnable = Runnable {
        val snapshot = this
        Thread {
            runCatching { ReminderScheduler.schedule(appContext, snapshot) }
            runCatching { WidgetUpdater.updateAll(appContext, snapshot) }
        }.start()
    }

    /** 课程/设置变化后，去抖 600ms 再在后台线程刷新提醒与小组件，避免拖动滑块时反复计算 */
    private fun reschedule() {
        if (!ready) return
        mainHandler.removeCallbacks(rescheduleRunnable)
        mainHandler.postDelayed(rescheduleRunnable, 600)
    }

    private fun load(settingsOnly: Boolean = false) {
        if (!settingsOnly) {
            prefs.getString("todos", null)?.let { json ->
                runCatching { val arr = JSONArray(json); for (i in 0 until arr.length()) todos.add(Todo.fromJson(arr.getJSONObject(i))) }
            }
            prefs.getString("ddays", null)?.let { json ->
                runCatching { val arr = JSONArray(json); for (i in 0 until arr.length()) ddays.add(DDay.fromJson(arr.getJSONObject(i))) }
            }
            prefs.getString("dayAdjusts", null)?.let { json ->
                runCatching { val arr = JSONArray(json); for (i in 0 until arr.length()) customAdjusts.add(DayAdjust.fromJson(arr.getJSONObject(i))) }
            }
            val json = prefs.getString("courses", null)
            if (json == null) {
                courses.addAll(SampleData.courses())
                persistCourses()
            } else {
                runCatching {
                    val arr = JSONArray(json)
                    for (i in 0 until arr.length()) courses.add(Course.fromJson(arr.getJSONObject(i)))
                }
            }
        }
        prefs.getString("periodTimes", null)?.let { PeriodTimes.deserialize(it) }
        val s = prefs.getString("settings", null)
        if (s != null) {
            runCatching {
                val o = JSONObject(s)
                settings = AppSettings(
                    semesterStart = LocalDate.parse(o.optString("semesterStart", AppSettings.defaultSemesterStart().toString())),
                    totalWeeks = o.optInt("totalWeeks", 20),
                    periodsPerDay = o.optInt("periodsPerDay", 12),
                    showWeekend = o.optBoolean("showWeekend", true),
                    dynamicColor = o.optBoolean("dynamicColor", true),
                    darkMode = o.optInt("darkMode", 0),
                    reminderEnabled = o.optBoolean("reminderEnabled", false),
                    reminderMode = o.optInt("reminderMode", 0),
                    reminderLeadMinutes = o.optInt("reminderLeadMinutes", 10),
                    classSilent = o.optBoolean("classSilent", false),
                    classDnd = o.optBoolean("classDnd", false),
                    classAirplane = o.optBoolean("classAirplane", false),
                    persistentNotification = o.optBoolean("persistentNotification", false),
                    holidayAuto = o.optBoolean("holidayAuto", true),
                    morning = SelfStudy.fromJson(o.optJSONObject("morning"), SelfStudy.MORNING),
                    evening = SelfStudy.fromJson(o.optJSONObject("evening"), SelfStudy.EVENING),
                    selfStudyWeekend = o.optBoolean("selfStudyWeekend", false),
                    wallpaper = o.optString("wallpaper", ""),
                    wallpaperDim = o.optDouble("wallpaperDim", 0.35).toFloat(),
                    wallpaperBlur = o.optInt("wallpaperBlur", 0)
                )
            }
        }
    }

    private fun persistCourses() {
        val arr = JSONArray()
        courses.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("courses", arr.toString()).apply()
        reschedule()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settings = transform(settings)
        val o = JSONObject().apply {
            put("semesterStart", settings.semesterStart.toString())
            put("totalWeeks", settings.totalWeeks)
            put("periodsPerDay", settings.periodsPerDay)
            put("showWeekend", settings.showWeekend)
            put("dynamicColor", settings.dynamicColor)
            put("darkMode", settings.darkMode)
            put("reminderEnabled", settings.reminderEnabled)
            put("reminderMode", settings.reminderMode)
            put("reminderLeadMinutes", settings.reminderLeadMinutes)
            put("classSilent", settings.classSilent)
            put("classDnd", settings.classDnd)
            put("classAirplane", settings.classAirplane)
            put("persistentNotification", settings.persistentNotification)
            put("holidayAuto", settings.holidayAuto)
            put("morning", settings.morning.toJson())
            put("evening", settings.evening.toJson())
            put("selfStudyWeekend", settings.selfStudyWeekend)
            put("wallpaper", settings.wallpaper); put("wallpaperDim", settings.wallpaperDim.toDouble()); put("wallpaperBlur", settings.wallpaperBlur)
        }
        prefs.edit().putString("settings", o.toString()).apply()
        reschedule()
    }

    private fun settingsJson(): JSONObject = JSONObject(prefs.getString("settings", null) ?: "{}")

    // ---------- 导出 / 导入 ----------
    fun exportJson(): String {
        val arr = JSONArray(); courses.forEach { arr.put(it.toJson()) }
        return JSONObject().apply {
            put("app", "CourseTable"); put("version", 1)
            put("exportedAt", java.time.LocalDateTime.now().toString())
            put("settings", settingsJson())
            put("periodTimes", PeriodTimes.serialize())
            put("courses", arr)
            put("todos", JSONArray().also { a -> todos.forEach { a.put(it.toJson()) } })
            put("dayAdjusts", JSONArray().also { a -> customAdjusts.forEach { a.put(it.toJson()) } })
            put("ddays", JSONArray().also { a -> ddays.forEach { a.put(it.toJson()) } })
        }.toString(2)
    }

    /** 面对面共享用的紧凑 JSON（课程 + 作息 + 学期信息） */
    fun shareJson(): String {
        val arr = JSONArray(); courses.forEach { arr.put(it.toJson()) }
        val so = JSONObject().apply {
            put("semesterStart", settings.semesterStart.toString())
            put("totalWeeks", settings.totalWeeks)
            put("periodsPerDay", settings.periodsPerDay)
            put("showWeekend", settings.showWeekend)
        }
        return JSONObject().apply {
            put("app", "CourseTable"); put("version", 1)
            put("settings", so); put("periodTimes", PeriodTimes.serialize()); put("courses", arr)
        }.toString()
    }

    /** 返回导入的课程数量；格式错误返回 -1 */
    fun importJson(text: String, replace: Boolean = true): Int {
        return try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("courses") ?: return -1
            val imported = (0 until arr.length()).map { Course.fromJson(arr.getJSONObject(it)) }.filter { it.name.isNotBlank() }
            if (replace) courses.clear()
            val existing = courses.map { it.id }.toMutableSet()
            imported.forEach { c ->
                if (c.id in existing) courses.add(c.copy(id = UUID.randomUUID().toString())) else { courses.add(c); existing.add(c.id) }
            }
            persistCourses()
            o.optJSONArray("todos")?.let { ta ->
                val ids = todos.map { it.id }.toMutableSet()
                for (i in 0 until ta.length()) { val t = Todo.fromJson(ta.getJSONObject(i)); if (t.title.isNotBlank() && ids.add(t.id)) todos.add(t) }
                persistTodos()
            }
            o.optJSONArray("ddays")?.let { da ->
                val ids = ddays.map { it.id }.toMutableSet()
                for (i in 0 until da.length()) { runCatching { DDay.fromJson(da.getJSONObject(i)) }.getOrNull()?.let { if (it.name.isNotBlank() && ids.add(it.id)) ddays.add(it) } }
                persistDDays()
            }
            o.optJSONArray("dayAdjusts")?.let { da ->
                for (i in 0 until da.length()) { val d = DayAdjust.fromJson(da.getJSONObject(i)); customAdjusts.removeAll { it.date == d.date }; customAdjusts.add(d) }
                persistAdjusts()
            }
            o.optString("periodTimes", "").takeIf { it.isNotBlank() }?.let {
                PeriodTimes.deserialize(it); prefs.edit().putString("periodTimes", PeriodTimes.serialize()).apply()
            }
            o.optJSONObject("settings")?.let { so ->
                val keep = settings
                val merged = settingsJson()
                so.keys().forEach { k -> merged.put(k, so.get(k)) }
                prefs.edit().putString("settings", merged.toString()).apply()
                load(settingsOnly = true)
                // 提醒与外观相关沿用本机设置
                updateSettings {
                    it.copy(
                        reminderEnabled = keep.reminderEnabled, reminderMode = keep.reminderMode, reminderLeadMinutes = keep.reminderLeadMinutes,
                        dynamicColor = keep.dynamicColor, darkMode = keep.darkMode,
                        classSilent = keep.classSilent, classDnd = keep.classDnd, classAirplane = keep.classAirplane,
                        persistentNotification = keep.persistentNotification
                    )
                }
            }
            reschedule()
            imported.size
        } catch (e: Exception) { -1 }
    }

    // ---------- 提醒 ----------
    data class ReminderTarget(val course: Course, val triggerAt: java.time.LocalDateTime, val startAt: java.time.LocalDateTime)

    fun nextReminder(now: java.time.LocalDateTime = java.time.LocalDateTime.now()): ReminderTarget? {
        if (!settings.reminderEnabled) return null
        val lead = settings.reminderLeadMinutes.toLong()
        for (offset in 0..21L) {
            val date = now.toLocalDate().plusDays(offset)
            var best: ReminderTarget? = null
            for (c in coursesForDate(date)) {
                val t = PeriodTimes.of(c.startPeriod).first
                val start = runCatching { date.atTime(java.time.LocalTime.parse(t)) }.getOrNull() ?: continue
                val trigger = start.minusMinutes(lead)
                if (trigger.isAfter(now) && (best == null || trigger.isBefore(best.triggerAt))) best = ReminderTarget(c, trigger, start)
            }
            if (best != null) return best
        }
        return null
    }

    fun updatePeriodTime(period: Int, start: String? = null, end: String? = null) {
        val idx = period - 1
        if (idx !in PeriodTimes.times.indices) return
        val cur = PeriodTimes.times[idx]
        PeriodTimes.times[idx] = (start ?: cur.first) to (end ?: cur.second)
        prefs.edit().putString("periodTimes", PeriodTimes.serialize()).apply()
        reschedule()
    }

    fun resetPeriodTimes() {
        PeriodTimes.times.clear(); PeriodTimes.times.addAll(PeriodTimes.defaults)
        prefs.edit().remove("periodTimes").apply()
    }

    fun upsert(course: Course) {
        val idx = courses.indexOfFirst { it.id == course.id }
        if (idx >= 0) courses[idx] = course else courses.add(course)
        persistCourses()
    }

    fun delete(id: String) {
        courses.removeAll { it.id == id }
        persistCourses()
    }

    fun clearAll() {
        courses.clear()
        persistCourses()
    }

    fun resetSample() {
        courses.clear()
        courses.addAll(SampleData.courses())
        persistCourses()
    }

    /** 当前周次（从 1 开始），学期开始前返回 0 */
    fun currentWeek(today: LocalDate = LocalDate.now()): Int {
        val start = settings.semesterStart.with(DayOfWeek.MONDAY)
        val days = ChronoUnit.DAYS.between(start, today)
        if (days < 0) return 0
        return (days / 7).toInt() + 1
    }

    fun dateOf(week: Int, day: Int): LocalDate =
        settings.semesterStart.with(DayOfWeek.MONDAY).plusDays(((week - 1) * 7 + (day - 1)).toLong())

    fun coursesForWeek(week: Int): List<Course> = courses.filter { it.occursInWeek(week) }

    fun coursesForDay(week: Int, day: Int): List<Course> =
        coursesForWeek(week).filter { it.day == day }.sortedBy { it.startPeriod }

    // ---------- 假期 / 调休 ----------

    /** 某日的调整（用户自定义优先于内置；关闭自动标注后仅保留自定义） */
    fun dayAdjust(date: LocalDate): DayAdjust? {
        customAdjusts.firstOrNull { it.date == date }?.let { return it }
        return if (settings.holidayAuto) HolidayCalendar.builtin[date] else null
    }

    fun setCustomAdjust(adj: DayAdjust) {
        customAdjusts.removeAll { it.date == adj.date }
        customAdjusts.add(adj.copy(custom = true))
        persistAdjusts()
    }

    fun removeCustomAdjust(date: LocalDate) {
        customAdjusts.removeAll { it.date == date }
        persistAdjusts()
    }

    private fun persistAdjusts() {
        prefs.edit().putString("dayAdjusts", JSONArray().also { a -> customAdjusts.forEach { a.put(it.toJson()) } }.toString()).apply()
        reschedule()
    }

    /** 某个具体日期的课程：考虑学期范围、放假、调休 */
    fun coursesForDate(date: LocalDate): List<Course> {
        val week = currentWeek(date)
        if (week !in 1..settings.totalWeeks) return emptyList()
        val adj = dayAdjust(date)
        if (adj != null) {
            if (adj.holiday) return emptyList()
            if (adj.asWeekday in 1..7) return coursesForDay(if (adj.asWeek in 1..settings.totalWeeks) adj.asWeek else week, adj.asWeekday)
        }
        return coursesForDay(week, date.dayOfWeek.value)
    }

    // ---------- D-Day ----------

    fun upsertDDay(d: DDay) {
        val i = ddays.indexOfFirst { it.id == d.id }
        if (i >= 0) ddays[i] = d else ddays.add(d)
        persistDDays()
    }

    fun deleteDDay(id: String) { ddays.removeAll { it.id == id }; persistDDays() }

    private fun persistDDays() {
        prefs.edit().putString("ddays", JSONArray().also { a -> ddays.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    /** 某天是否显示早晚自习（学期内、非放假；周末按设置） */
    fun selfStudyOn(date: LocalDate): Boolean {
        if (currentWeek(date) !in 1..settings.totalWeeks) return false
        if (dayAdjust(date)?.holiday == true) return false
        val wd = dayAdjust(date)?.takeIf { !it.holiday && it.asWeekday in 1..7 }?.asWeekday ?: date.dayOfWeek.value
        return settings.selfStudyWeekend || wd <= 5
    }

    // ---------- 课程待办 ----------

    fun upsertTodo(t: Todo) {
        val i = todos.indexOfFirst { it.id == t.id }
        if (i >= 0) todos[i] = t else todos.add(t)
        persistTodos()
    }

    fun toggleTodo(id: String) { todos.firstOrNull { it.id == id }?.let { upsertTodo(it.copy(done = !it.done)) } }

    fun deleteTodo(id: String) { todos.removeAll { it.id == id }; persistTodos() }

    fun pendingTodoCount(courseId: String): Int = todos.count { it.courseId == courseId && !it.done }

    private fun persistTodos() {
        prefs.edit().putString("todos", JSONArray().also { a -> todos.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    /** 某天的课程时间段（合并相邻/重叠课程），用于上课自动模式与常驻通知 */
    data class ClassBlock(val start: java.time.LocalDateTime, val end: java.time.LocalDateTime, val courses: List<Course>)

    fun blocksForDate(date: LocalDate): List<ClassBlock> {
        val items = coursesForDate(date).mapNotNull { c ->
            val s = runCatching { date.atTime(java.time.LocalTime.parse(PeriodTimes.of(c.startPeriod).first)) }.getOrNull()
            val e = runCatching { date.atTime(java.time.LocalTime.parse(PeriodTimes.of(c.endPeriod).second)) }.getOrNull()
            if (s == null || e == null || !e.isAfter(s)) null else Triple(s, e, c)
        }.sortedBy { it.first }
        val out = mutableListOf<ClassBlock>()
        for ((s, e, c) in items) {
            val last = out.lastOrNull()
            if (last != null && !s.isAfter(last.end)) {
                out[out.lastIndex] = ClassBlock(last.start, maxOf(last.end, e), last.courses + c)
            } else out.add(ClassBlock(s, e, listOf(c)))
        }
        return out
    }
}

object SampleData {
    fun courses(): List<Course> = listOf(
        Course(name = "高等数学", teacher = "王教授", location = "教一 201", day = 1, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 16, colorIndex = 0),
        Course(name = "大学英语", teacher = "李老师", location = "外语楼 305", day = 1, startPeriod = 3, endPeriod = 4, startWeek = 1, endWeek = 16, colorIndex = 1),
        Course(name = "程序设计基础", teacher = "张老师", location = "计算中心 A302", day = 2, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 18, colorIndex = 2),
        Course(name = "线性代数", teacher = "赵教授", location = "教二 108", day = 2, startPeriod = 5, endPeriod = 6, startWeek = 1, endWeek = 16, colorIndex = 3),
        Course(name = "大学物理", teacher = "刘教授", location = "理科楼 401", day = 3, startPeriod = 3, endPeriod = 4, startWeek = 1, endWeek = 16, colorIndex = 4),
        Course(name = "物理实验", teacher = "孙老师", location = "实验楼 B201", day = 3, startPeriod = 5, endPeriod = 8, startWeek = 3, endWeek = 14, weekType = 2, colorIndex = 5),
        Course(name = "思想道德与法治", teacher = "周老师", location = "教三 501", day = 4, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 16, weekType = 1, colorIndex = 6),
        Course(name = "数据结构", teacher = "吴教授", location = "计算中心 A305", day = 4, startPeriod = 3, endPeriod = 4, startWeek = 1, endWeek = 18, colorIndex = 7),
        Course(name = "体育(羽毛球)", teacher = "郑老师", location = "体育馆", day = 5, startPeriod = 5, endPeriod = 6, startWeek = 1, endWeek = 16, colorIndex = 8),
        Course(name = "离散数学", teacher = "冯老师", location = "教一 302", day = 5, startPeriod = 1, endPeriod = 2, startWeek = 1, endWeek = 16, colorIndex = 9),
        Course(name = "形势与政策", teacher = "陈老师", location = "大礼堂", day = 2, startPeriod = 9, endPeriod = 10, startWeek = 2, endWeek = 8, weekType = 2, colorIndex = 1),
        Course(name = "创新创业选修", teacher = "何老师", location = "创业园 2F", day = 6, startPeriod = 3, endPeriod = 4, startWeek = 4, endWeek = 12, colorIndex = 3)
    )
}
