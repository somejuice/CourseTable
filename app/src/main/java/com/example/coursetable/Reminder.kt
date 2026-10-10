package com.example.coursetable

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 上课提醒调度：始终只安排"下一次"提醒，触发后再安排下一次；
 * 课程、作息时间、设置变化以及开机后都会重新计算。
 */
object ReminderScheduler {
    const val CHANNEL_NOTIFY = "course_reminder_notify"
    const val CHANNEL_ALARM = "course_reminder_alarm"
    private const val REQUEST_CODE = 1001

    const val EXTRA_NAME = "name"
    const val EXTRA_LOCATION = "location"
    const val EXTRA_TEACHER = "teacher"
    const val EXTRA_TIME = "time"
    const val EXTRA_LEAD = "lead"
    const val EXTRA_MODE = "mode"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_NOTIFY) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_NOTIFY, "上课通知提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "以普通通知方式提醒即将上课"
                    enableVibration(true)
                }
            )
        }
        if (nm.getNotificationChannel(CHANNEL_ALARM) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ALARM, "上课闹钟提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "以闹钟方式（响铃 + 全屏）提醒即将上课"
                    enableVibration(true)
                    setBypassDnd(true)
                    setSound(
                        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
            )
        }
    }

    fun canScheduleExact(context: Context): Boolean {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun hasNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pendingIntent(context: Context, target: CourseRepository.ReminderTarget?, settings: AppSettings): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = "com.example.coursetable.REMIND"
            if (target != null) {
                val (s, _) = PeriodTimes.of(target.course.startPeriod)
                val (_, e) = PeriodTimes.of(target.course.endPeriod)
                putExtra(EXTRA_NAME, target.course.name)
                putExtra(EXTRA_LOCATION, target.course.location)
                putExtra(EXTRA_TEACHER, target.course.teacher)
                putExtra(EXTRA_TIME, "$s - $e")
                putExtra(EXTRA_LEAD, settings.reminderLeadMinutes)
                putExtra(EXTRA_MODE, settings.reminderMode)
            }
        }
        return PendingIntent.getBroadcast(
            context, REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 每天 0 点刷新桌面小组件 */
    private fun scheduleWidgetRefresh(context: Context, am: AlarmManager) {
        val intent = Intent(context, BootReceiver::class.java).apply { action = ACTION_REFRESH_WIDGETS }
        val pi = PendingIntent.getBroadcast(context, 1002, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val midnight = java.time.LocalDate.now().plusDays(1).atStartOfDay().plusSeconds(5)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC, midnight, pi) }
    }

    const val ACTION_REFRESH_WIDGETS = "com.example.coursetable.REFRESH_WIDGETS"

    fun schedule(context: Context, repo: CourseRepository = CourseRepository(context)) {
        ensureChannels(context)
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        scheduleWidgetRefresh(context, am)
        runCatching { ClassModeManager.apply(context, repo) }
        val settings = repo.settings
        val target = repo.nextReminder()
        if (!settings.reminderEnabled || target == null) {
            am.cancel(pendingIntent(context, null, settings))
            return
        }
        val triggerMillis = target.triggerAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(context, target, settings)
        try {
            if (canScheduleExact(context)) {
                // 闹钟模式使用 setAlarmClock（系统状态栏显示闹钟图标，优先级最高）；通知模式使用精确唤醒
                if (settings.reminderMode == 1) {
                    val showIntent = PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerMillis, showIntent), pi)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
                }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerMillis, pi)
        }
    }

    fun describeNext(repo: CourseRepository): String? {
        val t = repo.nextReminder() ?: return null
        val fmt = DateTimeFormatter.ofPattern("M月d日 HH:mm")
        return "下一次：${t.triggerAt.format(fmt)} 提醒「${t.course.name}」（${t.startAt.format(DateTimeFormatter.ofPattern("HH:mm"))} 上课）"
    }

    fun showReminder(context: Context, intent: Intent) {
        ensureChannels(context)
        val name = intent.getStringExtra(EXTRA_NAME) ?: return
        val location = intent.getStringExtra(EXTRA_LOCATION).orEmpty()
        val teacher = intent.getStringExtra(EXTRA_TEACHER).orEmpty()
        val time = intent.getStringExtra(EXTRA_TIME).orEmpty()
        val lead = intent.getIntExtra(EXTRA_LEAD, 10)
        val mode = intent.getIntExtra(EXTRA_MODE, 0)

        val title = "「$name」$lead 分钟后上课"
        val body = listOf(time, location, teacher).filter { it.isNotBlank() }.joinToString(" · ")

        val openApp = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(context, if (mode == 1) CHANNEL_ALARM else CHANNEL_NOTIFY)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(if (mode == 1) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setContentIntent(openApp)

        if (mode == 1) {
            val alarmIntent = Intent(context, AlarmActivity::class.java).apply {
                putExtras(intent)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val fullScreen = PendingIntent.getActivity(
                context, 2, alarmIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.setFullScreenIntent(fullScreen, true)
                .setContentIntent(fullScreen)
                .setOngoing(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        }

        if (hasNotificationPermission(context)) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(name.hashCode(), builder.build())
        }
        if (mode == 1) {
            // 直接拉起闹钟界面（部分系统在亮屏时不会自动展示全屏意图）
            runCatching {
                context.startActivity(Intent(context, AlarmActivity::class.java).apply {
                    putExtras(intent)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                })
            }
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler.showReminder(context, intent)
        // 安排下一次
        ReminderScheduler.schedule(context)
        runCatching { WidgetUpdater.updateAll(context) }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED ||
            intent.action == ReminderScheduler.ACTION_REFRESH_WIDGETS
        ) {
            ReminderScheduler.schedule(context)
            runCatching { WidgetUpdater.updateAll(context) }
            runCatching { ClassModeManager.apply(context) }
        }
    }
}

/** 闹钟全屏界面：响铃 + 震动，直到用户点击"知道了" */
class AlarmActivity : ComponentActivity() {
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true); setTurnScreenOn(true)
        }
        val name = intent.getStringExtra(ReminderScheduler.EXTRA_NAME).orEmpty()
        val location = intent.getStringExtra(ReminderScheduler.EXTRA_LOCATION).orEmpty()
        val teacher = intent.getStringExtra(ReminderScheduler.EXTRA_TEACHER).orEmpty()
        val time = intent.getStringExtra(ReminderScheduler.EXTRA_TIME).orEmpty()
        val lead = intent.getIntExtra(ReminderScheduler.EXTRA_LEAD, 10)

        startRinging()

        val repo = CourseRepository(applicationContext)
        setContent {
            CourseTableTheme(settings = repo.settings) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("⏰ 上课提醒", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(16.dp))
                        Text(name, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(12.dp))
                        Text("$lead 分钟后上课", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(8.dp))
                        Text(listOf(time, location, teacher).filter { it.isNotBlank() }.joinToString("\n"),
                            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Spacer(Modifier.height(48.dp))
                        Button(onClick = { stopRinging(); finish() }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                            Text("知道了", style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
        }
    }

    private fun startRinging() {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                )
                setDataSource(this@AlarmActivity, uri)
                isLooping = true
                prepare(); start()
            }
        }
        runCatching {
            vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION") getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400, 600, 400), 0))
        }
    }

    private fun stopRinging() {
        runCatching { player?.stop(); player?.release() }; player = null
        runCatching { vibrator?.cancel() }
        val name = intent.getStringExtra(ReminderScheduler.EXTRA_NAME).orEmpty()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(name.hashCode())
    }

    override fun onDestroy() {
        stopRinging()
        super.onDestroy()
    }
}

@Suppress("unused")
private val NotificationRef = Notification::class
