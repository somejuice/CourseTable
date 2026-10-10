package com.example.coursetable

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务系统解析规则（插件）。
 * 规则 = JSON 文件：{ id, name, vendor, version, author, match:[url 片段/正则], hint, script }
 * script 为在教务页面内执行的 JavaScript 函数体（可用 await / fetch / document），
 * 需 return { courses:[{ name, teacher, location, day(1-7), startPeriod, endPeriod, weekText | weeks:[..] }], semesterStart? }
 */
data class ImportRule(
    val id: String,
    val name: String,
    val vendor: String = "",
    val version: Int = 1,
    val author: String = "",
    val match: List<String> = emptyList(),
    val hint: String = "",
    val script: String,
    val builtin: Boolean = false,
    val freeRoomScript: String = "",   // 可选：空闲教室查询脚本
    val freeRoomHint: String = "",
    val profileScript: String = "",    // 可选：个人信息抓取脚本，return { name, studentId, college, major, className, grade, extra:{} }
    val loginCheckScript: String = ""  // 可选：登录状态检测脚本，return true/false
) {
    val supportsProfile get() = profileScript.isNotBlank()
    val supportsFreeRoom get() = freeRoomScript.isNotBlank()
    fun matches(url: String): Boolean = match.any { m ->
        url.contains(m, ignoreCase = true) || runCatching { Regex(m, RegexOption.IGNORE_CASE).containsMatchIn(url) }.getOrDefault(false)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("vendor", vendor); put("version", version); put("author", author)
        put("match", JSONArray(match)); put("hint", hint); put("script", script)
        if (freeRoomScript.isNotBlank()) { put("freeRoomScript", freeRoomScript); put("freeRoomHint", freeRoomHint) }
        if (profileScript.isNotBlank()) put("profileScript", profileScript)
        if (loginCheckScript.isNotBlank()) put("loginCheckScript", loginCheckScript)
    }

    companion object {
        fun fromJson(o: JSONObject, builtin: Boolean = false): ImportRule {
            val script = o.optString("script", "")
            require(script.isNotBlank()) { "规则缺少 script 字段" }
            val id = o.optString("id", "").ifBlank { "rule-" + script.hashCode().toUInt().toString(16) }
            val match = o.optJSONArray("match")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            return ImportRule(
                id = id, name = o.optString("name", id), vendor = o.optString("vendor", ""), version = o.optInt("version", 1),
                author = o.optString("author", ""), match = match, hint = o.optString("hint", ""), script = script, builtin = builtin,
                freeRoomScript = o.optString("freeRoomScript", ""), freeRoomHint = o.optString("freeRoomHint", ""),
                profileScript = o.optString("profileScript", ""), loginCheckScript = o.optString("loginCheckScript", "")
            )
        }
    }
}

object RuleEngine {
    private const val PREFS = "import_rules"

