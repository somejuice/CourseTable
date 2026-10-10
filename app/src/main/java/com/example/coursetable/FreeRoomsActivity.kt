package com.example.coursetable

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** 空闲教室 */
data class FreeRoom(val name: String, val building: String, val campus: String, val type: String, val seats: String) {
    /** 地点分类键：校区 · 教学楼；都没有时按教室名前缀推断 */
    val place: String
        get() {
            val explicit = listOfNotNull(campus.ifBlank { null }, building.ifBlank { null }).joinToString(" · ")
            if (explicit.isNotBlank()) return explicit
            val m = Regex("^(.*?[楼馆院区部栋座])").find(name)
            if (m != null && m.value.length < name.length) return m.value
            val lead = name.takeWhile { !it.isDigit() && it != '-' && it != ' ' }
            return if (lead.isNotBlank() && lead.length < name.length) lead else "其他"
        }

    companion object {
        fun fromJson(json: String): List<FreeRoom> = runCatching {
            val arr = JSONObject(json).optJSONArray("rooms") ?: JSONArray()
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map {
                FreeRoom(it.optString("name").trim(), it.optString("building").trim(), it.optString("campus").trim(),
                    it.optString("type").trim(), it.optString("seats").trim())
            }.filter { it.name.isNotBlank() }
        }.getOrDefault(emptyList())
    }
}

/**
 * 空闲教室结果页：按地点分类 + 搜索 + 分类筛选。
 * 通过 EXTRA_JSON/EXTRA_TITLE 传入结果，或无参数打开时显示上次查询结果。
 */
