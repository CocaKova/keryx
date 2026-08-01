package chat.keryx.app.theme

import androidx.compose.ui.graphics.Color

val HermesAmber = Color(0xFFE55A00) // Classic Hermes Accent

// Dark Mode Colors — 2.0 gives the elevated darks a whisper of violet so surfaces read as
// night air over the OLED void rather than gray cards on black. Background stays pure black.
val BackgroundDark = Color(0xFF000000) // True OLED Black
val SurfaceDark = Color(0xFF12121A)    // Very deep elevated dark, violet-cast
val SurfaceVariantDark = Color(0xFF1D1D28) // Soft night slate for user bubbles
val TextPrimaryDark = Color(0xFFFFFFFF)
val TextSecondaryDark = Color(0xFFA6A0B5) // Starlight gray

// Light Mode Colors
val BackgroundLight = Color(0xFFFAFAFA) // Clean Off-White
val SurfaceLight = Color(0xFFFFFFFF)    // Pristine White
val SurfaceVariantLight = Color(0xFFF0F0F0) // Subtle gray for user bubbles
val TextPrimaryLight = Color(0xFF1C1C1E)
val TextSecondaryLight = Color(0xFF8E8E93)
