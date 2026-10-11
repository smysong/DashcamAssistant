# DashcamAssistant proguard 规则（release 暂未开启混淆，保留以便后续阶段启用）
-keep class com.dashcam.assistant.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class com.google.zxing.** { *; }
-dontwarn com.google.zxing.**
