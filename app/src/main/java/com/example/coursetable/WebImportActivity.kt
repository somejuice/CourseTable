package com.example.coursetable

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * 教务系统一键导入（纯 View 实现，避免 Compose 内嵌 WebView 的布局/渲染问题）：
 * 内置 WebView 登录教务系统 → 注入规则脚本抓取 → 解析为课程 → 返回给设置页确认导入。
 */
class WebImportActivity : ComponentActivity() {
    companion object {
        const val EXTRA_JSON = "json"
        const val EXTRA_SEMESTER_START = "semesterStart"
        const val EXTRA_MODE = "mode"           // "import"（默认）/ "rooms" 空闲教室查询 / "login" 仅登录绑定
        const val EXTRA_URL = "url"             // 初始打开的网址
        private const val PREFS = "web_import"
        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    private lateinit var webView: WebView
    private lateinit var urlEdit: EditText
    private lateinit var statusText: TextView
    private lateinit var progress: ProgressBar
    private lateinit var ruleButton: Button
    private lateinit var grabButton: Button
    private lateinit var guide: View
    private lateinit var repo: CourseRepository
    private var rules: List<ImportRule> = emptyList()
    private var selectedRule: ImportRule? = null
    private var autoRule: ImportRule? = null
    private var currentUrl: String = ""
    private var running = false
    private var errorDetail: String? = null
    private var desktopMode = false
    private var entryUrl: String = ""          // 用户手动输入的入口网址（非 CAS 跳转后的地址）
    private var triedHttpFallback = false
    private var casHintShown = false
    private var roomsMode = false
    private var loginMode = false
    private var bindChecking = false
    private var boundThisSession = false
    private var pendingRoomsQuery = false
    private lateinit var roomsButton: Button

    private fun dp(v: Int): Int = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    @SuppressLint("SetJavaScriptEnabled", "SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = CourseRepository(applicationContext)
        rules = RuleEngine.allRules(this)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        desktopMode = prefs.getBoolean("desktop", false)
        roomsMode = intent.getStringExtra(EXTRA_MODE) == "rooms"
        loginMode = intent.getStringExtra(EXTRA_MODE) == "login"
        boundThisSession = JwSession.isLoggedIn(this) && intent.getStringExtra(EXTRA_URL) == null

        val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        val fg = if (dark) 0xFFEDEDED.toInt() else 0xFF1B1B1B.toInt()
        val muted = if (dark) 0xFFB0B0B0.toInt() else 0xFF666666.toInt()
        val accent = 0xFF5B7BF5.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(bg) }

        // ---- 顶栏：返回 / 地址栏 / 前往 ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), dp(4), dp(6))
        }
        val back = Button(this).apply { text = "‹ 返回"; setTextColor(fg); background = null; setOnClickListener { if (webView.canGoBack()) webView.goBack() else finish() } }
        urlEdit = EditText(this).apply {
            hint = "输入教务系统网址，如 jw.xxx.edu.cn/jwglxt"
            setText(prefs.getString("lastUrl", "") ?: "")
            setTextColor(fg); setHintTextColor(muted); textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) { load(text.toString()); true } else false
            }
        }
        val go = Button(this).apply { text = "前往"; setTextColor(accent); background = null; setOnClickListener { load(urlEdit.text.toString()) } }
        top.addView(back, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        top.addView(urlEdit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        top.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; visibility = View.GONE }
        root.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))

        // ---- 中间：WebView + 首次引导 ----
        val middle = FrameLayout(this)
        webView = WebView(this)
        middle.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        guide = ScrollView(this).apply {
            setBackgroundColor(bg)
            addView(LinearLayout(this@WebImportActivity).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(24), dp(24), dp(24))
                addView(TextView(this@WebImportActivity).apply { text = "教务系统一键导入"; textSize = 22f; setTextColor(fg) })
                addView(TextView(this@WebImportActivity).apply {
                    textSize = 14f; setTextColor(muted); setPadding(0, dp(12), 0, dp(16))
                    text = "1. 在上方地址栏输入学校教务系统网址，点「前往」。建议直接从电脑/手机浏览器地址栏复制平时使用的完整网址（含 https://）\n" +
                            "2. 像平时一样登录，进入「课表查询」页面\n" +
                            "3. 点击下方「抓取课表」，预览确认后导入\n\n" +
                            "已内置：正方 jwglxt、强智 jsxsd、通用表格识别（青果 / 苏亚 / URP 等）。可在设置中导入社区规则文件以适配更多学校。\n\n" +
                            "账号密码仅在本机 WebView 与学校服务器之间传输，应用不会收集。"
                })
                addView(TextView(this@WebImportActivity).apply { text = "常见入口（填好域名后点击补全路径）"; textSize = 13f; setTextColor(fg) })
                listOf("正方 jwglxt（首页，自动走单点登录）" to "/jwglxt/", "正方 jwglxt（本地账号登录）" to "/jwglxt/xtgl/login_slogin.html", "正方 CAS 单点登录入口" to "/jwglxt/sso/jziotlogin", "强智 jsxsd" to "/jsxsd/", "青果 KINGOSOFT" to "/jiaowu/", "URP" to "/login").forEach { (label, path) ->
                    addView(Button(this@WebImportActivity).apply {
                        text = "$label  $path"; isAllCaps = false; gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        setOnClickListener {
                            val host = urlEdit.text.toString().trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
                            if (host.isBlank()) Toast.makeText(context, "请先在地址栏填写学校教务系统域名", Toast.LENGTH_SHORT).show()
                            else { urlEdit.setText("https://$host$path"); load(urlEdit.text.toString()) }
                        }
                    })
                }
            })
        }
        middle.addView(guide, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(middle, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 底栏：状态 + 规则 + 抓取 ----
        val bottom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(8)); setBackgroundColor(bg) }
        statusText = TextView(this).apply { textSize = 12f; setTextColor(muted); maxLines = 3; text = "在上方输入学校教务系统网址并登录，进入课表页面后点击「抓取课表」" }
        bottom.addView(statusText)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        ruleButton = Button(this).apply { isAllCaps = false; textSize = 12f; background = null; setTextColor(accent); gravity = Gravity.START or Gravity.CENTER_VERTICAL; maxLines = 1; setOnClickListener { showMenu() } }
        val detail = Button(this).apply { text = "详情"; background = null; setTextColor(accent); setOnClickListener { errorDetail?.let { showText("脚本输出 / 错误详情", it) } } }
        grabButton = Button(this).apply { text = "抓取课表"; isAllCaps = false; setOnClickListener { runRule() } }
        roomsButton = Button(this).apply { text = "空闲教室"; isAllCaps = false; background = null; setTextColor(accent); setOnClickListener { askRoomsParams() } }
        actions.addView(ruleButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(detail, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (roomsMode) {
            actions.addView(grabButton.apply { background = null; setTextColor(accent) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            actions.addView(roomsButton.apply { background = Button(this@WebImportActivity).background; setTextColor(fg); text = "查询空闲教室" }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            statusText.text = "登录教务系统后点击「查询空闲教室」，选择日期、节次与关键字即可。目前内置支持正方 jwglxt，其它系统可通过规则文件扩展。"
        } else {
            actions.addView(roomsButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            actions.addView(grabButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        bottom.addView(actions)
        root.addView(bottom, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        setContentView(root)
        // 系统栏 / 键盘内边距
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom); insets
        }
        updateRuleButton()

        // ---- WebView 配置 ----
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION") databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
        }
        applyUa()
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.setBackgroundColor(Color.WHITE)
        webView.addJavascriptInterface(Bridge(), "CourseTable")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url.toString()
                return if (u.startsWith("http://") || u.startsWith("https://")) false
                else { runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }; true }
            }
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                currentUrl = url; urlEdit.setText(url); guide.visibility = View.GONE
                progress.visibility = View.VISIBLE
                setStatus("正在加载…")
            }
            override fun onPageFinished(view: WebView, url: String) {
                currentUrl = url; urlEdit.setText(url); guide.visibility = View.GONE
                progress.visibility = View.GONE
                prefs.edit().putString("lastUrl", url).apply()
                autoRule = RuleEngine.pickForUrl(this@WebImportActivity, url)
                updateRuleButton()
                view.evaluateJavascript("(function(){var t=(document.body&&document.body.innerText)||'';return /不允许使用CAS|Application Not Authorized|未授权的服务|service was not authorized/i.test(t)?'1':'0';})()") { r ->
                    if (r?.contains("1") == true && !casHintShown) { casHintShown = true; showCasHint(url) }
                }
                val r = selectedRule ?: autoRule
                if (r != null) tryAutoBind(url, r)
                setStatus(if (r != null) "已识别为「${r.name}」。${r.hint}" else "页面已加载（${view.title ?: ""}）。进入课表页面后点击「抓取课表」；若网址无法自动识别系统，请在左下角手动选择规则")
            }
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                AlertDialog.Builder(this@WebImportActivity)
                    .setTitle("证书不受信任")
                    .setMessage("该站点的 HTTPS 证书无效或为自签名证书（高校教务系统常见）。是否仍然继续访问？\n\n${error.url}")
                    .setPositiveButton("继续访问") { _, _ -> handler.proceed() }
                    .setNegativeButton("取消") { _, _ -> handler.cancel(); setStatus("已取消：证书不受信任") }
                    .setOnCancelListener { handler.cancel() }
                    .show()
            }
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    progress.visibility = View.GONE
                    val u = request.url.toString()
                    if (u.startsWith("https://") && u == entryUrl && !triedHttpFallback && error.errorCode != ERROR_HOST_LOOKUP) {
                        triedHttpFallback = true
                        val http = "http://" + u.removePrefix("https://")
                        setStatus("https 连接失败（${error.description}），自动改用 http 重试…")
                        entryUrl = http; view.loadUrl(http); return
                    }
                    setStatus("加载失败：${error.description}（${error.errorCode}）。可尝试 http/https 互换、菜单中切换“电脑版网页”，或确认已连接校园网 / VPN")
                }
            }
            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                if (request.isForMainFrame) setStatus("服务器返回 HTTP ${errorResponse.statusCode} ${errorResponse.reasonPhrase}")
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
            }
        }

        val initial = intent.getStringExtra(EXTRA_URL)
        if (!initial.isNullOrBlank()) { urlEdit.setText(initial); load(initial) }
        else { val last = prefs.getString("lastUrl", null); if (!last.isNullOrBlank()) load(last) }
        if (loginMode) setStatus("请在此登录教务系统。登录成功后应用会自动绑定账号并返回「我的」页面。")
    }

    /** 页面加载完成后自动检测登录态；成功则锚定当前网址 + 规则，供后台静默抓取使用 */
    private fun tryAutoBind(url: String, rule: ImportRule) {
        if (boundThisSession || bindChecking) return
        if (Regex("login|cas/", RegexOption.IGNORE_CASE).containsMatchIn(url)) return
        bindChecking = true
        val check = rule.loginCheckScript.ifBlank { "return !document.querySelector('input[type=password]') && !/login|cas\\//i.test(location.href);" }
        val js = """
            (async function(){ try { const ok = await (async function(){ $check
            })(); CourseTable.onLoginCheck(ok ? '1' : '0'); } catch (e) { CourseTable.onLoginCheck('0'); } })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        webView.postDelayed({ bindChecking = false }, 15000)
    }

    private fun onBound(rule: ImportRule) {
        boundThisSession = true
        JwSession.bind(this, currentUrl, rule.id)
        setStatus("✓ 已登录并绑定「${rule.name}」，之后课表 / 空闲教室 / 个人信息将在后台静默刷新")
        if (rule.supportsProfile) JwSession.fetchProfile(applicationContext) { }
        if (loginMode) {
            Toast.makeText(this, "登录成功，已绑定教务系统", Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_OK); finish()
        }
    }

    private fun applyUa() {
        webView.settings.userAgentString = if (desktopMode) DESKTOP_UA else WebSettings.getDefaultUserAgent(this).replace("; wv", "")
    }

    private fun load(raw: String) {
        var u = raw.trim()
        if (u.isBlank()) { Toast.makeText(this, "请输入网址", Toast.LENGTH_SHORT).show(); return }
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("lastUrl", u).apply()
        entryUrl = u; triedHttpFallback = false; casHintShown = false
        guide.visibility = View.GONE
        webView.loadUrl(u)
    }

    private fun setStatus(s: String) { runOnUiThread { statusText.text = s } }

    private fun updateRuleButton() {
        ruleButton.text = "规则：" + (selectedRule?.name ?: autoRule?.let { "自动 · " + it.name } ?: "自动识别") + " ▾"
    }

    private fun showMenu() {
        val items = mutableListOf("自动识别（按网址）")
        items.addAll(rules.map { it.name + if (it.builtin) "（内置）" else "（自定义）" })
        items.add(if (desktopMode) "✓ 电脑版网页（UA）" else "电脑版网页（UA）")
        items.add("在外部浏览器打开")
        items.add("清除网页 Cookie / 缓存")
        items.add("WebView 自检")
        AlertDialog.Builder(this).setTitle("解析规则 / 选项").setItems(items.toTypedArray()) { _, which ->
            when {
                which == 0 -> { selectedRule = null; updateRuleButton() }
                which <= rules.size -> { selectedRule = rules[which - 1]; updateRuleButton(); setStatus(selectedRule!!.hint) }
                which == rules.size + 1 -> {
                    desktopMode = !desktopMode
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("desktop", desktopMode).apply()
                    applyUa(); webView.reload()
                }
                which == rules.size + 2 -> runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl.ifBlank { urlEdit.text.toString() }))) }
                which == rules.size + 3 -> {
                    CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush()
                    webView.clearCache(true); webView.clearHistory(); webView.reload()
                }
                else -> {
                    val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
                    val info = "WebView：${pkg?.packageName ?: "未知"} ${pkg?.versionName ?: ""}\nUA：${webView.settings.userAgentString}\n当前网址：$currentUrl\n可返回：${webView.canGoBack()}"
                    webView.loadData("<html><body style='font-family:sans-serif;padding:16px'><h3>WebView 正常</h3><p>如果你能看到这段文字，说明内置浏览器渲染正常。</p><p>请在上方地址栏输入教务系统网址并点击「前往」。</p></body></html>", "text/html; charset=utf-8", "utf-8")
                    showText("WebView 自检", info)
                }
            }
        }.show()
    }

    /** CAS 提示"不允许使用CAS来认证您访问的目标应用"：service 参数与学校注册的地址不一致 */
    private fun showCasHint(casUrl: String) {
        val service = runCatching { Uri.parse(casUrl).getQueryParameter("service") }.getOrNull().orEmpty()
        val entry = entryUrl.ifBlank { currentUrl }
        val entryUri = runCatching { Uri.parse(entry) }.getOrNull()
        val host = entryUri?.host.orEmpty()
        val altScheme = if (entry.startsWith("https://")) "http://" + entry.removePrefix("https://") else "https://" + entry.removePrefix("http://")
        val root = runCatching {
            val seg = entryUri?.pathSegments?.firstOrNull()
            (if (entry.startsWith("https://")) "https://" else "http://") + host + (if (seg != null) "/$seg/" else "/")
        }.getOrDefault(entry)
        val msg = "学校的 CAS 单点登录只认可已登记的回调地址（service），而当前访问产生的地址是：\n$service\n\n" +
                "通常是因为协议（http/https）、域名或入口路径与学校登记的不一致，不是账号问题。可以尝试：\n" +
                "1. 用平时浏览器里的完整网址（含 https://、与学校门户链接一致）重新进入；\n" +
                "2. 切换 http/https；\n" +
                "3. 从教务系统首页（如 /jwglxt/）或学校信息门户进入，而不是直接打开登录页；\n" +
                "4. 若正方系统有本地账号登录页 /jwglxt/xtgl/login_slogin.html，可绕过 CAS 直接登录。"
        AlertDialog.Builder(this).setTitle("CAS 拒绝了当前入口地址").setMessage(msg)
            .setPositiveButton("切换为 ${altScheme.substringBefore("://")} 重试") { _, _ -> urlEdit.setText(altScheme); load(altScheme) }
            .setNeutralButton("从系统首页进入") { _, _ -> urlEdit.setText(root); load(root) }
            .setNegativeButton("本地登录页") { _, _ ->
                val local = (if (entry.startsWith("https://")) "https://" else "http://") + host + "/jwglxt/xtgl/login_slogin.html"
                urlEdit.setText(local); load(local)
            }
            .show()
        setStatus("CAS 拒绝了入口地址（service 不匹配）。请改用平时浏览器中的完整网址或切换 http/https 后重试")
    }

    private fun showText(title: String, text: String) {
        AlertDialog.Builder(this).setTitle(title).setMessage(text)
            .setPositiveButton("关闭", null)
            .setNeutralButton("复制") { _, _ ->
                val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText(title, text)); Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
            }.show()
    }

    // ---------- 空闲教室查询 ----------

    private fun askRoomsParams() {
        if (currentUrl.isBlank()) { setStatus("请先打开教务系统并登录"); return }
        val rule = selectedRule ?: autoRule
        if (rule == null || !rule.supportsFreeRoom) {
            val supported = rules.filter { it.supportsFreeRoom }
            AlertDialog.Builder(this).setTitle("当前规则不支持空闲教室查询")
                .setMessage("支持的规则：" + (if (supported.isEmpty()) "无" else supported.joinToString("、") { it.name }) + "\n\n可在左下角切换规则，或按「规则编写说明」为本校添加 freeRoomScript。")
                .setPositiveButton("知道了", null).show()
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        var date = java.time.LocalDate.now()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val dateBtn = Button(this).apply { isAllCaps = false }
        fun refreshDate() { dateBtn.text = "日期：" + date.toString() + " " + WeekdayNames[date.dayOfWeek.value - 1] + "（第 ${repo.currentWeek(date)} 周）" }
        refreshDate()
        dateBtn.setOnClickListener {
            android.app.DatePickerDialog(this, { _, y, mo, d -> date = java.time.LocalDate.of(y, mo + 1, d); refreshDate() }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val sp = EditText(this).apply { hint = "起始节"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("roomSp", "1")) }
        val ep = EditText(this).apply { hint = "结束节"; inputType = InputType.TYPE_CLASS_NUMBER; setText(prefs.getString("roomEp", "2")) }
        row.addView(TextView(this).apply { text = "节次：第 " })
        row.addView(sp, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = " 节 ～ 第 " })
        row.addView(ep, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(TextView(this).apply { text = " 节" })
        val kw = EditText(this).apply { hint = "教室 / 教学楼关键字（可选，如 教一）"; setText(prefs.getString("roomKw", "")) }
        box.addView(dateBtn); box.addView(row); box.addView(kw)
        if (rule.freeRoomHint.isNotBlank()) box.addView(TextView(this).apply { text = rule.freeRoomHint; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        AlertDialog.Builder(this).setTitle("查询空闲教室").setView(box)
            .setPositiveButton("查询") { _, _ ->
                val s1 = sp.text.toString().toIntOrNull()?.coerceIn(1, 14) ?: 1
                val e1 = (ep.text.toString().toIntOrNull() ?: s1).coerceIn(s1, 14)
                prefs.edit().putString("roomSp", "$s1").putString("roomEp", "$e1").putString("roomKw", kw.text.toString()).apply()
                runRooms(rule, date, s1, e1, kw.text.toString().trim())
            }
            .setNegativeButton("取消", null).show()
    }

    private fun runRooms(rule: ImportRule, date: java.time.LocalDate, sp: Int, ep: Int, keyword: String) {
        val week = repo.currentWeek(date).coerceAtLeast(1)
        // 正方口径：8 月及以后 = 当年第一学期(3)；2–7 月 = 上一年第二学期(12)；1 月 = 上一年第一学期(3)
        val (xnm, xqm) = when {
            date.monthValue >= 8 -> date.year to "3"
            date.monthValue <= 1 -> (date.year - 1) to "3"
            else -> (date.year - 1) to "12"
        }
        val params = JSONObject().apply {
            put("date", date.toString()); put("week", week); put("weekday", date.dayOfWeek.value)
            put("startPeriod", sp); put("endPeriod", ep); put("keyword", keyword); put("xnm", xnm.toString()); put("xqm", xqm)
            put("semesterStart", repo.settings.semesterStart.toString()); put("totalWeeks", repo.settings.totalWeeks)
        }
        running = true; pendingRoomsQuery = true; errorDetail = null
        roomsButton.isEnabled = false
        lastRoomsTitle = "${date.monthValue}月${date.dayOfMonth}日 ${WeekdayNames[date.dayOfWeek.value - 1]} 第${sp}-${ep}节" + (if (keyword.isNotBlank()) " · $keyword" else "")
        setStatus("正在查询 ${date} 第 $sp-$ep 节空闲教室…")
        val js = """
            (async function(){
              const __p = ${params};
              try {
                const __r = await (async function(){ ${rule.freeRoomScript}
                })();
                CourseTable.onRooms(JSON.stringify({ok:true, data: __r}));
              } catch (e) {
                CourseTable.onRooms(JSON.stringify({ok:false, error: String(e && (e.stack || e.message) || e)}));
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        webView.postDelayed({ if (pendingRoomsQuery) { pendingRoomsQuery = false; running = false; roomsButton.isEnabled = true; setStatus("查询超时（20 秒）没有返回") } }, 20000)
    }

    private var lastRoomsTitle = "空闲教室"

    private fun showRooms(data: JSONObject, title: String) {
        val rooms = FreeRoom.fromJson(data.toString())
        if (rooms.isEmpty()) { setStatus("该时段没有查询到空闲教室"); errorDetail = data.toString().take(3000); return }
        FreeRoomsActivity.saveLast(this, data.toString(), title)
        setStatus("查询到 ${rooms.size} 间空闲教室")
        startActivity(Intent(this, FreeRoomsActivity::class.java)
            .putExtra(FreeRoomsActivity.EXTRA_JSON, data.toString()).putExtra(FreeRoomsActivity.EXTRA_TITLE, title))
    }

    private fun runRule() {
        if (currentUrl.isBlank()) { setStatus("请先打开教务系统并登录"); return }
        val rule = selectedRule ?: autoRule ?: run { setStatus("未能根据网址自动识别教务系统，请点左下角「规则」手动选择（可先试“通用表格识别”）"); return }
        running = true; errorDetail = null; grabButton.isEnabled = false; grabButton.text = "抓取中…"
        setStatus("正在用「${rule.name}」抓取…")
        val js = """
            (async function(){
              try {
                const __r = await (async function(){ ${rule.script}
                })();
                CourseTable.onResult(JSON.stringify({ok:true, data: __r}));
              } catch (e) {
                CourseTable.onResult(JSON.stringify({ok:false, error: String(e && (e.stack || e.message) || e)}));
              }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        webView.postDelayed({ if (running) { running = false; grabButton.isEnabled = true; grabButton.text = "抓取课表"; setStatus("脚本超时（20 秒）没有返回，页面可能未加载完成或接口不可用") } }, 20000)
    }

    inner class Bridge {
        @JavascriptInterface
        fun onLoginCheck(ok: String) {
            runOnUiThread {
                bindChecking = false
                val rule = selectedRule ?: autoRule ?: return@runOnUiThread
                if (ok == "1" && !boundThisSession) onBound(rule)
            }
        }

        @JavascriptInterface
        fun onRooms(json: String) {
            runOnUiThread {
                pendingRoomsQuery = false; running = false; roomsButton.isEnabled = true
                runCatching {
                    val o = JSONObject(json)
                    if (!o.optBoolean("ok")) {
                        errorDetail = o.optString("error")
                        setStatus("查询失败：" + errorDetail!!.lineSequence().firstOrNull().orEmpty() + "（点「详情」查看）")
                    } else {
                        val data = o.optJSONObject("data") ?: JSONObject().put("rooms", o.optJSONArray("data") ?: JSONArray())
                        showRooms(data, lastRoomsTitle)
                    }
                }.onFailure { errorDetail = json.take(3000); setStatus("结果解析失败：${it.message}") }
            }
        }

        @JavascriptInterface
        fun onResult(json: String) {
            runOnUiThread {
                running = false; grabButton.isEnabled = true; grabButton.text = "抓取课表"
                runCatching {
                    val o = JSONObject(json)
                    if (!o.optBoolean("ok")) {
                        errorDetail = o.optString("error")
                        setStatus("抓取失败：" + errorDetail!!.lineSequence().firstOrNull().orEmpty() + "（点「详情」查看）")
                    } else {
                        val data = o.optJSONObject("data") ?: JSONObject().put("courses", o.optJSONArray("data") ?: JSONArray())
                        val r = RuleEngine.toCourses(data, repo.settings.totalWeeks, repo.settings.periodsPerDay)
                        if (r.courses.isEmpty()) { errorDetail = data.toString().take(3000); setStatus("脚本执行成功但没有解析到课程（点「详情」查看原始输出）") }
                        else showPreview(r)
                    }
                }.onFailure { errorDetail = json.take(3000); setStatus("结果解析失败：${it.message}") }
            }
        }
    }

    private fun showPreview(r: RuleEngine.Result) {
        val sb = StringBuilder()
        r.courses.take(12).forEach { c ->
            sb.append("· ${c.name} ${WeekdayNames[c.day - 1]} ${c.startPeriod}-${c.endPeriod}节 ${c.weekDescription()}")
            if (c.location.isNotBlank()) sb.append(" @${c.location}")
            sb.append('\n')
        }
        if (r.courses.size > 12) sb.append("… 共 ${r.courses.size} 条\n")
        if (r.warnings.isNotEmpty()) sb.append("\n").append(r.warnings.take(5).joinToString("\n"))
        if (r.semesterStart != null) sb.append("\n开学日期：${r.semesterStart}")
        AlertDialog.Builder(this).setTitle("抓取结果：${r.courses.size} 条").setMessage(sb.toString().trim())
            .setPositiveButton("下一步：导入") { _, _ ->
                val json = JSONObject().apply {
                    put("app", "CourseTable"); put("version", 1)
                    put("courses", JSONArray().also { a -> r.courses.forEach { a.put(it.toJson()) } })
                }.toString()
                setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_JSON, json).putExtra(EXTRA_SEMESTER_START, r.semesterStart))
                finish()
            }
            .setNegativeButton("取消", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (this::webView.isInitialized && webView.canGoBack()) webView.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) { (webView.parent as? ViewGroup)?.removeView(webView); webView.destroy() }
        super.onDestroy()
    }
}
