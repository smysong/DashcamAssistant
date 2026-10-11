package com.dashcam.assistant

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕尺寸分档（不引入 material3-window-size-class 依赖，按 dp 宽度自行判定）：
 * - COMPACT：手机竖屏（< 600dp）
 * - MEDIUM：大手机横屏 / 平板竖屏（600–840dp）
 * - EXPANDED：平板横屏 / 车机（≥ 840dp）
 *
 * 用 LocalConfiguration 而非 BoxWithConstraints：旋转/分屏时配置变化会触发重组，
 * 且可在任意层级读取，不必把约束一路传下去。
 */
internal enum class DashcamWindowClass { COMPACT, MEDIUM, EXPANDED }

@Composable
internal fun dashcamWindowClass(): DashcamWindowClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp < 600 -> DashcamWindowClass.COMPACT
        widthDp < 840 -> DashcamWindowClass.MEDIUM
        else -> DashcamWindowClass.EXPANDED
    }
}

/** 由屏幕档位派生的一组布局参数，避免各页面各写一套阈值。 */
internal data class DashcamLayoutMetrics(
    val homeHorizontalPadding: Dp,
    val homeVerticalPadding: Dp,
    val browserHorizontalPadding: Dp,
    val browserVerticalPadding: Dp,
    val gridMinCell: Dp,
    val twoPaneHome: Boolean,
    val scrollableTabs: Boolean
)

internal fun dashcamLayoutMetrics(windowClass: DashcamWindowClass): DashcamLayoutMetrics =
    when (windowClass) {
        DashcamWindowClass.COMPACT -> DashcamLayoutMetrics(
            homeHorizontalPadding = 20.dp,
            homeVerticalPadding = 20.dp,
            browserHorizontalPadding = 16.dp,
            browserVerticalPadding = 14.dp,
            gridMinCell = 200.dp,
            twoPaneHome = false,
            scrollableTabs = true
        )
        DashcamWindowClass.MEDIUM -> DashcamLayoutMetrics(
            homeHorizontalPadding = 32.dp,
            homeVerticalPadding = 24.dp,
            browserHorizontalPadding = 24.dp,
            browserVerticalPadding = 18.dp,
            gridMinCell = 220.dp,
            twoPaneHome = false,
            scrollableTabs = false
        )
        DashcamWindowClass.EXPANDED -> DashcamLayoutMetrics(
            homeHorizontalPadding = 40.dp,
            homeVerticalPadding = 28.dp,
            browserHorizontalPadding = 32.dp,
            browserVerticalPadding = 20.dp,
            gridMinCell = 240.dp,
            twoPaneHome = true,
            scrollableTabs = false
        )
    }
