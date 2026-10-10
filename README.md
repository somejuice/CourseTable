# 大学课表 (CourseTable)

Jetpack Compose + Material 3 + Material You（莫奈动态取色）的大学课表应用，
底栏使用 [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)（`io.github.kyant0:backdrop:2.0.1`，Apache-2.0）实现液态玻璃效果。

## 功能
- 周视图课表：节次/时间列、星期+日期表头、今日高亮、按周切换与周次选择、点击空格快速添加课程
- 课程管理：名称/地点/教师、星期、节次范围、周次范围（每周/单周/双周）、10 种颜色
- 今日页：正在上/下一节课卡片、今日/明日课程、全部课程列表
- 设置：开学日期、学期周数、每日节数、显示周末、莫奈动态取色开关、深色模式
- 数据本地持久化（SharedPreferences + JSON），首次启动内置示例课表

## 技术栈
- AGP 9.3.2 / Gradle 9.7.1 / Kotlin 2.4.10（内置 Kotlin）
- compileSdk 37, minSdk 26, targetSdk 37
- Compose BOM 2026.09.00, Material3 1.4.0
- 液态玻璃组件直接使用上游 catalog 源码（`app/src/main/java/com/kyant/backdrop/catalog/`，逐字节与上游一致）：LiquidBottomTabs/LiquidBottomTab（底栏）、LiquidButton（添加/设置页按钮）、LiquidToggle（设置页开关）及其工具类；仅 `utils/Coroutines.kt` 因 expect/actual 改为单平台等价实现
  （折射 lens 需 Android 13+ RuntimeShader，低版本自动降级为模糊）

## 构建
```bash
export JAVA_HOME=<JDK 17>
export ANDROID_HOME=<Android SDK，需 platforms;android-37.0 与 build-tools;37.0.0>
gradle assembleRelease      # 或在 Android Studio 中打开
```
产物：`app/build/outputs/apk/release/app-release.apk`

> 签名使用 `keystore/release.jks`（密码 `coursetable`，仅用于演示，发布请替换）。
