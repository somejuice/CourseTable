package com.example.coursetable

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONObject

/**
 * 教务系统会话：登录成功后"锚定"一个网址 + 规则，之后课表 / 空闲教室 / 个人信息都在后台
 * 用隐藏 WebView（共享 Cookie）打开锚定网址并执行规则脚本，无需用户再次访问教务系统。
 */
object JwSession {
    private const val PREFS = "jw_session"
    private const val TIMEOUT_MS = 35_000L

    /** Compose 观察用的版本号：绑定 / 退出 / 资料更新 时自增 */
    val tick = mutableIntStateOf(0)
    @Volatile var busy: String? = null
        private set

    data class Result(val ok: Boolean, val data: JSONObject? = null, val error: String? = null, val needLogin: Boolean = false)

    data class Profile(val name: String, val studentId: String, val college: String, val major: String, val className: String,
                       val grade: String, val extra: Map<String, String>) {
        companion object {
            fun fromJson(o: JSONObject?): Profile? {
                if (o == null) return null
                val extra = linkedMapOf<String, String>()
                o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
                return Profile(o.optString("name"), o.optString("studentId"), o.optString("college"), o.optString("major"),
                    o.optString("className"), o.optString("grade"), extra)
            }
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun anchorUrl(ctx: Context): String = prefs(ctx).getString("anchorUrl", "") ?: ""
    fun ruleId(ctx: Context): String = prefs(ctx).getString("ruleId", "") ?: ""
    fun isLoggedIn(ctx: Context) = anchorUrl(ctx).isNotBlank()
    fun rule(ctx: Context): ImportRule? = RuleEngine.allRules(ctx).firstOrNull { it.id == ruleId(ctx) }
    fun profile(ctx: Context): Profile? = prefs(ctx).getString("profile", null)?.let { runCatching { Profile.fromJson(JSONObject(it)) }.getOrNull() }
    fun lastSync(ctx: Context): Long = prefs(ctx).getLong("lastSync", 0L)
    fun lastProfileTime(ctx: Context): Long = prefs(ctx).getLong("profileTime", 0L)

    fun bind(ctx: Context, url: String, ruleId: String) {
        prefs(ctx).edit().putString("anchorUrl", url).putString("ruleId", ruleId).putLong("boundAt", System.currentTimeMillis()).apply()
        CookieManager.getInstance().flush()
        bump()
    }

    fun logout(ctx: Context) {
        prefs(ctx).edit().clear().apply()
        jwAvatarFile(ctx).delete()
        runCatching { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() }
        bump()
    }

    fun saveProfile(ctx: Context, o: JSONObject) {
        // 头像：脚本可返回 avatar（data:image/...;base64,）或 extra 中的照片，落盘为 avatar_jw
        val avatar = o.optString("avatar", "")
        if (avatar.startsWith("data:image")) {
            runCatching {
                val bytes = android.util.Base64.decode(avatar.substringAfter(",", ""), android.util.Base64.DEFAULT)
                if (bytes.size > 100) jwAvatarFile(ctx).writeBytes(bytes)
            }
        }
        o.remove("avatar")
        prefs(ctx).edit().putString("profile", o.toString()).putLong("profileTime", System.currentTimeMillis()).apply(); bump()
    }

    fun jwAvatarFile(ctx: Context) = java.io.File(ctx.filesDir, "avatar_jw.img")
    fun customAvatarFile(ctx: Context) = java.io.File(ctx.filesDir, "avatar_custom.img")
    /** 当前应显示的头像文件：自定义优先，其次教务照片 */
    fun avatarFile(ctx: Context): java.io.File? = listOf(customAvatarFile(ctx), jwAvatarFile(ctx)).firstOrNull { it.exists() && it.length() > 0 }
    fun setCustomAvatar(ctx: Context, bytes: ByteArray?) {
        if (bytes == null) customAvatarFile(ctx).delete() else customAvatarFile(ctx).writeBytes(bytes)
        bump()
    }

    fun markSynced(ctx: Context) { prefs(ctx).edit().putLong("lastSync", System.currentTimeMillis()).apply(); bump() }

    private fun bump() { Handler(Looper.getMainLooper()).post { tick.intValue++ } }

    /** 缺省登录检测：页面没有密码框且网址不含 login/cas */
    private const val DEFAULT_LOGIN_CHECK =
        "return !document.querySelector('input[type=password]') && !/login|cas\\//i.test(location.href);"

    // ---------- 后台执行 ----------

    fun fetchProfile(ctx: Context, onDone: (Result) -> Unit) {
        val rule = rule(ctx) ?: return onDone(Result(false, error = "未绑定规则"))
        if (!rule.supportsProfile) return onDone(Result(false, error = "规则「${rule.name}」不支持个人信息抓取"))
        run(ctx, "profile", rule.loginCheckScript, rule.profileScript, null) { r ->
            if (r.ok && r.data != null) saveProfile(ctx, r.data)
            onDone(r)
        }
    }

    /** 后台刷新课表：返回解析结果（不自动写入，由调用方确认） */
    fun fetchCourses(ctx: Context, repo: CourseRepository, onDone: (Result, RuleEngine.Result?) -> Unit) {
        val rule = rule(ctx) ?: return onDone(Result(false, error = "未绑定规则"), null)
        run(ctx, "courses", rule.loginCheckScript, rule.script, null) { r ->
            if (!r.ok || r.data == null) return@run onDone(r, null)
            val parsed = runCatching { RuleEngine.toCourses(r.data, repo.settings.totalWeeks, repo.settings.periodsPerDay) }.getOrNull()
            if (parsed == null || parsed.courses.isEmpty()) onDone(Result(false, error = "脚本执行成功但没有解析到课程"), null)
            else onDone(r, parsed)
        }
    }

    fun fetchRooms(ctx: Context, params: JSONObject, onDone: (Result) -> Unit) {
        val rule = rule(ctx) ?: return onDone(Result(false, error = "未绑定规则"))
        if (!rule.supportsFreeRoom) return onDone(Result(false, error = "规则「${rule.name}」不支持空闲教室查询"))
        run(ctx, "rooms", rule.loginCheckScript, rule.freeRoomScript, params, onDone)
    }

    /** 快速检测登录态（后台） */
    fun checkLogin(ctx: Context, onDone: (Boolean) -> Unit) {
        val rule = rule(ctx) ?: return onDone(false)
        run(ctx, "check", rule.loginCheckScript, "return true;", null) { onDone(it.ok) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun run(ctx: Context, kind: String, loginCheck: String, script: String, params: JSONObject?, onDone: (Result) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { run(ctx, kind, loginCheck, script, params, onDone) }; return }
        val anchor = anchorUrl(ctx)
        if (anchor.isBlank()) return onDone(Result(false, error = "尚未绑定教务系统", needLogin = true))
        if (busy != null) return onDone(Result(false, error = "正在执行其它后台任务（$busy），请稍候"))
        busy = kind
        val app = ctx.applicationContext
        val web = WebView(app)
        var finished = false
        var injected = false
        fun finish(r: Result) {
            if (finished) return
            finished = true; busy = null
            main.post { runCatching { web.stopLoading(); web.destroy() }; onDone(r) }
        }
        val check = loginCheck.ifBlank { DEFAULT_LOGIN_CHECK }
        val js = """
            (async function(){
              const __p = ${params ?: JSONObject()};
              try {
                const __ok = await (async function(){ $check
                })();
                if (!__ok) { CourseTableBg.onResult(JSON.stringify({ok:false, needLogin:true, error:'登录已失效，请重新登录'})); return; }
                const __r = await (async function(){ $script
                })();
                CourseTableBg.onResult(JSON.stringify({ok:true, data: __r}));
              } catch (e) {
                CourseTableBg.onResult(JSON.stringify({ok:false, error: String(e && (e.stack || e.message) || e)}));
              }
            })();
        """.trimIndent()
        web.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            userAgentString = if (app.getSharedPreferences("web_import", Context.MODE_PRIVATE).getBoolean("desktop", false)) WebImportActivity.DESKTOP_UA
                else android.webkit.WebSettings.getDefaultUserAgent(app).replace("; wv", "")
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        web.addJavascriptInterface(object {
            @JavascriptInterface
            fun onResult(json: String) {
                val r = runCatching {
                    val o = JSONObject(json)
                    if (o.optBoolean("ok")) {
                        val d = o.opt("data")
                        val data = when (d) {
                            is JSONObject -> d
                            is org.json.JSONArray -> JSONObject().put(if (kind == "rooms") "rooms" else "courses", d)
                            else -> JSONObject()
                        }
                        Result(true, data)
                    } else Result(false, error = o.optString("error"), needLogin = o.optBoolean("needLogin"))
                }.getOrElse { Result(false, error = "结果解析失败：${it.message}") }
                finish(r)
            }
        }, "CourseTableBg")
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (injected || finished) return
                injected = true
                // 若被重定向到登录页，直接判定需要登录
                if (Regex("login|cas/", RegexOption.IGNORE_CASE).containsMatchIn(url) && !url.startsWith(anchor)) {
                    finish(Result(false, error = "登录已失效，请重新登录", needLogin = true)); return
                }
                view.evaluateJavascript(js, null)
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) { handler.proceed() }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) finish(Result(false, error = "无法访问教务系统：${error.description}"))
            }
        }
        main.postDelayed({ finish(Result(false, error = "后台执行超时（${TIMEOUT_MS / 1000} 秒）")) }, TIMEOUT_MS)
        web.loadUrl(anchor)
    }

    fun roomParams(repo: CourseRepository, date: java.time.LocalDate, sp: Int, ep: Int, keyword: String): JSONObject {
        val week = repo.currentWeek(date).coerceAtLeast(1)
        val (xnm, xqm) = when {
            date.monthValue >= 8 -> date.year to "3"
            date.monthValue <= 1 -> (date.year - 1) to "3"
            else -> (date.year - 1) to "12"
        }
        return JSONObject().apply {
            put("date", date.toString()); put("week", week); put("weekday", date.dayOfWeek.value)
            put("startPeriod", sp); put("endPeriod", ep); put("keyword", keyword); put("xnm", xnm.toString()); put("xqm", xqm)
            put("semesterStart", repo.settings.semesterStart.toString()); put("totalWeeks", repo.settings.totalWeeks)
        }
    }

    fun roomTitle(date: java.time.LocalDate, sp: Int, ep: Int, keyword: String) =
        "${date.monthValue}月${date.dayOfMonth}日 ${WeekdayNames[date.dayOfWeek.value - 1]} 第${sp}-${ep}节" + (if (keyword.isNotBlank()) " · $keyword" else "")
}
