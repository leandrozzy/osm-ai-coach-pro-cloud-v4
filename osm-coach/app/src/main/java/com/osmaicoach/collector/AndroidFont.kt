package com.osmaicoach.collector

import androidx.compose.ui.text.font.FontFamily

/**
 * Fonte condensada do sistema (Roboto Condensed), parecida com a do OSM. Se o aparelho não tiver, fica a padrão.
 */
fun osmFontFamily(): FontFamily = try {
    FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
} catch (e: Throwable) {
    FontFamily.SansSerif
}
