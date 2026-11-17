// Material theme for the Go Mode Android shell.
//
// The palette is gomode.dev's README Brand table, taken from the launcher icon's brain gradient,
// so the app matches its websites. Every slot the app reads is set; an omitted slot would fall back
// to Material's baseline purple. Brand roles: brand #1d6a96 is primary, brand strong #174878 is
// secondary, paper #f4f8fb is the background, surface #e4edf3 is the variant and high container,
// ink #0d2148 is text, muted #47596b is secondary text, line #c4d3df divides, and accent text
// #a14a08 is tertiary, so active tool names keep 4.5:1 on the voice panel's tonal surface. Containers
// and the remaining surface tones are tints between paper and the brand.
// Text pairs meet WCAG AA 4.5:1 and outline meets 3:1 on every container.
//
// TODO: Add the dark scheme from the Brand table's Dark column. Compose always renders this light
// scheme, while values-night/themes.xml selects a dark window and a #111411 navigation bar, so dark
// mode shows a dark frame around light content. Choose with isSystemInDarkTheme(), match the night
// window colors, and repeat the contrast checks; documentation captures stay in light mode.
package com.fghbuild.gomode.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Paper = Color(0xFFF4F8FB)
private val Ink = Color(0xFF0D2148)
private val BrandContainer = Color(0xFFCFE2EF)

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1D6A96),
        onPrimary = Color.White,
        primaryContainer = BrandContainer,
        onPrimaryContainer = Color(0xFF174878),
        secondary = Color(0xFF174878),
        onSecondary = Color.White,
        secondaryContainer = BrandContainer,
        onSecondaryContainer = Ink,
        tertiary = Color(0xFFA14A08),
        onTertiary = Color.White,
        background = Paper,
        onBackground = Ink,
        surface = Paper,
        onSurface = Ink,
        surfaceTint = Color(0xFF1D6A96),
        surfaceVariant = Color(0xFFE4EDF3),
        onSurfaceVariant = Color(0xFF47596B),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFEEF3F7),
        surfaceContainer = Color(0xFFE9F0F5),
        surfaceContainerHigh = Color(0xFFE4EDF3),
        surfaceContainerHighest = Color(0xFFDCE7EE),
        outline = Color(0xFF71849A),
        outlineVariant = Color(0xFFC4D3DF),
    )

@Composable
fun GoModeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LightColors,
        content = content,
    )
}
