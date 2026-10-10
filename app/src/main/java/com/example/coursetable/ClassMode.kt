package com.example.coursetable

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课自动模式（静音 / 免打扰 / 飞行模式，各项独立）+ 通知栏常驻。
 * 始终只安排"下一次状态切换"（上课开始或结束）的精确闹钟，触发后重新计算。
 */
object ClassModeManager {
    private const val PREFS = "class_mode_state"
    private const val REQ = 1003
    const val ACTION_TICK = "com.example.coursetable.CLASS_MODE_TICK"
    const val CHANNEL_STATUS = "course_status"
    const val CHANNEL_AIRPLANE = "course_airplane_hint"
    const val STATUS_NOTIFICATION_ID = 7001
    private const val AIRPLANE_HINT_ID = 7002
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun hasPolicyAccess(context: Context): Boolean =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).isNotificationPolicyAccessGranted

    fun hasSecureSettings(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_STATUS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_STATUS, "课表常驻通知", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "在通知栏常驻显示当前/下一节课程"; setShowBadge(false); enableVibration(false); setSound(null, null)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_AIRPLANE) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_AIRPLANE, "飞行模式提示", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "未授权自动切换飞行模式时，上下课提示手动切换"
                }
            )
        }
    }

    /** 当前所在的课程时间段（若正在上课） */
    fun currentBlock(repo: CourseRepository, now: LocalDateTime = LocalDateTime.now()): CourseRepository.ClassBlock? =
        repo.blocksForDate(now.toLocalDate()).firstOrNull { !now.isBefore(it.start) && now.isBefore(it.end) }

    /** 下一节课（今天剩余或未来 21 天） */
    fun nextBlock(repo: CourseRepository, now: LocalDateTime = LocalDateTime.now()): CourseRepository.ClassBlock? {
        for (offset in 0..21L) {
            val date = now.toLocalDate().plusDays(offset)
            repo.blocksForDate(date).firstOrNull { it.start.isAfter(now) }?.let { return it }
        }
        return null
    }

    /** 下一次需要切换状态的时间点 */
    private fun nextTransition(repo: CourseRepository, now: LocalDateTime): LocalDateTime? {
        currentBlock(repo, now)?.let { return it.end }
        nextBlock(repo, now)?.let { return it.start }
        return null
    }

    /** 核心：根据当前时刻应用/恢复各模式、刷新常驻通知、安排下一次切换 */
    fun apply(context: Context, repo: CourseRepository = CourseRepository(context)) {
        ensureChannels(context)
        val settings = repo.settings
        val now = LocalDateTime.now()
        val state = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val anyMode = settings.classSilent || settings.classDnd || settings.classAirplane
        val inClass = anyMode && currentBlock(repo, now) != null
        val applied = state.getBoolean("applied", false)

        if (inClass && !applied) enterClass(context, settings, state)
        else if (!inClass && applied) exitClass(context, state)
        else if (inClass) {
            // 用户中途打开/关闭了某一项：补应用新开启的项
            enterClass(context, settings, state, onlyNew = true)
        }

        updateStatusNotification(context, repo, now)

        // 安排下一次切换
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, REQ, Intent(context, ClassModeReceiver::class.java).setAction(ACTION_TICK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val need = anyMode || settings.persistentNotification
        val next = if (need) nextTransition(repo, now) else null
        if (next == null) { am.cancel(pi); return }
        val trigger = next.plusSeconds(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (ReminderScheduler.canScheduleExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        }
    }

    private fun enterClass(context: Context, s: AppSettings, state: android.content.SharedPreferences, onlyNew: Boolean = false) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val e = state.edit()
        if (s.classSilent && !(onlyNew && state.contains("prevRinger"))) {
            runCatching {
                e.putInt("prevRinger", audio.ringerMode)
                // 静音需要免打扰访问权限；未授权时退化为震动
                audio.ringerMode = if (hasPolicyAccess(context)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_VIBRATE
            }
        }
        if (s.classDnd && hasPolicyAccess(context) && !(onlyNew && state.contains("prevFilter"))) {
            runCatching {
                e.putInt("prevFilter", nm.currentInterruptionFilter)
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            }
        }
        if (s.classAirplane && !(onlyNew && state.contains("airplaneDone"))) {
            e.putBoolean("airplaneDone", true)
            setAirplane(context, true)
        }
        e.putBoolean("applied", true).apply()
    }

    private fun exitClass(context: Context, state: android.content.SharedPreferences) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (state.contains("prevRinger")) runCatching { audio.ringerMode = state.getInt("prevRinger", AudioManager.RINGER_MODE_NORMAL) }
        if (state.contains("prevFilter") && hasPolicyAccess(context)) {
            runCatching { nm.setInterruptionFilter(state.getInt("prevFilter", NotificationManager.INTERRUPTION_FILTER_ALL)) }
        }
        if (state.getBoolean("airplaneDone", false)) setAirplane(context, false)
        state.edit().clear().apply()
    }

    private fun setAirplane(context: Context, on: Boolean) {
        if (hasSecureSettings(context)) {
            runCatching {
                Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, if (on) 1 else 0)
                @Suppress("DEPRECATION")
                context.sendBroadcast(Intent(Intent.ACTION_AIRPLANE_MODE_CHANGED).putExtra("state", on))
            }.onSuccess { return }
        }
        // 无权限：提示用户手动切换（点击直达飞行模式设置）
        if (!ReminderScheduler.hasNotificationPermission(context)) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            context, 3, Intent(Settings.ACTION_AIRPLANE_MODE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_AIRPLANE)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(if (on) "上课了，点击开启飞行模式" else "下课了，点击关闭飞行模式")
            .setContentText("应用未获得系统授权，无法自动切换。可用 adb 授权后实现全自动。")
            .setAutoCancel(true).setContentIntent(pi).build()
        nm.notify(AIRPLANE_HINT_ID, n)
    }

    // ---------- 通知栏常驻 ----------

    fun buildStatusNotification(context: Context, repo: CourseRepository, now: LocalDateTime = LocalDateTime.now()): Notification {
        ensureChannels(context)
        val cur = currentBlock(repo, now)
        val next = nextBlock(repo, now)
        val title: String
        val body: String
        if (cur != null) {
            val c = cur.courses.first()
            title = "正在上课：${c.name}"
            body = listOf("${cur.start.format(timeFmt)}–${cur.end.format(timeFmt)}", c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ") +
                    (next?.takeIf { it.start.toLocalDate() == now.toLocalDate() }?.let { "\n下一节 ${it.start.format(timeFmt)} ${it.courses.first().name}" } ?: "")
        } else if (next != null) {
            val c = next.courses.first()
            val day = when (val d = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), next.start.toLocalDate())) {
                0L -> ""; 1L -> "明天 "; else -> "${d}天后 "
            }
            title = "下一节：$day${next.start.format(timeFmt)} ${c.name}"
            val todayLeft = repo.blocksForDate(now.toLocalDate()).count { it.start.isAfter(now) }
            body = listOf(c.location, c.teacher).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (todayLeft > 0) "\n今天还有 $todayLeft 节课" else "\n今天没有课了"
        } else {
            title = "近期没有课程"; body = "第 ${repo.currentWeek(LocalDate.now())} 周 · 享受假期吧"
        }
        val open = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle(title).setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false).setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(open).build()
    }

    fun updateStatusNotification(context: Context, repo: CourseRepository, now: LocalDateTime = LocalDateTime.now()) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!repo.settings.persistentNotification) {
            runCatching { context.stopService(Intent(context, StatusService::class.java)) }
            nm.cancel(STATUS_NOTIFICATION_ID)
            return
        }
        if (!ReminderScheduler.hasNotificationPermission(context)) return
        if (StatusService.running) {
            nm.notify(STATUS_NOTIFICATION_ID, buildStatusNotification(context, repo, now))
        } else {
            runCatching {
                val i = Intent(context, StatusService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i)
            }.onFailure {
                // 后台无法启动前台服务时，退化为普通常驻通知
                nm.notify(STATUS_NOTIFICATION_ID, buildStatusNotification(context, repo, now))
            }
        }
    }
}

class ClassModeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            runCatching { ClassModeManager.apply(context) }
            runCatching { WidgetUpdater.updateAll(context) }
            pending.finish()
        }.start()
    }
}

/** 前台服务：让常驻通知不可被划掉，并在系统清理后台时保持存活 */
class StatusService : Service() {
    companion object { @Volatile var running = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = CourseRepository(applicationContext)
        val n = ClassModeManager.buildStatusNotification(this, repo)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ClassModeManager.STATUS_NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else startForeground(ClassModeManager.STATUS_NOTIFICATION_ID, n)
        running = true
        if (!repo.settings.persistentNotification) { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }
}
