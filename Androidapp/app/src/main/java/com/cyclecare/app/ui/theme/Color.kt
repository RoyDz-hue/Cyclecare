package com.cyclecare.app.ui.theme

import androidx.compose.ui.graphics.Color

// Quiet Depth: warm mineral neutrals, deep rose, botanical sage, and soft gold.
// Keep these shared tokens stable so the native app stays visually coherent.

// Primary brand — deep rose
val RosePrimary = Color(0xFF803A46)
val RoseOnPrimary = Color(0xFFFFFFFF)
val RosePrimaryContainer = Color(0xFFF6DCE0)
val RoseOnPrimaryContainer = Color(0xFF35161B)
val RoseInversePrimary = Color(0xFFFFB2BB)

// Dark mode primary
val RoseDarkPrimary = Color(0xFFFFB2BB)
val RoseDarkOnPrimary = Color(0xFF551520)
val RoseDarkPrimaryContainer = Color(0xFF6E2E3B)
val RoseDarkOnPrimaryContainer = Color(0xFFFFD9DD)

// Legacy alias compatibility
val RoseDark = Color(0xFF6E2E3B)
val RoseLight = Color(0xFFB65B68)
val RoseContainer = RosePrimaryContainer
val OnRoseContainer = RoseOnPrimaryContainer

// Secondary brand — botanical sage. Names retained for source compatibility.
val AmberSecondary = Color(0xFF4D6B58)
val AmberOnSecondary = Color(0xFFFFFFFF)
val AmberSecondaryContainer = Color(0xFFD5EBDD)
val AmberOnSecondaryContainer = Color(0xFF163224)

val AmberDarkSecondary = Color(0xFFB2D4BA)
val AmberDarkOnSecondary = Color(0xFF173623)
val AmberDarkSecondaryContainer = Color(0xFF2E4F3B)
val AmberDarkOnSecondaryContainer = Color(0xFFD5EBDD)

// Legacy alias compatibility
val AmberContainer = AmberSecondaryContainer
val OnAmberContainer = AmberOnSecondaryContainer

// Tertiary brand — quiet, legible gold
val PurpleTertiary = Color(0xFF6F4D1B)
val PurpleOnTertiary = Color(0xFFFFFFFF)
val PurpleTertiaryContainer = Color(0xFFF5E4B9)
val PurpleOnTertiaryContainer = Color(0xFF2B1B00)

val PurpleDarkTertiary = Color(0xFFE9C080)
val PurpleDarkOnTertiary = Color(0xFF3B290B)
val PurpleDarkTertiaryContainer = Color(0xFF5D420D)
val PurpleDarkOnTertiaryContainer = Color(0xFFFFDEAC)

// Legacy alias compatibility
val PurpleContainer = PurpleTertiaryContainer

// Cycle-phase tones — intentionally distinct without relying on color alone.
val LutealBlue = Color(0xFF58624F)
val LutealContainer = Color(0xFFE5EBDD)
val OvulationTeal = Color(0xFF7A581D)
val OvulationContainer = Color(0xFFF5E4B9)
val MenstrualCrimson = Color(0xFF803A46)
val FollicularEmerald = Color(0xFF4D6B58)

// Light surface ladder — pearl and warm paper
val BackgroundWarm = Color(0xFFFFFAF8)
val SurfaceWarm = Color(0xFFFFFAF8)
val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
val SurfaceContainerLowLight = Color(0xFFFFF4F2)
val SurfaceContainerLight = Color(0xFFF6ECE9)
val SurfaceContainerHighLight = Color(0xFFEFE4E1)
val SurfaceContainerHighestLight = Color(0xFFE9DCD9)
val SurfaceDimLight = Color(0xFFE4D6D3)
val SurfaceBrightLight = Color(0xFFFFFAF8)
val OnSurfaceLight = Color(0xFF21191A)
val OnSurfaceVariantLight = Color(0xFF514345)
val OutlineLight = Color(0xFF806F70)
val OutlineVariantLight = Color(0xFFD8C4C4)

// Legacy surface alias compatibility
val SurfaceVariantWarm = SurfaceContainerHighLight
val NeutralDark = OnSurfaceLight
val NeutralMedium = OnSurfaceVariantLight
val NeutralLight = OutlineLight

// Dark surface ladder — low-glare warm plum neutrals
val BackgroundDark = Color(0xFF171112)
val SurfaceDark = Color(0xFF171112)
val SurfaceContainerLowestDark = Color(0xFF100B0C)
val SurfaceContainerLowDark = Color(0xFF211819)
val SurfaceContainerDark = Color(0xFF291F20)
val SurfaceContainerHighDark = Color(0xFF34292A)
val SurfaceContainerHighestDark = Color(0xFF403435)
val SurfaceDimDark = Color(0xFF171112)
val SurfaceBrightDark = Color(0xFF433839)
val OnSurfaceDark = Color(0xFFF5E6E2)
val OnSurfaceVariantDark = Color(0xFFD7C2BF)
val OutlineDark = Color(0xFFA18C8A)
val OutlineVariantDark = Color(0xFF564646)

// Error and urgent states
val ErrorLight = Color(0xFFBA1A1A)
val OnErrorLight = Color(0xFFFFFFFF)
val ErrorContainerLight = Color(0xFFFFDAD6)
val OnErrorContainerLight = Color(0xFF410002)

val ErrorDark = Color(0xFFFFB4AB)
val OnErrorDark = Color(0xFF690005)
val ErrorContainerDark = Color(0xFF93000A)
val OnErrorContainerDark = Color(0xFFFFDAD6)
