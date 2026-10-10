package com.kyant.backdrop.catalog.utils

// 上游为 KMP expect/actual 声明（commonMain + androidMain），单平台 Android 工程无法使用 expect/actual，
// 这里以等价的 Android 实现提供同名函数，实现体与上游 androidMain 完全一致。
import kotlinx.coroutines.android.awaitFrame as androidAwaitFrame

suspend fun awaitFrame() {
    androidAwaitFrame()
}
