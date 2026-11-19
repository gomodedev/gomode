// Material theme for the Go Mode Android shell.
//
// Gomode.dev's Brand table supplies the two palettes. Paper and primary come from Android color
// resources so the launch window uses the same scheme as Compose. Containers carry
// the surface hierarchy, and tertiary keeps active tool names readable on the tinted voice panel.
package com.fghbuild.gomode.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import com.fghbuild.gomode.R

private val LightInk = Color(0xFF0D2148)
private val LightBrandStrong = Color(0xFF174878)
private val LightBrandContainer = Color(0xFFCFE2EF)
private val DarkInk = Color(0xFFEAF2F8)
private val DarkBrandStrong = Color(0xFF8FD6EE)
private val DarkBrandContainer = Color(0xFF214661)

private val LightColors =
    lightColorScheme(
        onPrimary = Color.White,
        primaryContainer = LightBrandContainer,
        onPrimaryContainer = LightBrandStrong,
        secondary = LightBrandStrong,
        onSecondary = Color.White,
        secondaryContainer = LightBrandContainer,
        onSecondaryContainer = LightInk,
        tertiary = Color(0xFFA14A08),
        onTertiary = Color.White,
        onBackground = LightInk,
        onSurface = LightInk,
        surfaceVariant = Color(0xFFE4EDF3),
        onSurfaceVariant = Color(0xFF47596B),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFEEF3F7),
        surfaceContainer = Color(0xFFE9F0F5),
        surfaceContainerHigh = Color(0xFFE4EDF3),
        surfaceContainerHighest = Color(0xFFDCE7EE),
        outline = Color(0xFF71849A),
        outlineVariant = Color(0xFFC4D3DF),
        inversePrimary = DarkBrandStrong,
        inverseSurface = Color(0xFF172A40),
        inverseOnSurface = DarkInk,
    )

private val DarkColors =
    darkColorScheme(
        onPrimary = LightInk,
        primaryContainer = DarkBrandContainer,
        onPrimaryContainer = DarkBrandStrong,
        secondary = DarkBrandStrong,
        onSecondary = LightInk,
        secondaryContainer = DarkBrandContainer,
        onSecondaryContainer = DarkInk,
        tertiary = Color(0xFFFFBA75),
        onTertiary = LightInk,
        onBackground = DarkInk,
        onSurface = DarkInk,
        surfaceVariant = Color(0xFF172A40),
        onSurfaceVariant = Color(0xFFBCCDDB),
        surfaceContainerLowest = Color(0xFF091522),
        surfaceContainerLow = Color(0xFF101F31),
        surfaceContainer = Color(0xFF132437),
        surfaceContainerHigh = Color(0xFF172A40),
        surfaceContainerHighest = Color(0xFF20374D),
        outline = Color(0xFF798EA3),
        outlineVariant = Color(0xFF3D536B),
        inversePrimary = LightBrandStrong,
        inverseSurface = Color(0xFFE4EDF3),
        inverseOnSurface = LightInk,
    )

@Composable
fun GoModeTheme(content: @Composable () -> Unit) {
    val base = if (isSystemInDarkTheme()) DarkColors else LightColors
    val paper = colorResource(R.color.gomode_paper)
    val primary = colorResource(R.color.gomode_primary)
    MaterialTheme(
        colorScheme = base.copy(primary = primary, surfaceTint = primary, background = paper, surface = paper),
        content = content,
    )
}
