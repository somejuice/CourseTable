package com.example.coursetable

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** 桌面小组件统一刷新入口 */
object WidgetUpdater {
    fun updateAll(context: Context, repo: CourseRepository = CourseRepository(context)) {
        val mgr = AppWidgetManager.getInstance(context)
        mgr.getAppWidgetIds(ComponentName(context, TodayWidget::class.java)).forEach {
            mgr.updateAppWidget(it, buildToday(context, repo))
        }
        mgr.getAppWidgetIds(ComponentName(context, WeekWidget::class.java)).forEach {
            mgr.updateAppWidget(it, buildWeek(context, repo))
        }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 10, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun isDark(context: Context) =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    private fun courseColor(context: Context, index: Int): Int {
        val c = CourseColors.argb(index, isDark(context))
        return c
    }

    /** 保留圆角形状的同时着色（API 31+ 用 tint，低版本退化为纯色背景） */
    private fun tintBackground(views: RemoteViews, viewId: Int, color: Int) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            views.setColorStateList(viewId, "setBackgroundTintList", android.content.res.ColorStateList.valueOf(color))
        } else {
            views.setInt(viewId, "setBackgroundColor", color)
        }
    }

    fun buildToday(context: Context, repo: CourseRepository): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_today)
        val today = LocalDate.now()
        val now = LocalTime.now()
        val week = repo.currentWeek(today)
        val inSemester = week in 1..repo.settings.totalWeeks
        val courses = repo.coursesForDate(today)
        val adj = repo.dayAdjust(today)

        views.setTextViewText(
            R.id.widget_subtitle,
            today.format(DateTimeFormatter.ofPattern("M月d日")) + " " + WeekdayNames[today.dayOfWeek.value - 1] +
                    (if (inSemester) " · 第${week}周" else " · 假期") +
                            (adj?.let { " · " + it.describe() } ?: "")
        )
        val muted = context.getColor(R.color.widget_muted)
        views.removeAllViews(R.id.widget_list)
        if (courses.isEmpty()) {
            views.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            views.setTextViewText(R.id.widget_empty, if (inSemester) "今天没有课 🎉" else "假期中，好好休息")
        } else {
            views.setViewVisibility(R.id.widget_empty, View.GONE)
            courses.take(8).forEach { c ->
                val item = RemoteViews(context.packageName, R.layout.widget_item)
                val (s, _) = PeriodTimes.of(c.startPeriod)
                val (_, e) = PeriodTimes.of(c.endPeriod)
                item.setTextViewText(R.id.item_start, s)
                item.setTextViewText(R.id.item_end, e)
                item.setTextViewText(R.id.item_name, c.name)
                val meta = listOf("第${c.startPeriod}-${c.endPeriod}节", c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ")
                item.setTextViewText(R.id.item_meta, meta)
                val finished = runCatching { now.isAfter(LocalTime.parse(e)) }.getOrDefault(false)
                tintBackground(item, R.id.item_bar, if (finished) muted else courseColor(context, c.colorIndex))
                if (finished) {
                    item.setTextColor(R.id.item_name, muted)
                    item.setTextColor(R.id.item_start, muted)
                }
                views.addView(R.id.widget_list, item)
            }
        }
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }

    fun buildWeek(context: Context, repo: CourseRepository): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_week)
        val today = LocalDate.now()
        val week = repo.currentWeek(today).coerceIn(1, repo.settings.totalWeeks)
        val inSemester = repo.currentWeek(today) in 1..repo.settings.totalWeeks
        val dayCount = if (repo.settings.showWeekend) 7 else 5
        val dark = isDark(context)

        views.setTextViewText(R.id.widget_subtitle, if (inSemester) "第 $week 周" else "假期 · 显示第 $week 周")
        views.removeAllViews(R.id.widget_columns)
        val accent = context.getColor(R.color.widget_accent)
        for (d in 1..dayCount) {
            val col = RemoteViews(context.packageName, R.layout.widget_week_column)
            val date = repo.dateOf(week, d)
            val isToday = date == today
            val adj = repo.dayAdjust(date)
            val tag = adj?.let { if (it.holiday) "假" else "班" } ?: ""
            col.setTextViewText(R.id.column_title, WeekdayNames[d - 1].removePrefix("周") + tag + "\n" + date.dayOfMonth)
            if (isToday) col.setTextColor(R.id.column_title, accent)
            col.removeAllViews(R.id.column_items)
            repo.coursesForDate(date).take(6).forEach { c ->
                val cell = RemoteViews(context.packageName, R.layout.widget_week_cell)
                cell.setTextViewText(R.id.cell_text, c.name + if (c.location.isNotBlank()) "\n@${c.location}" else "")
                val color = CourseColors.argb(c.colorIndex, dark)
                tintBackground(cell, R.id.cell_text, color)
                cell.setTextColor(R.id.cell_text, 0xFFFFFFFF.toInt())
                col.addView(R.id.column_items, cell)
            }
            views.addView(R.id.widget_columns, col)
        }
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }
}

class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = CourseRepository(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetUpdater.buildToday(context, repo)) }
    }
}

class WeekWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val repo = CourseRepository(context)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, WidgetUpdater.buildWeek(context, repo)) }
    }
}
