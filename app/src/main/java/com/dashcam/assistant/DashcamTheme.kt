package com.dashcam.assistant

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.core.view.WindowCompat

internal val DashcamAccent = Color(0xFF35C6D9)
internal val DashcamEmergency = Color(0xFFF0B34A)
internal val DashcamError = Color(0xFFF16C68)

/** “已连接 / 成功”语义色：深色底用亮绿，浅色底用深绿（保证两种主题下都满足对比度）。 */
internal val DashcamConnectedDark = Color(0xFF38C987)
internal val DashcamConnectedLight = Color(0xFF12784A)

/** 成功色：依据当前背景明暗自动取深/浅版本，两种主题下都保证对比度。 */
@Composable
internal fun dashcamSuccessColor(): Color {
    val background = MaterialTheme.colorScheme.background
    val luminance = background.red * 0.299f + background.green * 0.587f + background.blue * 0.114f
    return if (luminance < 0.5f) DashcamConnectedDark else DashcamConnectedLight
}

private val GraphiteColorScheme = darkColorScheme(
    primary = DashcamAccent,
    onPrimary = Color(0xFF06151A),
    primaryContainer = Color(0xFF0B4A56),
    onPrimaryContainer = Color(0xFFB2E7F0),
    secondary = Color(0xFF86DDE7),
    onSecondary = Color(0xFF10262B),
    // FilledTonalButton 默认取 secondaryContainer：必须给主题内的青色系值，
    // 否则会落到 Material 基线淡紫，与全 App 主色割裂（按钮配色不统一的根因）
    secondaryContainer = Color(0xFF0E3943),
    onSecondaryContainer = Color(0xFFA9DDE8),
    tertiary = DashcamEmergency,
    background = Color(0xFF0B1118),
    onBackground = Color(0xFFF2F6F8),
    surface = Color(0xFF141E28),
    onSurface = Color(0xFFF2F6F8),
    surfaceVariant = Color(0xFF1C2A36),
    onSurfaceVariant = Color(0xFFA8B6C2),
    outline = Color(0xFF2B3B49),
    error = DashcamError,
    onError = Color(0xFF2B0C0A),
    errorContainer = Color(0xFF5C1A16),
    onErrorContainer = Color(0xFFF2B8B5)
)

private val IceColorScheme = lightColorScheme(
    primary = Color(0xFF087E91),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9E8F0),
    onPrimaryContainer = Color(0xFF06333C),
    secondary = Color(0xFF176A79),
    // 同上：给主题内的青色系值，避免 FilledTonalButton 落到基线淡紫
    secondaryContainer = Color(0xFFD2ECF2),
    onSecondaryContainer = Color(0xFF0A4B57),
    tertiary = Color(0xFF8A5700),
    background = Color(0xFFF2F6F8),
    onBackground = Color(0xFF15232C),
    surface = Color.White,
    onSurface = Color(0xFF15232C),
    surfaceVariant = Color(0xFFE5EDF1),
    onSurfaceVariant = Color(0xFF51626E),
    outline = Color(0xFFCCD8DE),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

private val DashcamTypography = Typography(
    headlineLarge = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineMedium = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    labelLarge = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp),
    labelMedium = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
    labelSmall = TextStyle(fontSize = 14.sp, lineHeight = 18.sp)
)

private val DashcamShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

/**
 * 让状态栏与导航栏完全跟随当前主题：
 * - 背景色 = 当前配色方案的 background（与内容区连成一片，无割裂）；
 * - 图标/文字：浅色主题用深色（isAppearanceLight* = true），深色主题用浅色。
 * 注：不做 edge-to-edge，内容区不延伸到系统栏下方，车机横屏也不会被挖孔/圆角裁切。
 */
/**
 * 命令式同步系统栏（Activity 生命周期各节点调用）。
 * 车机 SystemUI 的状态栏/Dock 有时只在窗口创建时读取主题属性，仅靠 Compose 侧设置不一定生效，
 * 因此在 onCreate / onConfigurationChanged / onResume 都再刷一次。
 */
internal fun applySystemBarsToActivity(activity: Activity, darkTheme: Boolean) {
    val window = activity.window
    val barColor = (if (darkTheme) GraphiteColorScheme else IceColorScheme).background.toArgb()
    window.statusBarColor = barColor
    window.navigationBarColor = barColor
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        window.isNavigationBarContrastEnforced = false
    }
    WindowCompat.getInsetsController(window, window.decorView).apply {
        isAppearanceLightStatusBars = !darkTheme
        isAppearanceLightNavigationBars = !darkTheme
    }
}

@Composable
private fun ApplySystemBars(darkTheme: Boolean, colorScheme: ColorScheme) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val barColor = colorScheme.background.toArgb()
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 关闭系统强制对比度，否则三键导航条会被叠一层灰底，与内容区不一致
            window.isNavigationBarContrastEnforced = false
        }
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = !darkTheme
            isAppearanceLightNavigationBars = !darkTheme
        }
    }
}

@Composable
internal fun DashcamAssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) GraphiteColorScheme else IceColorScheme
    ApplySystemBars(darkTheme = darkTheme, colorScheme = colorScheme)
    MaterialTheme(
        colorScheme = colorScheme,
        typography = DashcamTypography,
        shapes = DashcamShapes
    ) {
        // Material3 的 MaterialTheme 不提供 LocalContentColor，未提供时 Compose 默认是黑色；
        // 在 Surface/Card 之外（如媒体库的 Box 根容器）的 Text 若不显式指定颜色，
        // 深色主题下就会变成“黑字黑底”。这里统一兜底为 onBackground。
        CompositionLocalProvider(LocalContentColor provides colorScheme.onBackground, content = content)
    }
}
