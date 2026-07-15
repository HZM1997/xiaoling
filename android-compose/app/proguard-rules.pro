# ============ 小灵 · release 加固规则(R8) ============

# 去掉所有日志调用(release 不留 Log.*,防信息泄漏 / 减体积)
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
    public static int wtf(...);
}
-assumenosideeffects class java.io.PrintStream {
    public void println(%);
    public void print(%);
}

# 混淆时打乱代码结构,加大逆向难度
-repackageclasses ''
-allowaccessmodification

# 保留注解(Compose/序列化需要)
-keepattributes *Annotation*, RuntimeVisible*Annotations, Signature

# Kotlin
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }

# 反射用到的数据模型:仅保留字段名(供 org.json/UI 读取),类名仍可混淆
-keepclassmembers class com.xiaoling.core.Reply { *; }
-keepclassmembers class com.xiaoling.core.UiState { *; }

# Picovoice Porcupine 用反射调用,保留其类与方法
-keep class ai.picovoice.porcupine.** { *; }
-dontwarn ai.picovoice.porcupine.**

# Compose / Coil / OkHttp 无系统级依赖;各库自带 consumer proguard 规则,无需手写。
# WebView JS 桥若后续用 @JavascriptInterface,需在此 -keep 对应类的方法。