class FreeRoomsActivity : ComponentActivity() {
    companion object {
        const val EXTRA_JSON = "json"
        const val EXTRA_TITLE = "title"
        private const val PREFS = "free_rooms"

        fun saveLast(ctx: Context, json: String, title: String) {
            ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("json", json).putString("title", title)
                .putLong("time", System.currentTimeMillis()).apply()
        }

        fun open(ctx: Context, json: String?, title: String?) {
            ctx.startActivity(Intent(ctx, FreeRoomsActivity::class.java).apply {
                if (json != null) putExtra(EXTRA_JSON, json)
                if (title != null) putExtra(EXTRA_TITLE, title)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repo = CourseRepository(applicationContext)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val json = intent.getStringExtra(EXTRA_JSON) ?: prefs.getString("json", null)
        val title = intent.getStringExtra(EXTRA_TITLE) ?: prefs.getString("title", null) ?: "空闲教室"
        val fromCache = intent.getStringExtra(EXTRA_JSON) == null
        setContent {
            CourseTableTheme(settings = repo.settings) {
                var curTitle by remember { mutableStateOf(title) }
                var curSubtitle by remember { mutableStateOf(if (fromCache && json != null) "上次查询结果" else null) }
                var rooms by remember { mutableStateOf(json?.let { FreeRoom.fromJson(it) } ?: emptyList()) }
                var showQuery by remember { mutableStateOf(false) }
                var loading by remember { mutableStateOf(false) }
                var error by remember { mutableStateOf<String?>(null) }
                val loggedIn = JwSession.tick.intValue.let { JwSession.isLoggedIn(this) }
                FreeRoomsScreen(
                    title = curTitle, subtitle = curSubtitle, rooms = rooms, loading = loading,
                    onBack = { finish() },
                    onRequery = {
                        if (loggedIn) showQuery = true
                        else startActivity(Intent(this, WebImportActivity::class.java).putExtra(WebImportActivity.EXTRA_MODE, "rooms"))
                    }
                )
                if (showQuery) RoomQueryDialog(repo, onDismiss = { showQuery = false }) { date, sp, ep, kw ->
                    showQuery = false; loading = true
                    val t = JwSession.roomTitle(date, sp, ep, kw)
                    JwSession.fetchRooms(this, JwSession.roomParams(repo, date, sp, ep, kw)) { r ->
                        loading = false
                        if (r.ok && r.data != null) {
                            val list = FreeRoom.fromJson(r.data.toString())
                            if (list.isEmpty()) error = "该时段没有查询到空闲教室"
                            else { rooms = list; curTitle = t; curSubtitle = null; saveLast(this, r.data.toString(), t) }
                        } else error = (r.error ?: "查询失败") + if (r.needLogin) "\n请在「我的」页面重新登录" else ""
                    }
                }
                error?.let { e ->
                    androidx.compose.material3.AlertDialog(onDismissRequest = { error = null }, title = { Text("查询失败") }, text = { Text(e) },
                        confirmButton = { androidx.compose.material3.TextButton(onClick = { error = null }) { Text("知道了") } })
                }
            }
        }
    }
}

/** 后台查询参数：日期 / 节次 / 关键字 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomQueryDialog(repo: CourseRepository, onDismiss: () -> Unit, onQuery: (java.time.LocalDate, Int, Int, String) -> Unit) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("web_import", 0)
    var date by remember { mutableStateOf(java.time.LocalDate.now()) }
    var sp by remember { mutableStateOf(prefs.getString("roomSp", "1") ?: "1") }
    var ep by remember { mutableStateOf(prefs.getString("roomEp", "2") ?: "2") }
    var kw by remember { mutableStateOf(prefs.getString("roomKw", "") ?: "") }
    var pickDate by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("查询空闲教室") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { pickDate = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("$date ${WeekdayNames[date.dayOfWeek.value - 1]}（第 ${repo.currentWeek(date)} 周）")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(sp, { sp = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("起始节") }, singleLine = true)
                    OutlinedTextField(ep, { ep = it.filter(Char::isDigit) }, Modifier.weight(1f), label = { Text("结束节") }, singleLine = true)
                }
                OutlinedTextField(kw, { kw = it }, Modifier.fillMaxWidth(), label = { Text("教室 / 教学楼关键字（可选）") }, singleLine = true)
                Text("将在后台使用已登录的教务会话查询，无需打开网页。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = {
                val s1 = sp.toIntOrNull()?.coerceIn(1, 14) ?: 1
                val e1 = (ep.toIntOrNull() ?: s1).coerceIn(s1, 14)
                prefs.edit().putString("roomSp", "$s1").putString("roomEp", "$e1").putString("roomKw", kw).apply()
                onQuery(date, s1, e1, kw.trim())
            }) { Text("查询") }
        },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } }
    )
    if (pickDate) {
        val state = androidx.compose.material3.rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(java.time.ZoneOffset.UTC).toEpochMilli())
        androidx.compose.material3.DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { state.selectedDateMillis?.let { date = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("确定") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { pickDate = false }) { Text("取消") } }
        ) { androidx.compose.material3.DatePicker(state = state) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FreeRoomsScreen(
    title: String,
    subtitle: String?,
    rooms: List<FreeRoom>,
    loading: Boolean = false,
    onBack: () -> Unit,
    onRequery: () -> Unit
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedPlace by remember { mutableStateOf<String?>(null) }
    // 默认全部折叠；搜索或选中某地点时自动展开
    val expanded = remember(rooms) { androidx.compose.runtime.mutableStateListOf<String>() }

    val allGroups = remember(rooms) {
        rooms.groupBy { it.place }.toSortedMap(compareBy<String> { it == "其他" }.thenBy { it })
    }
    val q = query.trim().lowercase()
    val filtered = remember(rooms, q, selectedPlace) {
        allGroups.mapValues { (place, list) ->
            if (selectedPlace != null && place != selectedPlace) emptyList()
            else list.filter { r ->
                q.isBlank() || r.name.lowercase().contains(q) || place.lowercase().contains(q) ||
                    r.type.lowercase().contains(q) || r.seats.contains(q)
            }
        }.filterValues { it.isNotEmpty() }
    }
    val shownCount = filtered.values.sumOf { it.size }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("空闲教室", fontWeight = FontWeight.SemiBold)
                        Text(
                            listOfNotNull(title.takeIf { it != "空闲教室" }, subtitle).joinToString(" · ").ifBlank { "共 ${rooms.size} 间" },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "返回") } },
                actions = {
                    if (rooms.isNotEmpty()) IconButton(onClick = {
                        val text = buildString {
                            append(title).append('\n')
                            filtered.forEach { (p, list) ->
                                append("【").append(p).append("】").append(list.size).append(" 间\n")
                                append(list.joinToString("  ") { it.name + (if (it.seats.isNotBlank()) "(${it.seats})" else "") }).append("\n\n")
                            }
                        }.trim()
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("空闲教室", text))
                        Toast.makeText(context, "已复制 $shownCount 间教室", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Filled.Share, contentDescription = "复制") }
                    if (loading) androidx.compose.material3.CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = onRequery) { Icon(Icons.Filled.Refresh, contentDescription = "重新查询") }
                }
            )
        },
        floatingActionButton = {
            if (rooms.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = onRequery,
                icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                text = { Text("重新查询") }
            )
        }
    ) { padding ->
        if (rooms.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(12.dp))
                Text("还没有查询结果", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("登录教务系统后选择日期、节次与关键字即可查询；结果会按教学楼分类并支持搜索。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                if (loading) androidx.compose.material3.CircularProgressIndicator()
                else Button(onClick = onRequery) { Icon(Icons.Filled.Search, contentDescription = null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("去查询") }
            }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query, onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索教室 / 教学楼 / 类型 / 座位数") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Clear, contentDescription = "清空") }
                },
                singleLine = true, shape = RoundedCornerShape(16.dp)
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = selectedPlace == null, onClick = { selectedPlace = null }, label = { Text("全部 ${rooms.size}") })
                FilterChip(selected = false, onClick = { if (expanded.size < allGroups.size) { expanded.clear(); expanded.addAll(allGroups.keys) } else expanded.clear() },
                    label = { Text(if (expanded.size < allGroups.size) "全部展开" else "全部折叠") })
                allGroups.forEach { (place, list) ->
                    FilterChip(
                        selected = selectedPlace == place,
                        onClick = { selectedPlace = if (selectedPlace == place) null else place },
                        label = { Text("$place ${list.size}") }
                    )
                }
            }
            Text(
                if (q.isBlank() && selectedPlace == null) "共 ${rooms.size} 间，${allGroups.size} 个地点" else "匹配 $shownCount 间，${filtered.size} 个地点",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
            if (filtered.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("没有匹配的教室", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                filtered.forEach { (place, list) ->
                    val isOpen = q.isNotBlank() || selectedPlace == place || place in expanded
                    stickyHeader(key = "h_$place") {
                        val rot by androidx.compose.animation.core.animateFloatAsState(if (isOpen) 90f else 0f, label = "arrow")
                        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable { if (place in expanded) expanded.remove(place) else expanded.add(place) }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                                Spacer(Modifier.width(8.dp))
                                Text(place, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Text("${list.size} 间", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = if (isOpen) "折叠" else "展开",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(20.dp).rotate(rot))
                            }
                        }
                    }
                    if (isOpen) items(list, key = { "$place/${it.name}/${it.seats}" }) { r -> RoomRow(r, q) }
                    else item(key = "c_$place") {
                        Text(list.take(6).joinToString("  ") { it.name } + if (list.size > 6) "  …" else "",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun RoomRow(r: FreeRoom, q: String) {
    val context = LocalContext.current
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("教室", r.name))
            Toast.makeText(context, "已复制 ${r.name}", Toast.LENGTH_SHORT).show()
        }
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val meta = listOfNotNull(r.type.ifBlank { null }, r.building.ifBlank { null }.takeIf { r.campus.isNotBlank() }).joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (r.seats.isNotBlank()) {
                Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                    Text("${r.seats} 座", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
            }
        }
    }
}