    fun builtinRules(context: Context): List<ImportRule> = runCatching {
        val am = context.assets
        (am.list("rules") ?: emptyArray()).sorted().mapNotNull { f ->
            runCatching { ImportRule.fromJson(JSONObject(am.open("rules/$f").bufferedReader().readText()), builtin = true) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    fun customRules(context: Context): List<ImportRule> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("rules", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json); (0 until arr.length()).mapNotNull { runCatching { ImportRule.fromJson(arr.getJSONObject(it)) }.getOrNull() }
        }.getOrDefault(emptyList())
    }

    /** 自定义规则优先（同 id 覆盖内置） */
    fun allRules(context: Context): List<ImportRule> {
        val custom = customRules(context)
        val ids = custom.map { it.id }.toSet()
        return custom + builtinRules(context).filter { it.id !in ids }
    }

    fun saveCustom(context: Context, rules: List<ImportRule>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("rules", JSONArray().also { a -> rules.forEach { a.put(it.toJson()) } }.toString()).apply()
    }

    fun addCustom(context: Context, rule: ImportRule) {
        val list = customRules(context).filter { it.id != rule.id } + rule
        saveCustom(context, list)
    }

    fun removeCustom(context: Context, id: String) = saveCustom(context, customRules(context).filter { it.id != id })

    /** 解析规则文件文本：支持单个规则对象或规则数组 */
    fun parseRuleText(text: String): List<ImportRule> {
        val t = text.trim()
        return if (t.startsWith("[")) {
            val a = JSONArray(t); (0 until a.length()).map { ImportRule.fromJson(a.getJSONObject(it)) }
        } else listOf(ImportRule.fromJson(JSONObject(t)))
    }

    fun pickForUrl(context: Context, url: String): ImportRule? = allRules(context).firstOrNull { it.match.isNotEmpty() && it.matches(url) }

    // ---------- 脚本输出 → 课程 ----------

    /** 解析周次文本："1-16周(单)"、"1-8周,10-16周(双)"、"1,3,5周"、"第2-17周 双周"、"全周" */
    fun parseWeeks(text: String, totalWeeks: Int): Set<Int> {
        val t = text.replace('，', ',').replace('、', ',').replace('－', '-').replace('—', '-').replace('～', '-').replace('~', '-')
            .replace("（", "(").replace("）", ")").replace(" ", "")
        if (t.isBlank()) return emptySet()
        val result = sortedSetOf<Int>()
        // 按逗号分段，每段可能带自己的(单)/(双)
        val globalOdd = Regex("单").containsMatchIn(t) && !Regex("双").containsMatchIn(t)
        val globalEven = Regex("双").containsMatchIn(t) && !Regex("单").containsMatchIn(t)
        var matchedAny = false
        for (seg in t.split(',')) {
            val odd = seg.contains("单") || (globalOdd && !seg.contains("双"))
            val even = seg.contains("双") || (globalEven && !seg.contains("单"))
            val range = Regex("(\\d+)-(\\d+)").find(seg)
            val weeks: IntRange? = if (range != null) range.groupValues[1].toInt()..range.groupValues[2].toInt()
            else Regex("(\\d+)").find(seg)?.let { v -> v.groupValues[1].toInt().let { it..it } }
            if (weeks == null) {
                if (seg.contains("全")) { result.addAll(1..totalWeeks); matchedAny = true }
                continue
            }
            matchedAny = true
            for (w in weeks) {
                if (w !in 1..60) continue
                if (odd && w % 2 == 0) continue
                if (even && w % 2 == 1) continue
                result.add(w)
            }
        }
        if (!matchedAny) return emptySet()
        return result
    }

    /** 将周次集合拆成若干 (startWeek, endWeek, weekType) 段 */
    fun weekRanges(weeks: Set<Int>): List<Triple<Int, Int, Int>> {
        if (weeks.isEmpty()) return emptyList()
        val sorted = weeks.sorted()
        val out = mutableListOf<Triple<Int, Int, Int>>()
        var i = 0
        while (i < sorted.size) {
            // 优先尝试连续段
            var j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 1) j++
            if (j > i) { out.add(Triple(sorted[i], sorted[j], 0)); i = j + 1; continue }
            // 尝试隔周段
            j = i
            while (j + 1 < sorted.size && sorted[j + 1] == sorted[j] + 2) j++
            if (j > i) { out.add(Triple(sorted[i], sorted[j], if (sorted[i] % 2 == 1) 1 else 2)); i = j + 1; continue }
            out.add(Triple(sorted[i], sorted[i], 0)); i++
        }
        return out
    }

    data class Result(val courses: List<Course>, val semesterStart: String?, val warnings: List<String>)

