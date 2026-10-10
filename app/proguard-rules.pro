-keep class com.kyant.backdrop.** { *; }
-dontwarn org.jetbrains.annotations.**

# WebView JS bridge
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
