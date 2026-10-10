package com.example.coursetable

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.blur
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.catalog.components.LiquidBottomTab
import com.kyant.backdrop.catalog.components.LiquidBottomTabs
import com.kyant.backdrop.catalog.components.LiquidButton

class MainActivity : ComponentActivity() {
    override fun onStop() {
        super.onStop()
        Thread { runCatching { WidgetUpdater.updateAll(applicationContext) } }.start()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val repo = CourseRepository(applicationContext)
        Thread {
            runCatching { ReminderScheduler.schedule(applicationContext, repo) }
            runCatching { WidgetUpdater.updateAll(applicationContext, repo) }
        }.start()
        setContent {
            CourseTableTheme(settings = repo.settings) {
                CourseTableApp(repo)
            }
        }
    }
}

data class BottomTab(val label: String, val icon: ImageVector)

private sealed interface EditorState {
    data object Hidden : EditorState
    data class Editing(val course: Course, val isNew: Boolean) : EditorState
}

@Composable
fun CourseTableApp(repo: CourseRepository) {
    val tabs = remember {
        listOf(
            BottomTab("课表", Icons.Filled.DateRange),
            BottomTab("今日", Icons.AutoMirrored.Filled.List),
            BottomTab("待办", Icons.Filled.CheckCircle)
        )
    }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }   // 0 课表 1 今日 2 待办 3 我的 4 设置
    var lastBarTab by rememberSaveable { mutableIntStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var todoAddRequest by remember { mutableIntStateOf(0) }
    if (selectedTab >= 3) BackHandler(enabled = true) { selectedTab = lastBarTab }
    if (menuOpen) BackHandler { menuOpen = false }
    var editor by remember { mutableStateOf<EditorState>(EditorState.Hidden) }
    var clipboard by remember { mutableStateOf<Course?>(null) }

    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = rememberLayerBackdrop {
        drawRect(backgroundColor)
        drawContent()
    }

    val statusBar = WindowInsets.statusBars.asPaddingValues()
    val navBar = WindowInsets.navigationBars.asPaddingValues()
    val bottomBarSpace = 64.dp + 16.dp + navBar.calculateBottomPadding()
    val contentPadding = PaddingValues(top = statusBar.calculateTopPadding(), bottom = bottomBarSpace + 8.dp)

    Surface(Modifier.fillMaxSize(), color = backgroundColor) {
        Box(Modifier.fillMaxSize()) {
            // 页面内容，作为液态玻璃的背景层
            Box(
                Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop)
                    .background(backgroundColor)
            ) {
                WallpaperLayer(repo.settings)
                // 课表页常驻组合（最重的页面），切换时只做透明度/位移动画，避免重新组合造成顿挫
                val timetableVisible = selectedTab == 0
                // 与 AnimatedContent 的页面过渡保持一致：淡入 220 / 淡出 180，位移 260
                val ttAlpha by androidx.compose.animation.core.animateFloatAsState(
                    if (timetableVisible) 1f else 0f,
                    androidx.compose.animation.core.tween(if (timetableVisible) 220 else 180), label = "ttAlpha")
                val ttShift by androidx.compose.animation.core.animateFloatAsState(
                    if (timetableVisible) 0f else -1f,
                    androidx.compose.animation.core.tween(260), label = "ttShift")
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        alpha = ttAlpha
                        translationX = ttShift * size.width / 8f
                    }.then(if (!timetableVisible) Modifier.pointerInput(Unit) {
                        awaitPointerEventScope { while (true) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial).changes.forEach { it.consume() } } }
                    } else Modifier)
                ) {
                    TimetableScreen(
                            repo = repo,
                            contentPadding = contentPadding,
                            onAddCourse = { day, period ->
                                editor = EditorState.Editing(
                                    Course(
                                        name = "", day = day, startPeriod = period,
                                        endPeriod = (period + 1).coerceAtMost(repo.settings.periodsPerDay),
                                        startWeek = 1, endWeek = repo.settings.totalWeeks,
                                        colorIndex = repo.courses.size % CourseColors.size
                                    ),
                                    isNew = true
                                )
                            },
                            onEditCourse = { editor = EditorState.Editing(it, isNew = false) },
                            clipboard = clipboard,
                            onCopy = { clipboard = it }
                        )
                }
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        val dir = if (targetState > initialState) 1 else -1
                        (slideInHorizontally(androidx.compose.animation.core.tween(260)) { it / 8 * dir } + fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
                                (slideOutHorizontally(androidx.compose.animation.core.tween(260)) { -it / 8 * dir } + fadeOut(androidx.compose.animation.core.tween(180)))
                    },
                    label = "tabs"
                ) { tab ->
                    when (tab) {
                        0 -> Box(Modifier.fillMaxSize())
                        1 -> CoursesScreen(repo, contentPadding, onOpenDDay = { selectedTab = 4 }) { editor = EditorState.Editing(it, isNew = false) }
                        2 -> TodoScreen(repo, contentPadding, addRequest = todoAddRequest, onAddConsumed = { todoAddRequest = 0 })
                        3 -> MyScreen(repo, contentPadding)
                        else -> SettingsScreen(repo, contentPadding)
                    }
                }
            }

            // 液态玻璃底栏：左侧「+」菜单按钮（上游 LiquidButton）+ 上游 LiquidBottomTabs
            val tabContentColor = MaterialTheme.colorScheme.onSurface
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = navBar.calculateBottomPadding() + 12.dp)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 进入「我的 / 设置」时底栏向下滑出屏幕
                val barHidden = selectedTab >= tabs.size
                val barShift by androidx.compose.animation.core.animateFloatAsState(
                    if (barHidden) 1f else 0f,
                    animationSpec = if (barHidden) androidx.compose.animation.core.tween(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                                    else androidx.compose.animation.core.spring(dampingRatio = 0.7f, stiffness = 380f),
                    label = "barShift"
                )
                // 上游 LiquidBottomTabs 通过 isSystemInDarkTheme() 取色；这里按应用实际主题（含手动浅色 / 深色）覆盖 uiMode，使浅色模式正确适配
                val appDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                val baseCfg = androidx.compose.ui.platform.LocalConfiguration.current
                val themedCfg = remember(baseCfg, appDark) {
                    android.content.res.Configuration(baseCfg).apply {
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                                (if (appDark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO)
                    }
                }
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalConfiguration provides themedCfg) {
                    LiquidBottomTabs(
                        selectedTabIndex = { if (selectedTab < tabs.size) selectedTab else lastBarTab },
                        onTabSelected = { selectedTab = it; lastBarTab = it },
                        backdrop = backdrop,
                        tabsCount = tabs.size,
                        modifier = Modifier.weight(1f).graphicsLayer {
                            translationY = barShift * (size.height + 48.dp.toPx() + navBar.calculateBottomPadding().toPx())
                            alpha = 1f - barShift * 0.3f
                        }
                    ) {
                        tabs.forEachIndexed { index, tab ->
                            LiquidBottomTab({ selectedTab = index; lastBarTab = index }) {
                                Icon(tab.icon, contentDescription = tab.label, tint = tabContentColor, modifier = Modifier.size(26.dp))
                                Text(tab.label, color = tabContentColor, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
                LiquidButton(
                    onClick = { menuOpen = !menuOpen },
                    backdrop = backdrop,
                    surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    modifier = Modifier.padding(start = 10.dp).size(56.dp)   // 正方形 + Capsule = 标准圆形
                ) {
                    val rot by androidx.compose.animation.core.animateFloatAsState(
                        if (menuOpen) 45f else 0f,
                        animationSpec = androidx.compose.animation.core.spring(dampingRatio = 0.48f, stiffness = 500f),
                        label = "plus"
                    )
                    Icon(Icons.Filled.Add, contentDescription = "菜单", tint = tabContentColor,
                        modifier = Modifier.requiredSize(28.dp).graphicsLayer { rotationZ = rot })
                }
            }

            // 「+」弹出菜单：点击空白处关闭
            if (menuOpen) {
                Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { menuOpen = false })
            }
            // 弹跳：用关键帧精确设计（0.6 → 1.06 → 0.98 → 1.0），比弹簧更可控；位移同样带一次过冲
            AnimatedVisibility(
                visible = menuOpen,
                enter = fadeIn(androidx.compose.animation.core.tween(140)),
                exit = fadeOut(androidx.compose.animation.core.tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = navBar.calculateBottomPadding() + 12.dp + 64.dp + 10.dp, end = 20.dp)
            ) {
                // 投影淡入与内容"由糊变清"共用 AnimatedVisibility 的 transition：与菜单弹出动画同时开始、同时结束
                val reveal by transition.animateFloat(
                    transitionSpec = {
                        if (targetState == androidx.compose.animation.EnterExitState.Visible)
                            androidx.compose.animation.core.tween(400, easing = androidx.compose.animation.core.LinearOutSlowInEasing)
                        else androidx.compose.animation.core.tween(160)
                    },
                    label = "menuReveal"
                ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
                // 模糊比投影更早结束：reveal 到 0.7 时已完全清晰（约 360 ms），投影继续淡入到 520 ms
                // 模糊：起始 48dp，用平方根曲线让前半段保持明显模糊，在 reveal≈0.75 时清晰
                val blurT = (reveal / 0.9f).coerceIn(0f, 1f)
                val menuBlurDp = 64f * (1f - kotlin.math.sqrt(blurT))
                val menuScale by transition.animateFloat(
                    transitionSpec = {
                        if (targetState == androidx.compose.animation.EnterExitState.Visible)
                            androidx.compose.animation.core.keyframes {
                                durationMillis = 400
                                0.75f at 0 using androidx.compose.animation.core.LinearOutSlowInEasing
                                1.025f at 225 using androidx.compose.animation.core.FastOutSlowInEasing
                                0.995f at 325 using androidx.compose.animation.core.FastOutSlowInEasing
                                1f at 400
                            }
                        else androidx.compose.animation.core.tween(150)
                    }, label = "menuScale"
                ) { when (it) { androidx.compose.animation.EnterExitState.PreEnter -> 0.75f; androidx.compose.animation.EnterExitState.Visible -> 1f; else -> 0.88f } }
                val menuRise by transition.animateFloat(
                    transitionSpec = {
                        if (targetState == androidx.compose.animation.EnterExitState.Visible)
                            androidx.compose.animation.core.keyframes {
                                durationMillis = 400
                                1f at 0 using androidx.compose.animation.core.LinearOutSlowInEasing
                                -0.05f at 235 using androidx.compose.animation.core.FastOutSlowInEasing
                                0.01f at 335 using androidx.compose.animation.core.FastOutSlowInEasing
                                0f at 400
                            }
                        else androidx.compose.animation.core.tween(150)
                    }, label = "menuRise"
                ) { when (it) { androidx.compose.animation.EnterExitState.PreEnter -> 1f; androidx.compose.animation.EnterExitState.Visible -> 0f; else -> 0.2f } }
                LiquidMenu(
                    backdrop = backdrop, surfaceColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f),
                    shadowAlpha = { reveal },
                    modifier = Modifier.width(200.dp).graphicsLayer {
                        // 关键帧在 PreEnter 状态下初值即为 0.6 / 1f（见 keyframes 的 0ms 帧），进入后沿关键帧弹跳
                        scaleX = menuScale; scaleY = menuScale
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.92f, 1.05f)
                        translationY = menuRise * size.height * 0.35f
                    }
                ) {
                  androidx.compose.foundation.layout.Column(
                      Modifier.graphicsLayer { alpha = 0.25f + 0.75f * blurT }
                          .then(if (menuBlurDp > 0.1f) Modifier.blur(menuBlurDp.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Rectangle) else Modifier)
                  ) {
                    LiquidMenuItem(Icons.Filled.Add, "添加课程", tabContentColor) {
                        menuOpen = false
                        editor = EditorState.Editing(
                            Course(name = "", endWeek = repo.settings.totalWeeks, colorIndex = repo.courses.size % CourseColors.size),
                            isNew = true
                        )
                    }
                    if (selectedTab == 2) LiquidMenuItem(Icons.Filled.CheckCircle, "添加待办", tabContentColor) { menuOpen = false; todoAddRequest++ }
                    if (selectedTab != 3) LiquidMenuItem(Icons.Filled.Person, "我的", tabContentColor) { menuOpen = false; selectedTab = 3 }
                    if (selectedTab != 4) LiquidMenuItem(Icons.Filled.Settings, "设置", tabContentColor) { menuOpen = false; selectedTab = 4 }
                    if (selectedTab >= 3) LiquidMenuItem(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "返回" + tabs[lastBarTab].label, tabContentColor) { menuOpen = false; selectedTab = lastBarTab }
                  }
                }
            }

            // 课程编辑页（全屏覆盖）
            val state = editor
            var lastEditing by remember { mutableStateOf<EditorState.Editing?>(null) }
            if (state is EditorState.Editing) lastEditing = state
            if (state is EditorState.Editing) BackHandler { editor = EditorState.Hidden }
            AnimatedVisibility(
                visible = state is EditorState.Editing,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut()
            ) {
                val s = lastEditing ?: return@AnimatedVisibility
                Surface(Modifier.fillMaxSize(), color = backgroundColor) {
                  Box(Modifier.fillMaxSize().padding(top = statusBar.calculateTopPadding())) {
                    CourseEditorScreen(
                        initial = s.course,
                        isNew = s.isNew,
                        totalWeeks = repo.settings.totalWeeks,
                        periodsPerDay = repo.settings.periodsPerDay,
                        templates = repo.courses.filter { it.id != s.course.id }.distinctBy { it.name },
                        onSave = { repo.upsert(it); editor = EditorState.Hidden },
                        onDelete = { repo.delete(s.course.id); editor = EditorState.Hidden },
                        onBack = { editor = EditorState.Hidden }
                    )
                  }
                }
            }
        }
    }
}


/** 用户自定义壁纸层（铺满、可模糊、带遮罩） */
@Composable
fun WallpaperLayer(settings: AppSettings) {
    if (settings.wallpaper.isBlank()) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap = remember(settings.wallpaper) {
        runCatching {
            val f = java.io.File(context.filesDir, settings.wallpaper)
            if (!f.exists()) return@runCatching null
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(f.path, opts)
            var sample = 1
            while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 2400) sample *= 2
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
                ?.asImageBitmap()
        }.getOrNull()
    } ?: return
    val scrim = MaterialTheme.colorScheme.background
    androidx.compose.foundation.Image(
        bitmap = bitmap, contentDescription = null,
        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
        modifier = Modifier.fillMaxSize().then(
            if (settings.wallpaperBlur > 0) Modifier.blur(settings.wallpaperBlur.dp, edgeTreatment = androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded) else Modifier
        )
    )
    Box(Modifier.fillMaxSize().background(scrim.copy(alpha = settings.wallpaperDim.coerceIn(0f, 1f))))
}