    fun toCourses(data: JSONObject, totalWeeks: Int, periodsPerDay: Int): Result {
        val arr = data.optJSONArray("courses") ?: JSONArray()
        val out = mutableListOf<Course>()
        val warnings = mutableListOf<String>()
        val colorOf = linkedMapOf<String, Int>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("name", "").trim()
            if (name.isBlank()) continue
            val day = o.optInt("day", 0)
            if (day !in 1..7) { warnings.add("「$name」星期无效，已跳过"); continue }
            var sp = o.optInt("startPeriod", 0); var ep = o.optInt("endPeriod", 0)
            o.optJSONArray("periods")?.let { p -> if (p.length() >= 1) { sp = p.optInt(0); ep = p.optInt(p.length() - 1) } }
            if (sp <= 0) sp = 1
            if (ep < sp) ep = sp
            if (sp > periodsPerDay) { warnings.add("「$name」第 $sp 节超出每天节数，已跳过"); continue }
            ep = ep.coerceAtMost(14)
            val weeks: Set<Int> = o.optJSONArray("weeks")?.let { w -> (0 until w.length()).map { w.optInt(it) }.filter { it in 1..60 }.toSet() }
                ?: parseWeeks(o.optString("weekText", ""), totalWeeks)
            val ranges = if (weeks.isEmpty()) { warnings.add("「$name」未识别到周次，按 1-$totalWeeks 周导入"); listOf(Triple(1, totalWeeks, 0)) } else weekRanges(weeks)
            val color = colorOf.getOrPut(name) { colorOf.size % CourseColors.size }
            for ((s, e, type) in ranges) {
                out.add(Course(
                    name = name, teacher = o.optString("teacher", "").trim(), location = o.optString("location", "").trim(),
                    day = day, startPeriod = sp, endPeriod = ep, startWeek = s, endWeek = e, weekType = type, colorIndex = color
                ))
            }
        }
        // 合并完全相同的记录
        val dedup = out.distinctBy { listOf(it.name, it.day, it.startPeriod, it.endPeriod, it.startWeek, it.endWeek, it.weekType, it.location) }
        return Result(dedup, data.optString("semesterStart", "").takeIf { it.isNotBlank() }, warnings)
    }

    const val RULE_DOC = """# 教务系统解析规则（插件）编写说明

规则是一个 JSON 文件，可通过「设置 → 数据 → 教务系统导入 → 导入规则文件」加载，同 id 会覆盖内置规则。

```json
{
  "id": "my-school-jw",            // 唯一标识
  "name": "某某大学教务（正方）",     // 显示名称
  "vendor": "zfsoft",              // 系统厂商，可选
  "version": 1,
  "author": "你的名字",
  "match": ["/jwglxt/", "xskbcx"], // URL 包含这些片段（或正则）时自动选用
  "hint": "登录后进入 课表查询 页面再点抓取", // 给用户的操作提示
  "script": "...JavaScript 函数体..."
}
```

## script 约定
- 在教务页面上下文中以 `async` 函数体执行，可使用 `document`、`fetch`（同源、自动带 Cookie）、`await`。
- 必须 `return` 一个对象：
```js
return {
  semesterStart: "2026-09-07",      // 可选，开学日期
  courses: [{
    name: "高等数学",                // 必填
    teacher: "王老师",               // 可选
    location: "教一 201",            // 可选
    day: 1,                          // 1=周一 … 7=周日
    startPeriod: 1, endPeriod: 2,    // 节次（或 periods:[1,2]）
    weekText: "1-16周(单)",          // 周次文本，由应用解析
    // 或 weeks: [1,3,5,7]           // 直接给出周次数组（优先）
  }]
};
```
- 抛出异常（`throw '原因'`）会把原因显示给用户。
- weekText 支持：`1-16周`、`1-8周,10-16周(双)`、`1,3,5周`、`第2-17周 单周`、`全周`。
- 不连续周次会自动拆成多条记录；单双周自动识别。

## 空闲教室查询（可选）
在规则中增加 `freeRoomScript`（及 `freeRoomHint`），同样是 async 函数体，额外可读取注入的参数对象 `__p`：
```js
__p = { date:"2026-10-12", week:6, weekday:1, startPeriod:1, endPeriod:2,
        keyword:"教一", xnm:"2026", xqm:"3" }   // xqm: 3=第一学期 12=第二学期（正方口径）
return { rooms:[{ name:"教一 201", building:"教一楼", campus:"本部", type:"多媒体", seats:120 }] };
```

## 个人信息与登录检测（可选）
- `profileScript`：async 函数体，`return { name, studentId, college, major, className, grade, extra:{其它键值} }`，用于「我的」页面。
- `loginCheckScript`：async 函数体，`return true/false`，判断当前 Cookie 是否仍处于登录态；缺省时按页面是否含密码输入框 / 网址是否含 login 判断。
- 绑定账号后，课表刷新 / 空闲教室 / 个人信息都会在后台用锚定网址 + 已保存 Cookie 静默执行，无需再次打开教务系统。

## 调试
在「教务系统导入」页面右上角可切换规则并查看脚本返回的原始错误信息；也可以先在电脑浏览器控制台中把 script 包在 `(async()=>{ ... })().then(console.log)` 里运行。

欢迎把适配好的规则文件分享给同学或提交到社区仓库。
"""
}
