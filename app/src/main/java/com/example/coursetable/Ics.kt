package com.example.coursetable

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** iCalendar (.ics) 导出 / 导入 */
object Ics {
    private val dtFmt = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateFmt = DateTimeFormatter.ofPattern("yyyyMMdd")

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")
    private fun unesc(s: String) = s.replace("\\n", "\n").replace("\\N", "\n").replace("\\,", ",").replace("\\;", ";").replace("\\\\", "\\")

    /** 按"具体日期"展开导出（已自动排除放假日、包含调休补课与早晚自习） */
    fun export(repo: CourseRepository, includeSelfStudy: Boolean = true): String {
        val s = repo.settings
        val tz = ZoneId.systemDefault().id
        val sb = StringBuilder()
        sb.append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//CourseTable//大学课表//CN\r\nCALSCALE:GREGORIAN\r\nMETHOD:PUBLISH\r\n")
        sb.append("X-WR-CALNAME:大学课表\r\nX-WR-TIMEZONE:$tz\r\n")
        val stamp = LocalDateTime.now().format(dtFmt)
        val first = repo.dateOf(1, 1)
        val last = repo.dateOf(s.totalWeeks, 7)
        var d = first
        var n = 0
        while (!d.isAfter(last)) {
            val week = repo.currentWeek(d)
            repo.coursesForDate(d).forEach { c ->
                val (st, _) = PeriodTimes.of(c.startPeriod)
                val (_, en) = PeriodTimes.of(c.endPeriod)
                val start = runCatching { d.atTime(LocalTime.parse(st)) }.getOrNull() ?: return@forEach
                val end = runCatching { d.atTime(LocalTime.parse(en)) }.getOrNull() ?: return@forEach
                val adj = repo.dayAdjust(d)
                val desc = buildList {
                    if (c.teacher.isNotBlank()) add("教师：${c.teacher}")
                    add("第 $week 周 · 第${c.startPeriod}-${c.endPeriod}节")
                    if (adj != null && !adj.holiday) add("${adj.name}（按${WeekdayNames[adj.asWeekday - 1]}课表）")
                }.joinToString("\n")
                sb.append("BEGIN:VEVENT\r\n")
                sb.append("UID:${c.id}-${d.format(dateFmt)}@coursetable\r\n")
                sb.append("DTSTAMP:${stamp}\r\n")
                sb.append("DTSTART;TZID=$tz:${start.format(dtFmt)}\r\n")
                sb.append("DTEND;TZID=$tz:${end.format(dtFmt)}\r\n")
                sb.append("SUMMARY:${esc(c.name)}\r\n")
                if (c.location.isNotBlank()) sb.append("LOCATION:${esc(c.location)}\r\n")
                sb.append("DESCRIPTION:${esc(desc)}\r\n")
                sb.append("CATEGORIES:课程\r\n")
                sb.append("X-COURSETABLE-PERIOD:${c.startPeriod}-${c.endPeriod}\r\n")
                sb.append("END:VEVENT\r\n")
                n++
            }
            if (includeSelfStudy && repo.selfStudyOn(d)) {
                listOf("m" to s.morning, "e" to s.evening).forEach { (k, ss) ->
                    if (!ss.enabled) return@forEach
                    val start = runCatching { d.atTime(LocalTime.parse(ss.start)) }.getOrNull() ?: return@forEach
                    val end = runCatching { d.atTime(LocalTime.parse(ss.end)) }.getOrNull() ?: return@forEach
                    sb.append("BEGIN:VEVENT\r\nUID:selfstudy-$k-${d.format(dateFmt)}@coursetable\r\nDTSTAMP:$stamp\r\n")
                    sb.append("DTSTART;TZID=$tz:${start.format(dtFmt)}\r\nDTEND;TZID=$tz:${end.format(dtFmt)}\r\n")
                    sb.append("SUMMARY:${esc(ss.name)}\r\n")
                    if (ss.location.isNotBlank()) sb.append("LOCATION:${esc(ss.location)}\r\n")
                    sb.append("CATEGORIES:自习\r\nEND:VEVENT\r\n")
                }
            }
            d = d.plusDays(1)
        }
        // D-Day 作为全天事件
        repo.ddays.forEach { dd ->
            sb.append("BEGIN:VEVENT\r\nUID:dday-${dd.id}@coursetable\r\nDTSTAMP:$stamp\r\n")
            sb.append("DTSTART;VALUE=DATE:${dd.date.format(dateFmt)}\r\nDTEND;VALUE=DATE:${dd.date.plusDays(1).format(dateFmt)}\r\n")
            sb.append("SUMMARY:${esc(dd.name)}\r\n")
            if (dd.note.isNotBlank()) sb.append("DESCRIPTION:${esc(dd.note)}\r\n")
            sb.append("CATEGORIES:倒计时\r\nEND:VEVENT\r\n")
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    data class Parsed(val courses: List<Course>, val ddays: List<DDay>, val skipped: Int)

    private class Event {
        var summary = ""; var location = ""; var description = ""
        var start: LocalDateTime? = null; var end: LocalDateTime? = null
        var allDay: LocalDate? = null
        var rrule: String? = null
        val exdates = mutableSetOf<LocalDate>()
        var categories = ""
    }

    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        text.replace("\r\n", "\n").replace("\r", "\n").split("\n").forEach { line ->
            if ((line.startsWith(" ") || line.startsWith("\t")) && out.isNotEmpty()) out[out.lastIndex] = out.last() + line.substring(1)
            else out.add(line)
        }
        return out
    }

    private fun parseDateTime(params: String, value: String): Pair<LocalDateTime?, LocalDate?> {
        val v = value.trim()
        return try {
            if (params.contains("VALUE=DATE", true) || v.length == 8) null to LocalDate.parse(v, dateFmt)
            else {
                val core = v.removeSuffix("Z")
                var dt = LocalDateTime.parse(core.take(15), dtFmt)
                if (v.endsWith("Z")) dt = dt.atZone(ZoneId.of("UTC")).withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
                dt to null
            }
        } catch (_: Exception) { null to null }
    }

    /** 解析 ics 文本并映射为课程（节次按最接近的作息时间匹配） */
    fun parse(text: String, repo: CourseRepository): Parsed {
        val events = mutableListOf<Event>()
        var cur: Event? = null
        for (raw in unfold(text)) {
            val idx = raw.indexOf(':'); if (idx <= 0) continue
            val head = raw.substring(0, idx); val value = raw.substring(idx + 1)
            val name = head.substringBefore(';').uppercase(); val params = head.substringAfter(';', "")
            when (name) {
                "BEGIN" -> if (value.trim().equals("VEVENT", true)) cur = Event()
                "END" -> if (value.trim().equals("VEVENT", true)) { cur?.let { events.add(it) }; cur = null }
                "SUMMARY" -> cur?.summary = unesc(value).trim()
                "LOCATION" -> cur?.location = unesc(value).trim()
                "DESCRIPTION" -> cur?.description = unesc(value).trim()
                "CATEGORIES" -> cur?.categories = unesc(value).trim()
                "RRULE" -> cur?.rrule = value.trim()
                "DTSTART" -> parseDateTime(params, value).let { (dt, d) -> cur?.start = dt; cur?.allDay = d }
                "DTEND" -> parseDateTime(params, value).let { (dt, _) -> cur?.end = dt }
                "EXDATE" -> value.split(",").forEach { x -> parseDateTime(params, x).let { (dt, d) -> (dt?.toLocalDate() ?: d)?.let { cur?.exdates?.add(it) } } }
            }
        }
        val s = repo.settings
        val ddays = mutableListOf<DDay>()
        var skipped = 0
        // 分组键：名称 + 地点 + 星期 + 起止时间
        data class Key(val name: String, val loc: String, val day: Int, val st: LocalTime, val en: LocalTime)
        val groups = linkedMapOf<Key, MutableSet<Int>>()
        val teachers = mutableMapOf<Key, String>()
        for (e in events) {
            if (e.summary.isBlank()) { skipped++; continue }
            if (e.allDay != null && e.start == null) {
                if (!e.categories.contains("自习")) ddays.add(DDay(name = e.summary, date = e.allDay!!, note = e.description))
                continue
            }
            val st = e.start ?: run { skipped++; continue }
            if (e.categories.contains("自习")) continue
            val en = e.end ?: st.plusMinutes(90)
            val dates = expand(st.toLocalDate(), e.rrule).filterNot { it in e.exdates }
            val key = Key(e.summary, e.location, st.dayOfWeek.value, st.toLocalTime(), en.toLocalTime())
            val weeks = groups.getOrPut(key) { sortedSetOf() }
            dates.forEach { d -> val w = repo.currentWeek(d); if (w in 1..s.totalWeeks) weeks.add(w) }
            Regex("(教师|老师|Teacher)[：:]\\s*(\\S+)").find(e.description)?.groupValues?.get(2)?.let { teachers[key] = it }
        }
        val courses = mutableListOf<Course>()
        var color = 0
        for ((k, weeks) in groups) {
            if (weeks.isEmpty()) { skipped++; continue }
            val sp = nearestPeriod(k.st, true); val ep = nearestPeriod(k.en, false).coerceAtLeast(sp)
            val sorted = weeks.sorted()
            val type = when {
                sorted.size >= 2 && sorted.all { it % 2 == 1 } && sorted.zipWithNext().all { (a, b) -> b - a == 2 } -> 1
                sorted.size >= 2 && sorted.all { it % 2 == 0 } && sorted.zipWithNext().all { (a, b) -> b - a == 2 } -> 2
                else -> 0
            }
            courses.add(Course(
                name = k.name, teacher = teachers[k].orEmpty(), location = k.loc, day = k.day,
                startPeriod = sp, endPeriod = ep, startWeek = sorted.first(), endWeek = sorted.last(), weekType = type,
                colorIndex = color++ % CourseColors.size
            ))
        }
        return Parsed(courses, ddays, skipped)
    }

    private fun expand(first: LocalDate, rrule: String?): List<LocalDate> {
        if (rrule == null) return listOf(first)
        val parts = rrule.split(";").mapNotNull { p -> p.split("=").takeIf { it.size == 2 }?.let { it[0].uppercase() to it[1] } }.toMap()
        if (parts["FREQ"]?.uppercase() != "WEEKLY") return listOf(first)
        val interval = parts["INTERVAL"]?.toIntOrNull() ?: 1
        val count = parts["COUNT"]?.toIntOrNull()
        val until = parts["UNTIL"]?.let { u -> runCatching { LocalDate.parse(u.take(8), dateFmt) }.getOrNull() }
        val out = mutableListOf<LocalDate>()
        var d = first
        while (out.size < (count ?: 60) && out.size < 60) {
            if (until != null && d.isAfter(until)) break
            out.add(d); d = d.plusWeeks(interval.toLong())
        }
        return out
    }

    private fun nearestPeriod(t: LocalTime, isStart: Boolean): Int {
        var best = 1; var bestDiff = Long.MAX_VALUE
        PeriodTimes.times.forEachIndexed { i, (st, en) ->
            val ref = runCatching { LocalTime.parse(if (isStart) st else en) }.getOrNull() ?: return@forEachIndexed
            val diff = kotlin.math.abs(java.time.Duration.between(ref, t).toMinutes())
            if (diff < bestDiff) { bestDiff = diff; best = i + 1 }
        }
        return best
    }
}
