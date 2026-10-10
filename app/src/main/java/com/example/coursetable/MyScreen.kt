package com.example.coursetable

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ExitToApp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 「我的」页面：教务账号绑定、个人信息、后台刷新课表 / 空闲教室 */
@Composable
fun MyScreen(repo: CourseRepository, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val tick = JwSession.tick.intValue
    val loggedIn = remember(tick) { JwSession.isLoggedIn(context) }
    val profile = remember(tick) { JwSession.profile(context) }
    val rule = remember(tick) { JwSession.rule(context) }
    val anchor = remember(tick) { JwSession.anchorUrl(context) }
    val lastSync = remember(tick) { JwSession.lastSync(context) }
    val profileTime = remember(tick) { JwSession.lastProfileTime(context) }

    var working by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<RuleEngine.Result?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }
    var needLogin by remember { mutableStateOf(false) }

    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
            // 压缩到 512px 以内
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("无法解码图片")
            val scale = (512f / maxOf(bmp.width, bmp.height)).coerceAtMost(1f)
            val small = if (scale < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
            val out = java.io.ByteArrayOutputStream(); small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
            JwSession.setCustomAvatar(context, out.toByteArray())
        }.onFailure { Toast.makeText(context, "设置头像失败：${it.message}", Toast.LENGTH_LONG).show() }
    }
    var avatarMenu by remember { mutableStateOf(false) }

    fun login(url: String) {
        loginLauncher.launch(Intent(context, WebImportActivity::class.java).putExtra(WebImportActivity.EXTRA_MODE, "login").putExtra(WebImportActivity.EXTRA_URL, url))
    }

    // 首次进入且已绑定但没有资料：自动后台拉取
    LaunchedEffect(loggedIn) {
        if (loggedIn && profile == null && rule?.supportsProfile == true && JwSession.busy == null) {
            working = "profile"
            JwSession.fetchProfile(context) { r -> working = null; if (!r.ok) { message = r.error; needLogin = r.needLogin } }
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding() + 8.dp, bottom = contentPadding.calculateBottomPadding() + 24.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("我的", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 4.dp))

        if (!loggedIn) {
            LoginCard(onLogin = { login(it) })
            Card("支持的教务系统") {
                val rules = remember { RuleEngine.allRules(context) }
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    rules.filter { it.match.isNotEmpty() }.forEach { r ->
                        Text("• ${r.name}" + listOfNotNull(if (r.supportsProfile) "个人信息" else null, "课表", if (r.supportsFreeRoom) "空闲教室" else null).joinToString(" / ", prefix = "（", postfix = "）"),
                            style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("登录成功后将自动绑定账号；之后课表刷新、空闲教室、个人信息都在后台静默完成，无需再次访问教务系统。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        // ---------- 已登录 ----------
        ProfileCard(profile, rule?.name ?: "未知规则", anchor, profileTime, working == "profile", tick, onAvatarClick = { avatarMenu = true }) {
            if (working == null) {
                working = "profile"
                JwSession.fetchProfile(context) { r -> working = null; if (!r.ok) { message = r.error; needLogin = r.needLogin } else Toast.makeText(context, "个人信息已更新", Toast.LENGTH_SHORT).show() }
            }
        }

        Card("教务功能") {
            ActionRow(Icons.Filled.DateRange, "刷新课表", if (lastSync > 0) "上次同步 ${fmt(lastSync)} · 后台静默抓取" else "后台静默抓取并导入课表", loading = working == "courses") {
                if (working == null) {
                    working = "courses"
                    JwSession.fetchCourses(context, repo) { r, parsed ->
                        working = null
                        if (r.ok && parsed != null) pending = parsed else { message = r.error; needLogin = r.needLogin }
                    }
                }
            }
            HorizontalDivider()
            if (rule?.supportsFreeRoom == true) {
                ActionRow(Icons.Filled.LocationOn, "空闲教室查询", "按日期 / 节次 / 关键字后台查询，结果按地点分类") {
                    context.startActivity(Intent(context, FreeRoomsActivity::class.java))
                }
                HorizontalDivider()
            }
            ActionRow(Icons.Filled.Home, "打开教务系统", "已登录状态下浏览教务网页") {
                context.startActivity(Intent(context, WebImportActivity::class.java).putExtra(WebImportActivity.EXTRA_URL, anchor))
            }
            HorizontalDivider()
            ActionRow(Icons.Filled.ExitToApp, "退出登录", "清除绑定与 Cookie", danger = true) { confirmLogout = true }
        }
        Text("锚定网址：$anchor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
    }

    pending?.let { r ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("抓取到 ${r.courses.size} 门课程") },
            text = {
                Column {
                    Text(r.courses.take(8).joinToString("\n") { "· ${it.name} ${WeekdayNames[it.day - 1]} ${it.startPeriod}-${it.endPeriod}节" } + if (r.courses.size > 8) "\n… 共 ${r.courses.size} 条" else "")
                    if (r.semesterStart != null) Text("\n开学日期：${r.semesterStart}", style = MaterialTheme.typography.bodySmall)
                    if (r.warnings.isNotEmpty()) Text("\n" + r.warnings.take(3).joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val json = org.json.JSONObject().apply {
                        put("app", "CourseTable"); put("version", 1)
                        put("courses", org.json.JSONArray().also { a -> r.courses.forEach { a.put(it.toJson()) } })
                    }.toString()
                    val n = repo.importJson(json, replace = true)
                    r.semesterStart?.let { d -> runCatching { java.time.LocalDate.parse(d) }.getOrNull()?.let { date -> repo.updateSettings { it.copy(semesterStart = date.with(java.time.DayOfWeek.MONDAY)) } } }
                    JwSession.markSynced(context)
                    Toast.makeText(context, "已覆盖导入 $n 门课程", Toast.LENGTH_SHORT).show(); pending = null
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val json = org.json.JSONObject().apply { put("app", "CourseTable"); put("courses", org.json.JSONArray().also { a -> r.courses.forEach { a.put(it.toJson()) } }) }.toString()
                        val n = repo.importJson(json, replace = false); JwSession.markSynced(context)
                        Toast.makeText(context, "已合并 $n 门课程", Toast.LENGTH_SHORT).show(); pending = null
                    }) { Text("合并") }
                    TextButton(onClick = { pending = null }) { Text("取消") }
                }
            }
        )
    }
    message?.let { m ->
        AlertDialog(
            onDismissRequest = { message = null; needLogin = false },
            title = { Text(if (needLogin) "需要重新登录" else "操作失败") },
            text = { Text(m) },
            confirmButton = {
                if (needLogin) TextButton(onClick = { message = null; needLogin = false; login(anchor) }) { Text("去登录") }
                else TextButton(onClick = { message = null }) { Text("知道了") }
            },
            dismissButton = { if (needLogin) TextButton(onClick = { message = null; needLogin = false }) { Text("取消") } }
        )
    }
    if (avatarMenu) AlertDialog(
        onDismissRequest = { avatarMenu = false },
        title = { Text("头像") },
        text = {
            Column {
                Text(if (JwSession.jwAvatarFile(context).exists()) "已从教务系统获取到证件照；也可以使用本地图片。" else "教务系统未提供照片（或规则不支持），可选择本地图片作为头像。",
                    style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = { avatarMenu = false; avatarPicker.launch(arrayOf("image/*")) }) { Text("从相册选择") }
                if (JwSession.customAvatarFile(context).exists()) TextButton(onClick = { avatarMenu = false; JwSession.setCustomAvatar(context, null) }) { Text(if (JwSession.jwAvatarFile(context).exists()) "恢复教务照片" else "清除自定义头像") }
            }
        },
        confirmButton = { TextButton(onClick = { avatarMenu = false }) { Text("关闭") } }
    )
    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        title = { Text("退出登录？") }, text = { Text("将清除绑定的教务网址、个人信息与浏览器 Cookie，课表数据保留。") },
        confirmButton = { TextButton(onClick = { JwSession.logout(context); confirmLogout = false }) { Text("退出", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text("取消") } }
    )
}

@Composable
private fun LoginCard(onLogin: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf(context.getSharedPreferences("web_import", 0).getString("lastUrl", "") ?: "") }
    Card("绑定教务系统") {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AccountCircle, contentDescription = null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("尚未登录", style = MaterialTheme.typography.titleMedium)
                    Text("输入学校教务处网址，登录一次即可长期使用", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(
                value = url, onValueChange = { url = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("教务处网址") }, placeholder = { Text("如 jwglxt.xxx.edu.cn/jwglxt/") }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("jwglxt/", "jsxsd/").forEach { hint -> AssistChip(onClick = { if (url.isNotBlank() && !url.contains(hint)) url = url.trimEnd('/') + "/" + hint }, label = { Text("+ $hint", fontSize = 12.sp) }) }
            }
            Button(onClick = { if (url.isBlank()) Toast.makeText(context, "请输入网址", Toast.LENGTH_SHORT).show() else onLogin(url.trim()) }, modifier = Modifier.fillMaxWidth()) {
                Text("前往登录")
            }
        }
    }
}

@Composable
private fun ProfileCard(p: JwSession.Profile?, ruleName: String, anchor: String, time: Long, loading: Boolean, tick: Int, onAvatarClick: () -> Unit, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val avatar = remember(tick) {
        JwSession.avatarFile(context)?.let { f -> runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() }
    }
    Card(null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clickable(onClick = onAvatarClick), contentAlignment = Alignment.Center) {
                    if (avatar != null) androidx.compose.foundation.Image(avatar, contentDescription = "头像", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                    else Text((p?.name?.takeLast(2) ?: "?"), color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p?.name?.ifBlank { null } ?: if (loading) "正在获取个人信息…" else "未获取到个人信息", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(p?.studentId?.ifBlank { null }, ruleName).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else androidx.compose.material3.IconButton(onClick = onRefresh) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
            }
            if (p != null) {
                Spacer(Modifier.height(12.dp)); HorizontalDivider(); Spacer(Modifier.height(8.dp))
                val rows = listOf("学院" to p.college, "专业" to p.major, "班级" to p.className, "年级" to p.grade) + p.extra.entries.take(8).map { it.key to it.value }
                rows.filter { it.second.isNotBlank() }.forEach { (k, v) ->
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(k, Modifier.width(72.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (time > 0) Text("更新于 ${fmt(time)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun Card(title: String?, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column {
            if (title != null) Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 14.dp, end = 16.dp, bottom = 4.dp))
            content()
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, subtitle: String, loading: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
    }
}

private fun fmt(t: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault()).format(Date(t))
