package com.osmaicoach.collector

import kotlin.math.max
import kotlin.math.min

/**
 * Análises de cor/ícone. Limiares calibrados em quadros reais do OSM (2448x1080):
 * barra superior do OSM, camisa laranja (treino), selo V/E/D, ícone de casa e termômetro do árbitro.
 * Todas as coordenadas são frações (0..1) da imagem.
 */
object PixelProbe {
    class Img(val w: Int, val h: Int, val px: IntArray)

    private fun hsv(c: Int, out: FloatArray) {
        val r = (c shr 16 and 0xFF) / 255f
        val g = (c shr 8 and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        var hue = 0f
        if (d > 0f) {
            hue = when (mx) {
                r -> ((g - b) / d) % 6f
                g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } * 60f
            if (hue < 0f) hue += 360f
        }
        out[0] = hue
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    private val ORANGE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 20f..46f && s > 0.58f && v > 0.66f }
    private val LIGHT_BLUE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 176f..218f && s > 0.43f && v > 0.78f }
    private val GREEN: (Float, Float, Float) -> Boolean = { h, s, v -> h in 80f..165f && s > 0.66f && v > 0.66f }
    private val RED: (Float, Float, Float) -> Boolean = { h, s, v -> (h <= 10f || h >= 344f) && s > 0.66f && v > 0.66f }
    private val HUB_BLUE: (Float, Float, Float) -> Boolean = { h, s, v -> h in 195f..235f && s > 0.40f && v > 0.50f }

    private fun frac(img: Img, x0: Float, x1: Float, y0: Float, y1: Float, pred: (Float, Float, Float) -> Boolean): Float {
        val xa = (x0.coerceIn(0f, 1f) * img.w).toInt()
        val xb = max((x1.coerceIn(0f, 1f) * img.w).toInt(), xa + 1)
        val ya = (y0.coerceIn(0f, 1f) * img.h).toInt()
        val yb = max((y1.coerceIn(0f, 1f) * img.h).toInt(), ya + 1)
        var n = 0
        var hit = 0
        val t = FloatArray(3)
        for (y in ya until min(yb, img.h)) {
            for (x in xa until min(xb, img.w)) {
                hsv(img.px[y * img.w + x], t)
                n++
                if (pred(t[0], t[1], t[2])) hit++
            }
        }
        return if (n == 0) 0f else hit.toFloat() / n.toFloat()
    }

    /** Barra superior do OSM: pílula azul-clara de moedas + pílula laranja de gemas. Anúncios não têm. */
    fun isOsmTopBar(img: Img): Boolean =
        frac(img, 0.20f, 0.28f, 0.01f, 0.08f, ORANGE) >= 0.08f &&
            frac(img, 0.12f, 0.22f, 0.01f, 0.08f, LIGHT_BLUE) >= 0.10f

    /** A central dos 4 slots não tem barra superior; ela é azul. Serve só para decidir se vale rodar OCR. */
    fun hubBackdrop(img: Img): Boolean = frac(img, 0f, 1f, 0f, 1f, HUB_BLUE) >= 0.25f

    private val GOLD: (Float, Float, Float) -> Boolean = { h, s, v -> h in 36f..58f && s > 0.55f && v > 0.75f }

    /** Estrelas preenchidas (douradas) de um card do estádio: 3 estrelas centradas em cx, espaçadas 0,0286. */
    fun starLevel(img: Img, cx: Float, cy: Float): Int {
        var n = 0
        for (i in -1..1) {
            val x = cx + i * 0.0286f
            if (frac(img, x - 0.008f, x + 0.008f, cy - 0.016f, cy + 0.016f, GOLD) >= 0.25f) n++
        }
        return n
    }

    /** Preenchimento (0 a 100) de uma barra colorida horizontal (condição/moral) na linha y. */
    fun barFill(img: Img, x0: Float, x1: Float, y: Float): Int {
        val xa = (x0 * img.w).toInt().coerceIn(0, img.w - 1)
        val xb = (x1 * img.w).toInt().coerceIn(xa + 1, img.w)
        val ya = (y * img.h).toInt().coerceIn(0, img.h - 1)
        var n = 0
        var hit = 0
        val t = FloatArray(3)
        for (x in xa until xb) {
            n++
            hsv(img.px[ya * img.w + x], t)
            if (t[1] > 0.45f && t[2] > 0.5f) hit++
        }
        return if (n == 0) 0 else hit * 100 / n
    }

