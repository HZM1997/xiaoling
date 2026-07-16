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

# 保留注解(Compose/反射/序列化需要)
-keepattributes *Annotation*, RuntimeVisible*Annotations, Signature, InnerClasses, EnclosingMethod

# ---- ViewModel:viewModel() 靠反射调用 (Application) 构造函数,必须保留,否则启动即崩 ----
-keep class * extends androidx.lifecycle.ViewModel { <init>(...); }
-keep class * extends androidx.lifecycle.AndroidViewModel { <init>(...); }
-keep class com.xiaoling.core.AppState { *; }

# ---- Kotlin ----
-dontwarn kotlin.**
-keep class kotlin.Metadata { *; }
-keepclassmembers class **$WhenMappings { <fields>; }
-keepclassmembers class kotlin.Metadata { public <methods>; }

# ---- 反射/数据模型:保留成员 ----
-keepclassmembers class com.xiaoling.core.Reply { *; }
-keepclassmembers class com.xiaoling.core.UiState { *; }

# ---- Picovoice Porcupine 用反射调用,保留其类与方法 ----
-keep class ai.picovoice.porcupine.** { *; }
-dontwarn ai.picovoice.porcupine.**

# 说明:去掉了 -repackageclasses / -allowaccessmodification 这两条过激规则,
# 它们会打乱包名与访问修饰符,破坏 ViewModel/Compose 的反射调用,导致 release 版启动崩溃。
# Compose / Coil / OkHttp 各库自带 consumer proguard 规则,无需手写。
