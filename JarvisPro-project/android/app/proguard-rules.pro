# Jarvis Pro — keep JS bridges and native ONNX/llama entry points.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.jarvis.app.pro.ProActivity$** { *; }
-dontwarn com.llama.**