    /** Camisa laranja na linha do jogador (treino). */
    fun orangeShirt(img: Img, rowY: Float): Boolean =
        frac(img, 0.005f, 0.055f, rowY - 0.04f, rowY + 0.04f, ORANGE) >= 0.04f

    /** Selo V/E/D do card do calendário, à direita do rótulo "Jornada N". */
    fun resultBadge(img: Img, labelX: Float, labelY: Float): Char? {
        val x0 = labelX + 0.03f
        val x1 = labelX + 0.115f
        val y0 = labelY - 0.035f
        val y1 = labelY + 0.035f
        val g = frac(img, x0, x1, y0, y1, GREEN)
        val o = frac(img, x0, x1, y0, y1, ORANGE)
        val r = frac(img, x0, x1, y0, y1, RED)
        val best = max(g, max(o, r))
        if (best < 0.03f) return null
        return when (best) {
            g -> 'V'
            o -> 'E'
            else -> 'D'
        }
    }

    /** Ícone de casinha à esquerda do rótulo: true=casa, false=fora, null=incerto. */
    fun homeIcon(img: Img, labelX: Float, labelY: Float): Boolean? {
        val x0 = max(0f, labelX - 0.095f)
        val x1 = labelX - 0.04f
        if (x1 <= x0) return null
        val white = frac(img, x0, x1, labelY - 0.035f, labelY + 0.035f) { _, s, v -> s < 0.12f && v > 0.80f }
        return when {
            white >= 0.02f -> true
            white <= 0.008f -> false
            else -> null
        }
    }

    /** Termômetro do árbitro no pré-jogo: cor do bulbo (azul=brando, laranja=médio, vermelho=rigoroso). */
    fun refereeSeverity(img: Img): String? {
        val x0 = 0.49f
        val x1 = 0.508f
        val y0 = 0.484f
        val y1 = 0.496f
        val xa = (x0 * img.w).toInt()
        val xb = max((x1 * img.w).toInt(), xa + 1)
        val ya = (y0 * img.h).toInt()
        val yb = max((y1 * img.h).toInt(), ya + 1)
        var blue = 0
        var orange = 0
        var red = 0
        var green = 0
        val t = FloatArray(3)
        for (y in ya until min(yb, img.h)) {
            for (x in xa until min(xb, img.w)) {
                hsv(img.px[y * img.w + x], t)
                if (t[1] < 0.5f || t[2] < 0.55f) continue
                val h = t[0]
                when {
                    h <= 10f || h >= 344f -> red++
                    h < 20f -> red++
                    h <= 70f -> orange++
                    h <= 165f -> green++
                    h <= 260f -> blue++
                }
            }
        }
        val total = blue + orange + red + green
        if (total < 8) return null
        val top = max(max(blue, orange), max(red, green))
        return when (top) {
            red -> "Rigoroso"
            orange -> "Médio"
            blue -> "Brando"
            else -> null
        }
    }
}

/** Hash perceptual 32x18 (576 bits) para deduplicar capturas e detectar estabilidade. */
object FrameHash {
    const val W = 65
    const val H = 36

    fun of(px: IntArray): LongArray {
        val gray = IntArray(W * H) { i ->
            val c = px[i]
            ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
        }
        val out = LongArray(((W - 1) * H + 63) / 64)
        var bit = 0
        for (y in 0 until H) {
            for (x in 0 until W - 1) {
                if (gray[y * W + x] > gray[y * W + x + 1]) {
                    out[bit / 64] = out[bit / 64] or (1L shl (bit % 64))
                }
                bit++
            }
        }
        return out
    }

    fun distance(a: LongArray, b: LongArray): Int {
        var d = 0
        for (i in a.indices) d += java.lang.Long.bitCount(a[i] xor b[i])
        return d
    }

    fun toText(h: LongArray): String = h.joinToString("") { java.lang.Long.toHexString(it).padStart(16, '0') }
}
