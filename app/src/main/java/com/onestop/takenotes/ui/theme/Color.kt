package com.onestop.takenotes.ui.theme

import androidx.compose.ui.graphics.Color

// Geometric Balance - Light Palette
val GeoPrimary = Color(0xFF005AC1)
val GeoOnPrimary = Color(0xFFFFFFFF)
val GeoPrimaryContainer = Color(0xFFD8E2FF)
val GeoOnPrimaryContainer = Color(0xFF001A41)

val GeoSecondary = Color(0xFF575E71)
val GeoOnSecondary = Color(0xFFFFFFFF)
val GeoSecondaryContainer = Color(0xFFDBE2F9)
val GeoOnSecondaryContainer = Color(0xFF141B2C)

val GeoTertiary = Color(0xFF715573)
val GeoOnTertiary = Color(0xFFFFFFFF)
val GeoTertiaryContainer = Color(0xFFFBD7FC)
val GeoOnTertiaryContainer = Color(0xFF29132D)

val GeoBackground = Color(0xFFF8F9FE)
val GeoSurface = Color(0xFFFFFFFF)
val GeoSurfaceVariant = Color(0xFFE1E2EC)
val GeoOnSurface = Color(0xFF191C20)
val GeoOnSurfaceVariant = Color(0xFF44474F)
val GeoOutline = Color(0xFF74777F)
val GeoOutlineVariant = Color(0xFFC4C6D0)

// Geometric Balance - Dark Palette
val GeoPrimaryDark = Color(0xFFAEC6FF)
val GeoOnPrimaryDark = Color(0xFF002E69)
val GeoPrimaryContainerDark = Color(0xFF004494)
val GeoOnPrimaryContainerDark = Color(0xFFD8E2FF)

val GeoSecondaryDark = Color(0xFFBFC6DC)
val GeoOnSecondaryDark = Color(0xFF293041)
val GeoSecondaryContainerDark = Color(0xFF3F4759)
val GeoOnSecondaryContainerDark = Color(0xFFDBE2F9)

val GeoTertiaryDark = Color(0xFFDEBCDF)
val GeoOnTertiaryDark = Color(0xFF402843)
val GeoTertiaryContainerDark = Color(0xFF583E5B)
val GeoOnTertiaryContainerDark = Color(0xFFFBD7FC)

val GeoBackgroundDark = Color(0xFF111318)
val GeoSurfaceDark = Color(0xFF191C20)
val GeoSurfaceVariantDark = Color(0xFF44474F)
val GeoOnSurfaceDark = Color(0xFFE2E2E9)
val GeoOnSurfaceVariantDark = Color(0xFFC4C6D0)
val GeoOutlineDark = Color(0xFF8E9099)
val GeoOutlineVariantDark = Color(0xFF44474F)

// Backwards-compatible aliases
val IndigoPrimary = GeoPrimary
val IndigoOnPrimary = GeoOnPrimary
val IndigoContainer = GeoPrimaryContainer
val IndigoOnContainer = GeoOnPrimaryContainer
val SlateSecondary = GeoSecondary
val SlateOnSecondary = GeoOnSecondary
val SlateContainer = GeoSecondaryContainer
val SlateOnContainer = GeoOnSecondaryContainer
val AmberTertiary = GeoTertiary
val AmberOnTertiary = GeoOnTertiary
val AmberContainer = GeoTertiaryContainer
val AmberOnContainer = GeoOnTertiaryContainer
val BackgroundLight = GeoBackground
val SurfaceLight = GeoSurface
val SurfaceVariantLight = GeoSurfaceVariant

val IndigoPrimaryDark = GeoPrimaryDark
val IndigoOnPrimaryDark = GeoOnPrimaryDark
val IndigoContainerDark = GeoPrimaryContainerDark
val IndigoOnContainerDark = GeoOnPrimaryContainerDark
val SlateSecondaryDark = GeoSecondaryDark
val SlateOnSecondaryDark = GeoOnSecondaryDark
val SlateContainerDark = GeoSecondaryContainerDark
val SlateOnContainerDark = GeoOnSecondaryContainerDark
val AmberTertiaryDark = GeoTertiaryDark
val AmberOnTertiaryDark = GeoOnTertiaryDark
val AmberContainerDark = GeoTertiaryContainerDark
val AmberOnContainerDark = GeoOnTertiaryContainerDark
val BackgroundDark = GeoBackgroundDark
val SurfaceDark = GeoSurfaceDark
val SurfaceVariantDark = GeoSurfaceVariantDark

// Category specific accent colors tailored for Geometric Balance theme
object CategoryColors {
    val Work = Color(0xFF005AC1)        // Geometric Blue
    val Personal = Color(0xFF006A60)    // Emerald Teal
    val Shopping = Color(0xFF9C4235)    // Terracotta
    val Education = Color(0xFF6750A4)   // Iris Purple
    val News = Color(0xFFBA1A1A)        // Crimson
    val Archive = Color(0xFF575E71)     // Slate
    val Other = Color(0xFF715573)       // Plum

    val AvailableColorPresets = listOf(
        "#005AC1", // Blue
        "#006A60", // Teal
        "#9C4235", // Terracotta
        "#6750A4", // Purple
        "#BA1A1A", // Crimson
        "#575E71", // Slate
        "#715573", // Plum
        "#1B6B4A", // Forest
        "#825500", // Amber
        "#8B388D", // Magenta
        "#00677D", // Ocean Cyan
        "#5B53A4"  // Deep Violet
    )

    fun parseHex(hex: String?, fallback: Color = Other): Color {
        if (hex.isNullOrBlank()) return fallback
        return try {
            val clean = hex.removePrefix("#")
            val colorLong = clean.toLong(16)
            if (clean.length == 6) {
                Color(colorLong or 0xFF000000)
            } else {
                Color(colorLong)
            }
        } catch (e: Exception) {
            fallback
        }
    }

    fun getColor(category: String, customHex: String? = null): Color {
        if (!customHex.isNullOrBlank()) {
            return parseHex(customHex)
        }
        return when (category.trim().lowercase()) {
            "work" -> Work
            "personal" -> Personal
            "shopping" -> Shopping
            "education" -> Education
            "news" -> News
            "archive" -> Archive
            "all" -> GeoPrimary
            else -> {
                val index = (Math.abs(category.hashCode()) % AvailableColorPresets.size)
                parseHex(AvailableColorPresets[index], Other)
            }
        }
    }
}
