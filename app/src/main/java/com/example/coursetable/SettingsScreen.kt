package com.example.coursetable

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.catalog.components.LiquidButton
import com.kyant.backdrop.catalog.components.LiquidToggle
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import java.time.format.DateTimeFormatter
import android.graphics.Bitmap
import android.content.ContentValues
import android.provider.MediaStore
import android.app.Activity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(repo: CourseRepository, contentPadding: PaddingValues) {
    val settings = repo.settings
    // 设置页的玻璃控件位于页面 layerBackdrop 层内部，不能再读取同一个 backdrop（会递归绘制导致崩溃）。
    // 按上游 ToggleContent 的做法，使用卡片底色的 CanvasBackdrop。
    val cardColor = MaterialTheme.colorScheme.surfaceContainer
    val backdrop: Backdrop = rememberCanvasBackdrop { drawRect(cardColor) }
    var showDatePicker by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    // 正在编辑的节次时间：Pair(节次, 是否为开始时间)
    var editingTime by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    val context = LocalContext.current

    // 通知权限（Android 13+）
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(context, "未授予通知权限，提醒可能无法显示", Toast.LENGTH_LONG).show()
        repo.updateSettings { it }
    }
    // 导出 / 导入
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { it.write(repo.exportJson().toByteArray()) }
            }.onSuccess { Toast.makeText(context, "已导出 ${repo.courses.size} 门课程", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    var pendingImport by remember { mutableStateOf<String?>(null) }
    val wallpaperLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val name = "wallpaper_${System.currentTimeMillis()}.img"
                context.contentResolver.openInputStream(uri)!!.use { input -> java.io.File(context.filesDir, name).outputStream().use { input.copyTo(it) } }
                val old = repo.settings.wallpaper
                if (old.isNotBlank()) java.io.File(context.filesDir, old).delete()
                repo.updateSettings { it.copy(wallpaper = name) }
            }.onFailure { Toast.makeText(context, "设置壁纸失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    if (page != null) BackHandler { page = null }
    val pageTitles = remember {
        mapOf("semester" to "学期与周数", "periods" to "节次时间", "selfstudy" to "早晚自习", "holiday" to "假期与调休",
            "reminder" to "上课提醒", "classmode" to "上课自动模式", "status" to "通知栏常驻", "dday" to "D-Day 倒计时",
            "share" to "面对面共享", "io" to "导出与导入", "jw" to "教务系统导入", "data" to "数据管理", "appearance" to "外观", "advanced" to "高级", "about" to "关于")
    }
    var selfStudyEdit by remember { mutableStateOf<Boolean?>(null) }
    // 教务系统导入 + 规则管理
    val webImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(WebImportActivity.EXTRA_SEMESTER_START)?.let { d ->
                runCatching { java.time.LocalDate.parse(d) }.getOrNull()?.let { date -> repo.updateSettings { it.copy(semesterStart = date.with(java.time.DayOfWeek.MONDAY)) } }
            }
            result.data?.getStringExtra(WebImportActivity.EXTRA_JSON)?.let { pendingImport = it }
        }
    }
    var rulesTick by remember { mutableStateOf(0) }
    val allRules = remember(rulesTick) { RuleEngine.allRules(context) }
    var showRuleDoc by remember { mutableStateOf(false) }
    var ruleUrlDialog by remember { mutableStateOf(false) }
    val ruleFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: error("读取失败")
                val rules = RuleEngine.parseRuleText(text)
                rules.forEach { RuleEngine.addCustom(context, it) }
                rules.size
            }.onSuccess { n -> rulesTick++; Toast.makeText(context, "已导入 $n 条规则", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "规则无效：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    val ruleExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val arr = org.json.JSONArray().also { a -> RuleEngine.builtinRules(context).forEach { a.put(it.toJson()) } }
                context.contentResolver.openOutputStream(uri)?.use { it.write(arr.toString(2).toByteArray()) }
            }.onSuccess { Toast.makeText(context, "已导出内置规则模板", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    // ICS 导出 / 导入
    val icsExportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(Ics.export(repo).toByteArray()) } }
                .onSuccess { Toast.makeText(context, "已导出 ICS 日历文件", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "导出失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    var pendingIcs by remember { mutableStateOf<Ics.Parsed?>(null) }
    val icsImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }?.let { Ics.parse(it, repo) } }
                .onSuccess { r -> if (r == null || (r.courses.isEmpty() && r.ddays.isEmpty())) Toast.makeText(context, "未在文件中找到可识别的课程", Toast.LENGTH_LONG).show() else pendingIcs = r }
                .onFailure { Toast.makeText(context, "读取失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    // 面对面共享：多色二维码
    var showQr by remember { mutableStateOf(false) }
    val scanLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra(ScanActivity.EXTRA_JSON)?.let { pendingImport = it }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
            }.onSuccess { text -> if (text != null) pendingImport = text }
                .onFailure { Toast.makeText(context, "读取失败：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = initialState == null && targetState != null
            val backward = initialState != null && targetState == null
            val spec = tween<IntOffset>(320, easing = FastOutSlowInEasing)
            val fade = tween<Float>(220)
            when {
                forward -> (slideInHorizontally(spec) { it / 3 } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { -it / 4 } + fadeOut(fade))
                backward -> (slideInHorizontally(spec) { -it / 4 } + fadeIn(fade)) togetherWith
                        (slideOutHorizontally(spec) { it / 3 } + fadeOut(fade))
                else -> fadeIn(fade) togetherWith fadeOut(fade)
            }.using(SizeTransform(clip = false))
        },
        label = "settingsPages"
    ) { p ->
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (p == null) {
            Text("设置", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))
            Text(
                "第 ${repo.currentWeek(java.time.LocalDate.now())} 周 · ${repo.courses.size} 门课程 · 共 ${settings.totalWeeks} 周",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            HubGroup("课表") {
                HubItem(Icons.Filled.DateRange, "学期与周数", "开学 ${settings.semesterStart} · ${settings.totalWeeks} 周 · 每天 ${settings.periodsPerDay} 节") { page = "semester" }
                HubItem(Icons.Filled.Refresh, "节次时间", "第 1 节 ${PeriodTimes.of(1).first} 开始，可逐节修改") { page = "periods" }
                HubItem(Icons.Filled.Star, "早晚自习", listOfNotNull(if (settings.morning.enabled) "早自习 ${settings.morning.start}" else null, if (settings.evening.enabled) "晚自习 ${settings.evening.start}" else null).ifEmpty { listOf("未开启") }.joinToString(" · ")) { page = "selfstudy" }
                HubItem(Icons.Filled.Place, "假期与调休", if (settings.holidayAuto) "自动标注法定假期（${repo.customAdjusts.size} 项自定义）" else "已关闭自动标注") { page = "holiday" }
            }
            HubGroup("提醒与自动化") {
                HubItem(Icons.Filled.Notifications, "上课提醒", if (settings.reminderEnabled) "提前 ${settings.reminderLeadMinutes} 分钟 · ${if (settings.reminderMode == 1) "闹钟" else "通知"}" else "未开启") { page = "reminder" }
                HubItem(Icons.Filled.Lock, "上课自动模式", listOfNotNull(if (settings.classSilent) "静音" else null, if (settings.classDnd) "免打扰" else null, if (settings.classAirplane) "飞行模式" else null).ifEmpty { listOf("未开启") }.joinToString(" · ")) { page = "classmode" }
                HubItem(Icons.Filled.Info, "通知栏常驻", if (settings.persistentNotification) "已开启" else "未开启") { page = "status" }
            }
            HubGroup("倒计时") {
                HubItem(Icons.Filled.CheckCircle, "D-Day 倒计时", repo.ddays.toList().sortedBy { it.date }.firstOrNull { it.daysLeft() >= 0 }?.let { "${it.name} 还有 ${it.daysLeft()} 天" } ?: "期末考、四六级、考研…") { page = "dday" }
            }
            HubGroup("数据") {
                HubItem(Icons.Filled.Home, "教务系统导入", "内置浏览器登录教务系统一键抓取 · ${allRules.size} 条解析规则") { page = "jw" }
                HubItem(Icons.Filled.Share, "面对面共享", "多色二维码 · 生成 / 扫码导入") { page = "share" }
                HubItem(Icons.Filled.Send, "导出与导入", "JSON 备份 · ICS 日历文件") { page = "io" }
                HubItem(Icons.Filled.Delete, "数据管理", "清空课程 / 重置设置") { page = "data" }
            }
            HubGroup("其他") {
                HubItem(Icons.Filled.Face, "外观", (if (settings.dynamicColor) "莫奈动态取色" else "默认配色") + if (settings.wallpaper.isNotBlank()) " · 自定义壁纸" else "") { page = "appearance" }
                HubItem(Icons.Filled.Build, "高级", "编辑课表 / 空闲教室抓取脚本 · 自定义规则") { page = "advanced" }
                HubItem(Icons.Filled.Info, "关于", "大学课表 v1.0.0 · `拾星*") { page = "about" }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                IconButton(onClick = { page = null }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "返回") }
                Text(pageTitles[p] ?: "", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            when (p) {
            "semester" -> {
        SettingsCard("学期") {
            SettingsItem(
                title = "开学日期（第一周周一）",
                subtitle = settings.semesterStart.toString(),
                onClick = { showDatePicker = true }
            )
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("学期周数：${settings.totalWeeks} 周", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.totalWeeks.toFloat(),
                    onValueChange = { v -> repo.updateSettings { it.copy(totalWeeks = v.toInt().coerceIn(10, 30)) } },
                    valueRange = 10f..30f, steps = 19
                )
            }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Text("每天节数：${settings.periodsPerDay} 节", style = MaterialTheme.typography.bodyLarge)
                Slider(
                    value = settings.periodsPerDay.toFloat(),
                    onValueChange = { v -> repo.updateSettings { it.copy(periodsPerDay = v.toInt().coerceIn(6, 14)) } },
                    valueRange = 6f..14f, steps = 7
                )
            }
            HorizontalDivider()
            SwitchItem("显示周末", settings.showWeekend, backdrop = backdrop) { v -> repo.updateSettings { it.copy(showWeekend = v) } }
        }
            }
            "periods" -> {
        SettingsCard("节次时间（点击修改 时:分）") {
            Column(Modifier.padding(vertical = 6.dp)) {
                for (p in 1..settings.periodsPerDay) {
                    val (start, end) = PeriodTimes.of(p)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("第 $p 节", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        SuggestionChip(onClick = { editingTime = p to true }, label = { Text(start) })
                        Text("  –  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SuggestionChip(onClick = { editingTime = p to false }, label = { Text(end) })
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                OutlinedButton(
                    onClick = { repo.resetPeriodTimes() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                ) { Text("恢复默认作息时间") }
            }
        }
            }
            "holiday" -> {
        SettingsCard("假期与调休") {
            SwitchItem(
                title = "自动标注法定假期 / 调休",
                subtitle = "内置 2025–2026 年国务院放假安排：放假日不显示课程，调休上班日自动显示补课日课表",
                checked = settings.holidayAuto, backdrop = backdrop
            ) { v -> repo.updateSettings { it.copy(holidayAuto = v) } }
            HorizontalDivider()
            val customs = repo.customAdjusts.toList().sortedBy { it.date }
            Text(
                if (customs.isEmpty()) "在课表页点击任意日期可手动设置放假 / 调休（如校运会、补课）" else "自定义 ${customs.size} 天：" +
                        customs.take(6).joinToString("、") { it.date.format(java.time.format.DateTimeFormatter.ofPattern("M.d")) + (if (it.holiday) "休" else "班") } +
                        (if (customs.size > 6) "…" else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
            )
            if (customs.isNotEmpty()) {
                HorizontalDivider()
                SettingsItem("清除全部自定义日期", danger = true) { customs.forEach { repo.removeCustomAdjust(it.date) } }
            }
        }
            }
            "reminder" -> {
        SettingsCard("上课提醒") {
            SwitchItem(
                title = "开启上课提醒",
                subtitle = ReminderScheduler.describeNext(repo) ?: if (settings.reminderEnabled) "暂无即将到来的课程" else "按提前时间在上课前提醒",
                checked = settings.reminderEnabled,
                backdrop = backdrop
            ) { v ->
                repo.updateSettings { it.copy(reminderEnabled = v) }
                if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderScheduler.hasNotificationPermission(context)) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            if (settings.reminderEnabled) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("提醒方式", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("通知提醒", "闹钟提醒").forEachIndexed { i, label ->
                            SegmentedButton(
                                selected = settings.reminderMode == i,
                                onClick = { repo.updateSettings { it.copy(reminderMode = i) } },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 2)
                            ) { Text(label) }
                        }
                    }
                    Text(
                        if (settings.reminderMode == 1) "闹钟：全屏界面 + 持续响铃震动，直到点击“知道了”" else "通知：横幅通知 + 一次提示音",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text("提前 ${settings.reminderLeadMinutes} 分钟提醒", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        listOf(5, 10, 15, 30).forEach { m ->
                            SuggestionChip(
                                onClick = { repo.updateSettings { it.copy(reminderLeadMinutes = m) } },
                                label = { Text("${m}分") },
                                colors = if (settings.reminderLeadMinutes == m)
                                    androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    ) else androidx.compose.material3.SuggestionChipDefaults.suggestionChipColors()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    var customLead by remember(settings.reminderLeadMinutes) { mutableStateOf(settings.reminderLeadMinutes.toString()) }
                    OutlinedTextField(
                        value = customLead,
                        onValueChange = { v ->
                            customLead = v.filter { it.isDigit() }.take(3)
                            customLead.toIntOrNull()?.let { n -> if (n in 1..180) repo.updateSettings { it.copy(reminderLeadMinutes = n) } }
                        },
                        label = { Text("自定义（1–180 分钟）") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (!ReminderScheduler.canScheduleExact(context)) {
                    HorizontalDivider()
                    SettingsItem("允许精确闹钟", "系统未授权精确闹钟，提醒时间可能有延迟，点击前往授权") {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            runCatching {
                                context.startActivity(Intent(AndroidSettings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                            }
                        }
                    }
                }
            }
        }
            }
            "classmode" -> {
        SettingsCard("上课自动模式") {
            // 从系统设置返回后刷新权限状态
            var permTick by remember { mutableStateOf(0) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permTick++ }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            val policyOk = remember(permTick) { ClassModeManager.hasPolicyAccess(context) }
            val secureOk = remember(permTick) { ClassModeManager.hasSecureSettings(context) }
            fun ensureNotif() {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderScheduler.hasNotificationPermission(context)) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            SwitchItem(
                title = "上课自动静音",
                subtitle = if (policyOk) "上课时切换为静音，下课后恢复原铃声模式" else "未授权免打扰访问时只能切换为震动，建议授权",
                checked = settings.classSilent, backdrop = backdrop
            ) { v -> repo.updateSettings { it.copy(classSilent = v) } }
            HorizontalDivider()
            SwitchItem(
                title = "上课自动免打扰",
                subtitle = if (policyOk) "上课时开启“仅优先”免打扰，下课后恢复" else "需要授予免打扰访问权限",
                checked = settings.classDnd, backdrop = backdrop
            ) { v -> repo.updateSettings { it.copy(classDnd = v) } }
            HorizontalDivider()
            SwitchItem(
                title = "上课自动飞行模式",
                subtitle = if (secureOk) "已获系统授权，上下课自动开关飞行模式" else "系统限制：未授权时上下课会发通知，点一下即可手动切换",
                checked = settings.classAirplane, backdrop = backdrop
            ) { v -> repo.updateSettings { it.copy(classAirplane = v) }; if (v) ensureNotif() }
            if ((settings.classSilent || settings.classDnd) && !policyOk) {
                HorizontalDivider()
                SettingsItem("授予免打扰访问权限", "静音 / 免打扰需要此权限，点击前往系统设置开启") {
                    runCatching { context.startActivity(Intent(AndroidSettings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                }
            }
            if (settings.classAirplane && !secureOk) {
                HorizontalDivider()
                SettingsItem("实现全自动飞行模式（可选）", "电脑连接手机后执行：adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS，点击复制命令") {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", "adb shell pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS"))
                    Toast.makeText(context, "已复制 adb 命令", Toast.LENGTH_SHORT).show()
                }
            }
            if (settings.classSilent || settings.classDnd || settings.classAirplane) {
                HorizontalDivider()
                val cur = ClassModeManager.currentBlock(repo)
                val next = ClassModeManager.nextBlock(repo)
                Text(
                    when {
                        cur != null -> "当前正在上课（${cur.courses.first().name}），模式已生效，${cur.end.toLocalTime()} 恢复"
                        next != null -> "下一次生效：${next.start.format(java.time.format.DateTimeFormatter.ofPattern("M月d日 HH:mm"))}（${next.courses.first().name}）"
                        else -> "近期没有课程"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
            }
            "status" -> {
        SettingsCard("通知栏常驻") {
            SwitchItem(
                title = "常驻显示课程",
                subtitle = "在通知栏固定显示正在上的课 / 下一节课，自动随课程进度更新",
                checked = settings.persistentNotification, backdrop = backdrop
            ) { v ->
                repo.updateSettings { it.copy(persistentNotification = v) }
                if (v && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !ReminderScheduler.hasNotificationPermission(context)) {
                    notifPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
                Thread { runCatching { ClassModeManager.apply(context, repo) } }.start()
            }
        }
            }
            "share" -> {
        SettingsCard("面对面共享（多色二维码）") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "采用 R/G/B 三通道叠加的多色二维码，容量为普通二维码的 3 倍，一张码即可装下整学期课表。对方用本应用“扫码导入”即可一键复制你的课表。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = { showQr = true },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    ) { Text("生成二维码", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    LiquidButton(
                        onClick = { scanLauncher.launch(Intent(context, ScanActivity::class.java)) },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    ) { Text("扫码导入", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
            }
            "io" -> {
        SettingsCard("导出 / 导入") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("以 JSON 文件备份或迁移课程、作息时间与学期设置", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = {
                            val name = "课表_" + java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".json"
                            exportLauncher.launch(name)
                        },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    ) { Text("导出课表", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    LiquidButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    ) { Text("导入课表", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        SettingsCard("ICS 日历") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("导出为标准 iCalendar 文件，可导入系统日历 / Google 日历 / Outlook；已自动排除放假日、包含调休补课、早晚自习与倒计时。也可从教务系统或其他课表应用导出的 .ics 导入。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = { icsExportLauncher.launch("课表_" + java.time.LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) + ".ics") },
                        backdrop = backdrop, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f)
                    ) { Text("导出 ICS", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                    LiquidButton(
                        onClick = { icsImportLauncher.launch(arrayOf("text/calendar", "application/octet-stream", "text/plain", "*/*")) },
                        backdrop = backdrop, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.weight(1f)
                    ) { Text("导入 ICS", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
            }
            "data" -> {
        SettingsCard("数据") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前共 ${repo.courses.size} 门课程", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    LiquidButton(
                        onClick = { confirmReset = true },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("恢复示例", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                    LiquidButton(
                        onClick = { confirmClear = true },
                        backdrop = backdrop,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Text("清空课程", color = Color.White, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
            }
            "appearance" -> {
        SettingsCard("外观") {
            SwitchItem(
                title = "莫奈动态取色",
                subtitle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "跟随系统壁纸生成 Material You 主题" else "需要 Android 12 及以上",
                checked = settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
                backdrop = backdrop
            ) { v -> repo.updateSettings { it.copy(dynamicColor = v) } }
            HorizontalDivider()
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("深色模式", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("跟随系统", "浅色", "深色").forEachIndexed { i, label ->
                        SegmentedButton(
                            selected = settings.darkMode == i,
                            onClick = { repo.updateSettings { it.copy(darkMode = i) } },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = 3)
                        ) { Text(label) }
                    }
                }
            }
        }
        SettingsCard("壁纸") {
            SettingsItem("选择壁纸", if (settings.wallpaper.isNotBlank()) "已设置 · 点击更换" else "从相册选择一张图片作为应用背景") {
                wallpaperLauncher.launch(arrayOf("image/*"))
            }
            if (settings.wallpaper.isNotBlank()) {
                HorizontalDivider()
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("遮罩不透明度：${(settings.wallpaperDim * 100).toInt()}%", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = settings.wallpaperDim, onValueChange = { v -> repo.updateSettings { it.copy(wallpaperDim = v) } }, valueRange = 0f..0.9f)
                    Text("模糊：${settings.wallpaperBlur} dp", style = MaterialTheme.typography.bodyLarge)
                    Slider(value = settings.wallpaperBlur.toFloat(), onValueChange = { v -> repo.updateSettings { it.copy(wallpaperBlur = v.toInt()) } }, valueRange = 0f..40f, steps = 7)
                }
                HorizontalDivider()
                SettingsItem("移除壁纸", "恢复纯色背景") {
                    java.io.File(context.filesDir, settings.wallpaper).delete()
                    repo.updateSettings { it.copy(wallpaper = "") }
                }
            }
        }
            }
            "advanced" -> {
        SettingsCard("脚本编辑") {
            SettingsItem("编辑课表抓取脚本", "修改任意规则的 script（async 函数体），保存后以自定义规则覆盖") {
                context.startActivity(Intent(context, ScriptEditorActivity::class.java).putExtra(ScriptEditorActivity.EXTRA_FIELD, "script"))
            }
            HorizontalDivider()
            SettingsItem("编辑空闲教室脚本", "修改 freeRoomScript，为本校教务系统添加空闲教室查询") {
                context.startActivity(Intent(context, ScriptEditorActivity::class.java).putExtra(ScriptEditorActivity.EXTRA_FIELD, "freeRoomScript"))
            }
            HorizontalDivider()
            SettingsItem("新建空白规则", "从模板创建一条新规则（id / 名称 / 匹配网址 / 脚本）") {
                context.startActivity(Intent(context, ScriptEditorActivity::class.java).putExtra(ScriptEditorActivity.EXTRA_NEW, true))
            }
        }
        SettingsCard("说明") {
            Text("脚本在教务系统页面内以同源身份执行，可直接使用 fetch / document / jQuery。课表脚本需 return { courses:[…] }，空闲教室脚本需 return { rooms:[…] }，参数由 __p 对象传入。修改内置规则会生成同 id 的自定义规则并优先生效；删除自定义规则即可恢复内置版本。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            SettingsItem("规则编写说明", "JSON 字段、脚本约定、输出格式与调试方法") { showRuleDoc = true }
        }
            }
            "about" -> {
        SettingsCard("关于") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("大学课表 v1.0.0", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text("Jetpack Compose · Material 3 · Material You 动态取色", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("底栏、开关与按钮使用 Kyant0/AndroidLiquidGlass (Backdrop) 上游液态玻璃组件，Apache-2.0 许可。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("折射效果需要 Android 13+，低版本自动降级为模糊效果。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider()
            SettingsItem("开发者", "`拾星*") {
                (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("开发者", "`拾星*"))
                Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
            }
            HorizontalDivider()
            SettingsItem("项目地址", "https://github.com/somejuice/CourseTable") {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/somejuice/CourseTable")))
                }.onFailure {
                    (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("项目地址", "https://github.com/somejuice/CourseTable"))
                    Toast.makeText(context, "未找到浏览器，已复制链接", Toast.LENGTH_SHORT).show()
                }
            }
        }
            }
            "jw" -> {
                SettingsCard("一键导入") {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("在应用内置浏览器中登录学校教务系统（正方、强智、青果、苏亚、URP 等），进入课表页面后一键抓取并解析为课程。账号密码仅在本机与学校服务器间传输。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        LiquidButton(
                            onClick = { webImportLauncher.launch(Intent(context, WebImportActivity::class.java)) },
                            backdrop = backdrop, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth()
                        ) { Text("打开教务系统导入", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                        if (JwSession.tick.intValue.let { JwSession.isLoggedIn(context) && JwSession.rule(context)?.supportsFreeRoom == true }) LiquidButton(
                            onClick = { context.startActivity(Intent(context, FreeRoomsActivity::class.java)) },
                            backdrop = backdrop, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.fillMaxWidth()
                        ) { Text("空闲教室查询", color = Color.White, style = MaterialTheme.typography.labelLarge) }
                        Text("推荐在「我的」页面登录并绑定教务账号：之后课表刷新、空闲教室、个人信息都会在后台静默完成。空闲教室查询仅在登录后显示（内置支持正方 jwglxt，其它系统可在规则中添加 freeRoomScript）。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SettingsCard("解析规则（插件）") {
                    allRules.forEach { r ->
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(r.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOf(r.vendor, "v${r.version}", r.author, if (r.builtin) "内置" else "自定义").filter { it.isNotBlank() }.joinToString(" · ") +
                                            if (r.match.isNotEmpty()) "\n匹配：${r.match.joinToString(" ")}" else "\n手动选择使用",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (!r.builtin) TextButton(onClick = { RuleEngine.removeCustom(context, r.id); rulesTick++ }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                        }
                        HorizontalDivider()
                    }
                    SettingsItem("导入规则文件（.json）", "加载开发者 / 社区贡献的学校适配规则，同 id 覆盖内置") { ruleFileLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }
                    SettingsItem("从网址导入规则", "输入规则文件的 URL（如 GitHub Raw 链接）") { ruleUrlDialog = true }
                    SettingsItem("导出内置规则作为模板", "保存为 JSON，便于修改后适配本校") { ruleExportLauncher.launch("coursetable-rules.json") }
                    SettingsItem("规则编写说明", "JSON 字段、脚本约定、输出格式与调试方法") { showRuleDoc = true }
                }
            }
            "selfstudy" -> {
                SettingsCard("早晚自习") {
                    Text("开启后在课表首行 / 末行固定显示自习时段，可自定义名称、时间与地点。点击课表中的自习格也可编辑。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                    HorizontalDivider()
                    SwitchItem(title = "早自习", subtitle = "${settings.morning.name} ${settings.morning.start}–${settings.morning.end}" + if (settings.morning.location.isNotBlank()) " · ${settings.morning.location}" else "", checked = settings.morning.enabled, backdrop = backdrop) { v ->
                        repo.updateSettings { it.copy(morning = it.morning.copy(enabled = v)) }
                    }
                    SettingsItem("编辑早自习", "名称 / 时间 / 地点") { selfStudyEdit = true }
                    HorizontalDivider()
                    SwitchItem(title = "晚自习", subtitle = "${settings.evening.name} ${settings.evening.start}–${settings.evening.end}" + if (settings.evening.location.isNotBlank()) " · ${settings.evening.location}" else "", checked = settings.evening.enabled, backdrop = backdrop) { v ->
                        repo.updateSettings { it.copy(evening = it.evening.copy(enabled = v)) }
                    }
                    SettingsItem("编辑晚自习", "名称 / 时间 / 地点") { selfStudyEdit = false }
                    HorizontalDivider()
                    SwitchItem(title = "周末也显示", subtitle = "默认仅周一至周五显示自习；放假日始终不显示", checked = settings.selfStudyWeekend, backdrop = backdrop) { v ->
                        repo.updateSettings { it.copy(selfStudyWeekend = v) }
                    }
                }
            }
            "dday" -> {
                SettingsCard("D-Day 倒计时") { DDayList(repo) }
                Text("倒计时会显示在「今日」页顶部，7 天内的事件以醒目颜色提示；随 JSON / ICS 一并导出。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
            }
            }
        }

    }
    }

    if (showDatePicker) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = settings.semesterStart.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { ms ->
                        val date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                        repo.updateSettings { it.copy(semesterStart = date.with(java.time.DayOfWeek.MONDAY)) }
                    }
                    showDatePicker = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("取消") } }
        ) {
            DatePicker(state = state, title = { Text("选择开学日期", Modifier.padding(start = 24.dp, top = 16.dp)) })
        }
    }

    editingTime?.let { (period, isStart) ->
        val current = PeriodTimes.of(period).let { if (isStart) it.first else it.second }
        val (h, m) = current.split(":").let { it[0].toInt() to it[1].toInt() }
        val state = rememberTimePickerState(initialHour = h, initialMinute = m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editingTime = null },
            title = { Text("第 $period 节 · ${if (isStart) "上课" else "下课"}时间") },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val t = PeriodTimes.format(state.hour, state.minute)
                    if (isStart) repo.updatePeriodTime(period, start = t) else repo.updatePeriodTime(period, end = t)
                    editingTime = null
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { editingTime = null }) { Text("取消") } }
        )
    }

    if (showQr) {
        var bitmap by remember { mutableStateOf<Bitmap?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(Unit) {
            withContext(Dispatchers.Default) {
                runCatching { ColorQr.encode(ShareCodec.pack(repo), 1024) }
                    .onSuccess { bitmap = it }
                    .onFailure { error = if (it is ColorQr.TooLargeException) "课程过多，超出单张二维码容量，请改用导出文件分享" else "生成失败：${it.message}" }
            }
        }
        AlertDialog(
            onDismissRequest = { showQr = false },
            title = { Text("我的课表二维码") },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val bmp = bitmap
                    when {
                        error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                        bmp == null -> Text("正在生成…")
                        else -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "课表二维码",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                                .background(Color.White)
                                .padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "包含 ${repo.courses.size} 门课程 · 对方在「设置 → 扫码导入」中扫描\n屏幕亮度调高、避免反光可提高识别率",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Row {
                    TextButton(enabled = bitmap != null, onClick = {
                        val bmp = bitmap ?: return@TextButton
                        runCatching {
                            val values = ContentValues().apply {
                                put(MediaStore.Images.Media.DISPLAY_NAME, "课表二维码_${System.currentTimeMillis()}.png")
                                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CourseTable")
                            }
                            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                            context.contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        }.onSuccess { Toast.makeText(context, "已保存到相册 Pictures/CourseTable", Toast.LENGTH_SHORT).show() }
                            .onFailure { Toast.makeText(context, "保存失败：${it.message}", Toast.LENGTH_LONG).show() }
                    }) { Text("保存") }
                    TextButton(enabled = bitmap != null, onClick = {
                        val bmp = bitmap ?: return@TextButton
                        runCatching {
                            val dir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                            val file = java.io.File(dir, "course_qr.png")
                            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "image/png"; putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(send, "分享课表二维码"))
                        }.onFailure { Toast.makeText(context, "分享失败：${it.message}", Toast.LENGTH_LONG).show() }
                    }) { Text("分享") }
                    TextButton(onClick = { showQr = false }) { Text("关闭") }
                }
            }
        )
    }

    if (showRuleDoc) {
        AlertDialog(
            onDismissRequest = { showRuleDoc = false },
            title = { Text("规则编写说明") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(RuleEngine.RULE_DOC, style = MaterialTheme.typography.bodySmall) } },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("rules", RuleEngine.RULE_DOC))
                        Toast.makeText(context, "已复制说明", Toast.LENGTH_SHORT).show()
                    }) { Text("复制") }
                    TextButton(onClick = { showRuleDoc = false }) { Text("关闭") }
                }
            }
        )
    }
    if (ruleUrlDialog) {
        var url by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { if (!loading) ruleUrlDialog = false },
            title = { Text("从网址导入规则") },
            text = { OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("https://…/rule.json") }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = {
                TextButton(enabled = url.isNotBlank() && !loading, onClick = {
                    loading = true
                    Thread {
                        val r = runCatching {
                            val conn = java.net.URL(url.trim()).openConnection() as java.net.HttpURLConnection
                            conn.connectTimeout = 10000; conn.readTimeout = 15000
                            val text = conn.inputStream.bufferedReader().readText()
                            RuleEngine.parseRuleText(text).also { list -> list.forEach { RuleEngine.addCustom(context, it) } }.size
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            loading = false
                            r.onSuccess { n -> rulesTick++; ruleUrlDialog = false; Toast.makeText(context, "已导入 $n 条规则", Toast.LENGTH_SHORT).show() }
                                .onFailure { Toast.makeText(context, "导入失败：${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }.start()
                }) { Text(if (loading) "下载中…" else "导入") }
            },
            dismissButton = { TextButton(onClick = { ruleUrlDialog = false }, enabled = !loading) { Text("取消") } }
        )
    }

    selfStudyEdit?.let { isMorning ->
        SelfStudyDialog(
            title = if (isMorning) "早自习" else "晚自习",
            initial = if (isMorning) settings.morning else settings.evening,
            onSave = { v -> repo.updateSettings { if (isMorning) it.copy(morning = v) else it.copy(evening = v) } },
            onDismiss = { selfStudyEdit = null }
        )
    }

    pendingIcs?.let { r ->
        AlertDialog(
            onDismissRequest = { pendingIcs = null },
            title = { Text("导入 ICS 日历") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("识别到 ${r.courses.size} 门课程" + (if (r.ddays.isNotEmpty()) "、${r.ddays.size} 个全天事件（将作为倒计时）" else "") + (if (r.skipped > 0) "，跳过 ${r.skipped} 条" else ""))
                    r.courses.take(8).forEach { c -> Text("· ${c.name} ${WeekdayNames[c.day - 1]} 第${c.startPeriod}-${c.endPeriod}节 ${c.weekDescription()}", style = MaterialTheme.typography.bodySmall) }
                    if (r.courses.size > 8) Text("…", style = MaterialTheme.typography.bodySmall)
                    Text("节次按当前作息时间就近匹配，周次相对当前开学日期计算；请先确认学期设置正确。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        repo.clearAll(); r.courses.forEach { repo.upsert(it) }; r.ddays.forEach { repo.upsertDDay(it) }
                        Toast.makeText(context, "已替换为 ${r.courses.size} 门课程", Toast.LENGTH_SHORT).show(); pendingIcs = null
                    }) { Text("替换") }
                    TextButton(onClick = {
                        r.courses.forEach { repo.upsert(it) }; r.ddays.forEach { repo.upsertDDay(it) }
                        Toast.makeText(context, "已合并 ${r.courses.size} 门课程", Toast.LENGTH_SHORT).show(); pendingIcs = null
                    }) { Text("合并") }
                }
            },
            dismissButton = { TextButton(onClick = { pendingIcs = null }) { Text("取消") } }
        )
    }

    pendingImport?.let { text ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入课表") },
            text = { Text("选择导入方式：\n「替换」会清空当前课程后导入；「合并」保留现有课程并追加。") },
            confirmButton = {
                TextButton(onClick = {
                    val n = repo.importJson(text, replace = true); pendingImport = null
                    Toast.makeText(context, if (n >= 0) "已导入 $n 门课程" else "文件格式不正确", Toast.LENGTH_SHORT).show()
                }) { Text("替换") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { pendingImport = null }) { Text("取消") }
                    TextButton(onClick = {
                        val n = repo.importJson(text, replace = false); pendingImport = null
                        Toast.makeText(context, if (n >= 0) "已合并 $n 门课程" else "文件格式不正确", Toast.LENGTH_SHORT).show()
                    }) { Text("合并") }
                }
            }
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空全部课程？") },
            text = { Text("此操作不可撤销。") },
            confirmButton = { TextButton(onClick = { repo.clearAll(); confirmClear = false }) { Text("清空", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } }
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("恢复示例课表？") },
            text = { Text("当前课程将被示例数据替换。") },
            confirmButton = { TextButton(onClick = { repo.resetSample(); confirmReset = false }) { Text("恢复") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("取消") } }
        )
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, bottom = 6.dp)
        )
        Card(
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth()
        ) { Column { content() } }
    }
}

@Composable
private fun SettingsItem(title: String, subtitle: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchItem(
    title: String,
    checked: Boolean,
    subtitle: String? = null,
    enabled: Boolean = true,
    backdrop: Backdrop,
    onChange: (Boolean) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        // 上游 LiquidToggle：其手势仅挂在滑块上，点击轨道区域没有响应，
        // 因此在外层补一个点击切换；滑块自身的点击/拖动仍由上游处理。
        val currentChecked by rememberUpdatedState(checked)
        val currentOnChange by rememberUpdatedState(onChange)
        val currentEnabled by rememberUpdatedState(enabled)
        Box(
            Modifier
                .alpha(if (enabled) 1f else 0.4f)
                .pointerInput(Unit) {
                    detectTapGestures { pos ->
                        if (!currentEnabled) return@detectTapGestures
                        // 上游滑块：宽 40dp，左边距 2dp，选中时右移 20dp；滑块内的点击交给上游处理，避免重复切换
                        val fraction = if (currentChecked) 1f else 0f
                        val thumbStart = (2.dp.toPx() + 20.dp.toPx() * fraction)
                        val thumbEnd = thumbStart + 40.dp.toPx()
                        val insideThumb = pos.x in thumbStart..thumbEnd && pos.y in 2.dp.toPx()..26.dp.toPx()
                        if (!insideThumb) currentOnChange(!currentChecked)
                    }
                }
        ) {
            LiquidToggle(
                selected = { currentChecked },
                onSelect = { if (enabled) currentOnChange(it) },
                backdrop = backdrop
            )
        }
    }
}

@Suppress("unused")
private val Today: LocalDate get() = LocalDate.now()


@Composable
private fun HubGroup(title: String, content: @Composable () -> Unit) {
    SettingsCard(title) { content() }
}

@Composable
private fun HubItem(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
