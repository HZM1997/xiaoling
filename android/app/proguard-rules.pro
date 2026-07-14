# 保留讯飞等三方 SDK 类名(混淆会导致反射调用失败)
-keep class com.iflytek.** { *; }
-keep class ai.picovoice.** { *; }
-dontwarn com.iflytek.**
-keepclassmembers class * { public <init>(...); }
