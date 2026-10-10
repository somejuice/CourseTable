package com.example.coursetable

import java.time.LocalDate

/** 日期调整：放假 或 调休上班（按某个星期几的课表上课） */
data class DayAdjust(
    val date: LocalDate,
    val holiday: Boolean,          // true 放假；false 调休上班
    val name: String,              // 节日名
    val asWeekday: Int = 0,        // 调休时按周几课表上课（1..7），放假时为 0
    val custom: Boolean = false,   // 用户自定义（可删除）
    val asWeek: Int = 0            // 调休时按第几周的课表上课，0 = 当天所在周
) {
    /** 用于展示的"按 xx 课表"描述 */
    fun describe(): String = if (holiday) "${name}放假" else "${name}，按" + (if (asWeek > 0) "第${asWeek}周" else "") + WeekdayNames[(asWeekday - 1).coerceIn(0, 6)] + "课表"
    fun toJson() = org.json.JSONObject().apply {
        put("date", date.toString()); put("holiday", holiday); put("name", name); put("asWeekday", asWeekday); put("asWeek", asWeek)
    }
    companion object {
        fun fromJson(o: org.json.JSONObject) = DayAdjust(
            LocalDate.parse(o.getString("date")), o.optBoolean("holiday", true), o.optString("name", ""), o.optInt("asWeekday", 0), custom = true, asWeek = o.optInt("asWeek", 0)
        )
    }
}

/** 国务院办公厅公布的法定节假日与调休安排（内置 2025、2026 年） */
object HolidayCalendar {
    private fun range(from: String, to: String, name: String): List<DayAdjust> {
        val a = LocalDate.parse(from); val b = LocalDate.parse(to)
        return generateSequence(a) { d -> if (d < b) d.plusDays(1) else null }.map { DayAdjust(it, true, name) }.toList()
    }
    private fun work(date: String, name: String, asWeekday: Int) = DayAdjust(LocalDate.parse(date), false, name, asWeekday)

    val builtin: Map<LocalDate, DayAdjust> by lazy {
        val list = buildList {
            // ---- 2025 ----
            addAll(range("2025-01-01", "2025-01-01", "元旦"))
            addAll(range("2025-01-28", "2025-02-04", "春节")); add(work("2025-01-26", "春节调休", 1)); add(work("2025-02-08", "春节调休", 2))
            addAll(range("2025-04-04", "2025-04-06", "清明节"))
            addAll(range("2025-05-01", "2025-05-05", "劳动节")); add(work("2025-04-27", "劳动节调休", 5))
            addAll(range("2025-05-31", "2025-06-02", "端午节"))
            addAll(range("2025-10-01", "2025-10-08", "国庆·中秋")); add(work("2025-09-28", "国庆调休", 1)); add(work("2025-10-11", "国庆调休", 5))
            // ---- 2026（国办发〔2025〕年通知）----
            addAll(range("2026-01-01", "2026-01-03", "元旦")); add(work("2026-01-04", "元旦调休", 5))
            addAll(range("2026-02-15", "2026-02-23", "春节")); add(work("2026-02-14", "春节调休", 1)); add(work("2026-02-28", "春节调休", 5))
            addAll(range("2026-04-04", "2026-04-06", "清明节"))
            addAll(range("2026-05-01", "2026-05-05", "劳动节")); add(work("2026-05-09", "劳动节调休", 1))
            addAll(range("2026-06-19", "2026-06-21", "端午节"))
            addAll(range("2026-09-25", "2026-09-27", "中秋节"))
            addAll(range("2026-10-01", "2026-10-07", "国庆节")); add(work("2026-09-20", "国庆调休", 1)); add(work("2026-10-10", "国庆调休", 5))
        }
        list.associateBy { it.date }
    }
}
