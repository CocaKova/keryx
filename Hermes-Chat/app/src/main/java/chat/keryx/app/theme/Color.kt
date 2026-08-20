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

// Light Mode Colors — 2.5 gives light the pass 2.0 gave the darks. It was stock Material white
// (#FAFAFA/#FFFFFF/#F0F0F0) while the dark side got its whisper of violet, and the mismatch showed:
// the app's whole vocabulary is "color is light, light means life", which on a clinical white sheet
// has no dark for light to mean anything against. So light is not the dark theme inverted — it is
// PAPER AND INK, and the gilt bubble edge is the only thing on it allowed to glow.
//
// Warm, because the default accent is amber and neutral grey next to amber reads as dirty. The
// secondary was also failing WCAG AA outright (#8E8E93 on #FAFAFA is 3.12:1, under the 4.5 a body
// text needs); the replacement measures 5.24:1.
val BackgroundLight = Color(0xFFF6F2EA)     // Parchment
val SurfaceLight = Color(0xFFFDFBF6)        // A fresh leaf laid on it
val SurfaceVariantLight = Color(0xFFEBE5D9) // Pressed tint for user bubbles
val TextPrimaryLight = Color(0xFF1F1B14)    // Warm ink, never pure black — 15.35:1
val TextSecondaryLight = Color(0xFF6B6459)  // Faded ink — 5.24:1, was 3.12:1 and failing
