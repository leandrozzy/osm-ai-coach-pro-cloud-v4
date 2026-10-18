package com.osmaicoach.collector

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Ilustrações vetoriais da tela de tática (estilo app de esporte moderno): campo visto de cima, setas com gradiente,
 * brilho e sombra suave, jogadores como fichas. Tudo desenhado na hora, nítido em qualquer tela, e animado: as setas
 * se desenham do começo ao fim sempre que a opção muda.
 */
object Ill {
    val GRASS_A = Color(0xFF1F8F3A)
    val GRASS_B = Color(0xFF1A8034)
    val LINE = Color(0x8CFFFFFF)
    val RED = Color(0xFFFF4D4D)
    val RED_D = Color(0xFFD7192A)
    val BLUE = Color(0xFF3DB2FF)
    val BLUE_D = Color(0xFF1666E0)
    val GOLD = Color(0xFFFFD24A)
    val GOLD_D = Color(0xFFFF9F1A)
    val ORANGE = Color(0xFFFF9A3D)
    val ORANGE_D = Color(0xFFE85A0C)
    val MINE = Color(0xFF2F7BFF)
    /** Fase da luz que corre nas setas do desenho atual (-1 = sem luz). */
    var flow = -1f
    val RIVAL = Color(0xFFE5484D)
}

/** Luz que corre pelas setas em loop (0..1), deixa o desenho "vivo" depois de desenhado. */
@Composable
fun flowPhase(millis: Int = 1800): Float {
    val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "flow")
    val v = t.animateFloat(
        initialValue = 0f, targetValue = 1f, animationSpec =
        androidx.compose.animation.core.infiniteRepeatable(tween(millis, easing = androidx.compose.animation.core.LinearEasing)),
        label = "flowv"
    )
    return v.value
}

/** Progresso 0..1 que recomeça (com aceleração suave) sempre que [key] muda. */
@Composable
fun drawProgress(key: Any?, millis: Int = 900): Float {
    val a = remember { Animatable(0f) }
    LaunchedEffect(key) {
        a.snapTo(0f)
        a.animateTo(1f, tween(millis, easing = FastOutSlowInEasing))
    }
    return a.value
}

// ------------------------------------------------------------------ campo

/** Campo visto de cima em [r] (horizontal = ataque para a direita; vertical = ataque para cima). */
fun DrawScope.pitch(r: Rect, vertical: Boolean) {
    val rad = CornerRadius(min(r.width, r.height) * 0.06f)
    // sombra do cartão do campo
    drawRoundRect(Color(0x66000000), r.topLeft + Offset(0f, r.height * 0.025f), r.size, rad)
    val clip = Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(r, rad)) }
    clipPath(clip) {
        drawRect(Ill.GRASS_A, r.topLeft, r.size)
        val n = 10
        for (i in 0 until n) {
            if (i % 2 == 0) continue
            if (vertical) drawRect(Ill.GRASS_B, Offset(r.left, r.top + r.height * i / n), Size(r.width, r.height / n))
            else drawRect(Ill.GRASS_B, Offset(r.left + r.width * i / n, r.top), Size(r.width / n, r.height))
        }
        // corte cruzado bem sutil (xadrez de gramado de estádio)
        val k = 6
        for (i in 0 until k) {
            if (i % 2 == 1) continue
            if (vertical) drawRect(Color(0x0FFFFFFF), Offset(r.left + r.width * i / k, r.top), Size(r.width / k, r.height))
            else drawRect(Color(0x0FFFFFFF), Offset(r.left, r.top + r.height * i / k), Size(r.width, r.height / k))
        }
        // luz de refletor: claro no centro, escurece nas bordas
        drawRect(
            Brush.radialGradient(listOf(Color(0x26FFFFFF), Color(0x00FFFFFF), Color(0x40000000)), r.center, maxOf(r.width, r.height) * 0.75f),
            r.topLeft, r.size
        )
    }
    // filete claro no topo do cartão (dá acabamento)
    drawRoundRect(Color(0x33FFFFFF), r.topLeft, r.size, rad, style = Stroke(1.5f))
    val m = min(r.width, r.height) * 0.06f
    val f = Rect(r.left + m, r.top + m, r.right - m, r.bottom - m)
    val lw = (min(r.width, r.height) * 0.012f).coerceAtLeast(1.2f)
    val st = Stroke(lw)
    drawRect(Ill.LINE, f.topLeft, f.size, style = st)
    val cr = min(f.width, f.height) * 0.16f
    drawCircle(Ill.LINE, cr, f.center, style = st)
    drawCircle(Ill.LINE, lw * 1.4f, f.center)
    // cantos
    val ca = min(f.width, f.height) * 0.05f
    drawArc(Ill.LINE, 0f, 90f, false, Offset(f.left - ca, f.top - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 90f, 90f, false, Offset(f.right - ca, f.top - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 270f, 90f, false, Offset(f.left - ca, f.bottom - ca), Size(ca * 2, ca * 2), style = st)
    drawArc(Ill.LINE, 180f, 90f, false, Offset(f.right - ca, f.bottom - ca), Size(ca * 2, ca * 2), style = st)
    if (vertical) {
        drawLine(Ill.LINE, Offset(f.left, f.center.y), Offset(f.right, f.center.y), lw)
        val dr = f.width * 0.13f
        drawArc(Ill.LINE, 20f, 140f, false, Offset(f.center.x - dr, f.top + f.height * 0.16f - dr * 1.25f), Size(dr * 2, dr * 2), style = st)
        drawArc(Ill.LINE, 200f, 140f, false, Offset(f.center.x - dr, f.bottom - f.height * 0.16f - dr * 0.75f), Size(dr * 2, dr * 2), style = st)
        for (top in listOf(true, false)) {
            val bw = f.width * 0.56f
            val bh = f.height * 0.16f
            val gw = f.width * 0.26f
            val gh = f.height * 0.06f
            val y = if (top) f.top else f.bottom
            val s = if (top) 1f else -1f
            drawRect(Ill.LINE, Offset(f.center.x - bw / 2, if (top) y else y - bh), Size(bw, bh), style = st)
            drawRect(Ill.LINE, Offset(f.center.x - gw / 2, if (top) y else y - gh), Size(gw, gh), style = st)
            drawLine(Color.White, Offset(f.center.x - gw * 0.35f, y - s * lw), Offset(f.center.x + gw * 0.35f, y - s * lw), lw * 2.4f, StrokeCap.Round)
        }
    } else {
        drawLine(Ill.LINE, Offset(f.center.x, f.top), Offset(f.center.x, f.bottom), lw)
        val dr = f.height * 0.13f
        drawArc(Ill.LINE, -70f, 140f, false, Offset(f.left + f.width * 0.15f - dr * 1.25f, f.center.y - dr), Size(dr * 2, dr * 2), style = st)
        drawArc(Ill.LINE, 110f, 140f, false, Offset(f.right - f.width * 0.15f - dr * 0.75f, f.center.y - dr), Size(dr * 2, dr * 2), style = st)
        for (left in listOf(true, false)) {
            val bw = f.width * 0.15f
            val bh = f.height * 0.56f
            val gw = f.width * 0.055f
            val gh = f.height * 0.26f
            val x = if (left) f.left else f.right
            val s = if (left) 1f else -1f
            drawRect(Ill.LINE, Offset(if (left) x else x - bw, f.center.y - bh / 2), Size(bw, bh), style = st)
            drawRect(Ill.LINE, Offset(if (left) x else x - gw, f.center.y - gh / 2), Size(gw, gh), style = st)
            drawLine(Color.White, Offset(x - s * lw, f.center.y - gh * 0.35f), Offset(x - s * lw, f.center.y + gh * 0.35f), lw * 2.4f, StrokeCap.Round)
        }
    }
}

// ------------------------------------------------------------------ setas e fichas

/** Caminho suave passando pelos pontos (curvas pelos pontos médios). */
fun smoothPath(pts: List<Offset>): Path {
    val p = Path()
    p.moveTo(pts[0].x, pts[0].y)
    if (pts.size == 2) {
        p.lineTo(pts[1].x, pts[1].y)
        return p
    }
    for (i in 1 until pts.size - 1) {
        val mid = (pts[i] + pts[i + 1]) / 2f
        p.quadraticTo(pts[i].x, pts[i].y, mid.x, mid.y)
    }
    p.lineTo(pts.last().x, pts.last().y)
    return p
}

/**
 * Seta: corpo com gradiente (claro -> cor), sombra suave embaixo, brilho fino em cima e ponta arredondada.
 * [progress] desenha só o começo do caminho (animação). [dashed] para trajetória de bola.
 */
fun DrawScope.arrow(pts: List<Offset>, light: Color, dark: Color, width: Float, progress: Float = 1f, dashed: Boolean = false, flow: Float = Ill.flow) {
    if (progress <= 0.01f) return
    val full = smoothPath(pts)
    val pm = PathMeasure()
    pm.setPath(full, false)
    val len = pm.length
    if (len <= 1f) return
    val head = width * (if (len < width * 8f) 1.7f else 2.1f)
    val end = len * progress.coerceIn(0f, 1f)
    val bodyEnd = (end - head * 0.55f).coerceAtLeast(0.01f)
    val body = Path()
    pm.getSegment(0f, bodyEnd, body, true)
    val tip = pm.getPosition(end)
    val tan = pm.getTangent(end)
    val ang = atan2(tan.y, tan.x)
    val start = pts.first()
    val brush = Brush.linearGradient(listOf(light, dark), start, tip)
    val effect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(width * 1.6f, width * 1.1f)) else null
    // sombra (deslocada para baixo, mais larga e transparente)
    val sh = Offset(width * 0.25f, width * 0.55f)
    val shadow = Path().apply { addPath(body, sh) }
    drawPath(shadow, Color(0x55000000), style = Stroke(width * 1.25f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // halo da cor da seta (brilho de neon suave)
    drawPath(body, light.copy(alpha = 0.16f), style = Stroke(width * 3.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(body, light.copy(alpha = 0.22f), style = Stroke(width * 2.2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    // contorno escuro fino (dá corpo) + corpo com gradiente
    drawPath(body, dark.copy(alpha = 0.9f), style = Stroke(width * 1.22f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    drawPath(body, brush, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // brilho
    val hl = Path().apply { addPath(body, Offset(-width * 0.12f, -width * 0.18f)) }
    drawPath(hl, Color(0x66FFFFFF), style = Stroke(width * 0.28f, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect))
    // ponta
    val hp = Path().apply {
        moveTo(head, 0f)
        quadraticTo(head * 0.1f, -head * 0.08f, -head * 0.35f, -head * 0.62f)
        quadraticTo(-head * 0.2f, 0f, -head * 0.35f, head * 0.62f)
        quadraticTo(head * 0.1f, head * 0.08f, head, 0f)
        close()
    }
    val base = tip - Offset(cos(ang) * head * 0.55f, sin(ang) * head * 0.55f)
    translateRotate(base + sh, ang) { drawPath(hp, Color(0x55000000)) }
    translateRotate(base, ang) {
        drawPath(hp, Brush.linearGradient(listOf(light, dark), Offset(-head, -head), Offset(head, head)))
        drawPath(hp, dark.copy(alpha = 0.9f), style = Stroke(width * 0.18f, join = StrokeJoin.Round))
        drawPath(hp, Color(0x40FFFFFF), style = Stroke(width * 0.12f, join = StrokeJoin.Round))
    }
    // luz que corre pela seta (só depois de desenhada)
    if (flow >= 0f && progress >= 0.999f) {
        val fp = pm.getPosition(bodyEnd * flow)
        val fade = (sin(Math.PI * flow).toFloat()).coerceIn(0f, 1f)
        drawCircle(Color.White.copy(alpha = 0.18f * fade), width * 1.3f, fp)
        drawCircle(Color.White.copy(alpha = 0.85f * fade), width * 0.38f, fp)
    }
}

private fun DrawScope.translateRotate(at: Offset, rad: Float, block: DrawScope.() -> Unit) {
    drawContext.transform.translate(at.x, at.y)
    rotate(Math.toDegrees(rad.toDouble()).toFloat(), Offset.Zero) { block() }
    drawContext.transform.translate(-at.x, -at.y)
}

/** Ficha de jogador: disco com gradiente da cor do time, borda branca e sombra. */
fun DrawScope.token(c: Offset, r: Float, color: Color, alpha: Float = 1f, jersey: Boolean = true) {
    if (alpha <= 0f) return
    drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r * 1.05f, c + Offset(r * 0.12f, r * 0.3f))
    drawCircle(Color.White.copy(alpha = alpha), r, c)
    drawCircle(
        Brush.radialGradient(listOf(lighten(color, 0.35f), color, darken(color, 0.35f)), c - Offset(r * 0.35f, r * 0.4f), r * 1.4f),
        r * 0.78f, c, alpha = alpha
    )
    // camisa dentro da ficha
    val j = Path().apply {
        val u = r * 0.42f
        moveTo(c.x - u * 0.35f, c.y - u * 0.75f)
        lineTo(c.x - u * 0.95f, c.y - u * 0.45f)
        lineTo(c.x - u * 0.7f, c.y - u * 0.05f)
        lineTo(c.x - u * 0.48f, c.y - u * 0.18f)
        lineTo(c.x - u * 0.48f, c.y + u * 0.78f)
        lineTo(c.x + u * 0.48f, c.y + u * 0.78f)
        lineTo(c.x + u * 0.48f, c.y - u * 0.18f)
        lineTo(c.x + u * 0.7f, c.y - u * 0.05f)
        lineTo(c.x + u * 0.95f, c.y - u * 0.45f)
        lineTo(c.x + u * 0.35f, c.y - u * 0.75f)
        quadraticTo(c.x, c.y - u * 0.35f, c.x - u * 0.35f, c.y - u * 0.75f)
        close()
    }
    if (jersey) {
        drawPath(j, Color.White.copy(alpha = 0.92f * alpha))
        drawPath(j, darken(color, 0.4f).copy(alpha = 0.5f * alpha), style = Stroke(r * 0.05f, join = StrokeJoin.Round))
    }
    drawCircle(Color.White.copy(alpha = 0.3f * alpha), r * 0.22f, c - Offset(r * 0.35f, r * 0.38f))
}

fun lighten(c: Color, f: Float) = Color(c.red + (1 - c.red) * f, c.green + (1 - c.green) * f, c.blue + (1 - c.blue) * f, c.alpha)
fun darken(c: Color, f: Float) = Color(c.red * (1 - f), c.green * (1 - f), c.blue * (1 - f), c.alpha)

/** Bola (branca com gomos) com sombra. */
fun DrawScope.ball(c: Offset, r: Float, alpha: Float = 1f) {
    if (alpha <= 0f) return
    drawCircle(Color.Black.copy(alpha = 0.35f * alpha), r, c + Offset(r * 0.25f, r * 0.45f))
    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFCFD6DE)), c - Offset(r * 0.3f, r * 0.3f), r * 1.4f), r, c, alpha = alpha)
    drawCircle(Color(0xFF1B1F27).copy(alpha = alpha), r * 0.36f, c)
    for (k in 0 until 5) {
        val a = Math.toRadians((k * 72 - 90).toDouble())
        drawCircle(Color(0xFF1B1F27).copy(alpha = alpha), r * 0.16f, c + Offset((cos(a) * r * 0.78f).toFloat(), (sin(a) * r * 0.78f).toFloat()))
    }
}

// ------------------------------------------------------------------ estilo de jogo

@Composable
fun StyleIllustration(style: String?, modifier: Modifier = Modifier) {
    val st = Osm.style(style) ?: style ?: ""
    val p = drawProgress(st, 1100)
    val fl = flowPhase()
    Canvas(modifier) {
        Ill.flow = fl
        val r = Rect(Offset(size.width * 0.02f, size.height * 0.05f), Size(size.width * 0.96f, size.height * 0.88f))
        pitch(r, vertical = false)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val w = r.height * 0.065f
        val tr = r.height * 0.088f
        when (st) {
            "Jogar pelas alas" -> {
                for (y in listOf(0.14f, 0.86f)) {
                    arrow(listOf(at(0.22f, y), at(0.55f, y), at(0.78f, y), at(0.86f, if (y < 0.5f) 0.3f else 0.7f)), Ill.RED, Ill.RED_D, w, p)
                    token(at(0.22f, y), tr, Ill.MINE)
                }
                token(at(0.9f, 0.5f), tr, Ill.MINE, ((p - 0.6f) / 0.4f).coerceIn(0f, 1f))
            }
            "Jogo de passe" -> {
                val pts = listOf(at(0.15f, 0.55f), at(0.32f, 0.35f), at(0.48f, 0.6f), at(0.64f, 0.38f), at(0.8f, 0.56f))
                for (i in 0 until pts.size - 1) {
                    val seg = (p * (pts.size - 1) - i).coerceIn(0f, 1f)
                    arrow(listOf(pts[i] + Offset(tr * 1.1f, 0f), pts[i + 1] - Offset(tr * 1.3f, 0f)), Ill.RED, Ill.RED_D, w * 0.75f, seg)
                }
                pts.forEach { token(it, tr, Ill.MINE) }
            }
            "Bola longa" -> {
                val a = at(0.16f, 0.72f)
                val b = at(0.84f, 0.6f)
                // sombra da bola no chão (linha reta tracejada) e a trajetória alta em pontos
                val n = 26
                val shown = (n * p).toInt()
                for (k in 0..shown) {
                    val t = k / n.toFloat()
                    val g = a + (b - a) * t
                    val h = r.height * 0.42f * sin(Math.PI * t).toFloat()
                    drawCircle(Color(0x40000000), w * 0.28f, g + Offset(0f, w * 0.3f))
                    val c = g - Offset(0f, h)
                    val col = androidx.compose.ui.graphics.lerp(Ill.GOLD, Ill.RED, t)
                    drawCircle(col.copy(alpha = 0.25f), w * 0.75f, c)
                    drawCircle(col, w * (0.32f + 0.18f * sin(Math.PI * t).toFloat()), c)
                }
                token(a, tr, Ill.MINE)
                token(b, tr, Ill.MINE)
                val bp = ((p - 0.85f) / 0.15f).coerceIn(0f, 1f)
                val fl2 = Ill.flow.coerceAtLeast(0f)
                val t2 = if (bp >= 1f) fl2 else 0f
                val bpos = a + (b - a) * t2 - Offset(0f, r.height * 0.42f * sin(Math.PI * t2).toFloat())
                ball(if (bp >= 1f && t2 > 0.02f && t2 < 0.98f) bpos else b - Offset(tr * 1.5f, tr * 0.1f), tr * 0.55f, bp)
            }
            "Contra-ataque" -> {
                val back = (p / 0.45f).coerceIn(0f, 1f)
                val fwd = ((p - 0.35f) / 0.65f).coerceIn(0f, 1f)
                arrow(listOf(at(0.62f, 0.3f), at(0.45f, 0.4f), at(0.3f, 0.45f)), Ill.BLUE, Ill.BLUE_D, w * 0.75f, back)
                arrow(listOf(at(0.28f, 0.62f), at(0.55f, 0.7f), at(0.86f, 0.52f)), Ill.RED, Ill.RED_D, w, fwd)
                token(at(0.24f, 0.55f), tr, Ill.MINE)
                token(at(0.66f, 0.26f), tr, Ill.RIVAL)
                token(at(0.7f, 0.45f), tr, Ill.RIVAL)
            }
            "Remate à vista" -> {
                val from = listOf(at(0.58f, 0.22f), at(0.62f, 0.5f), at(0.58f, 0.78f))
                from.forEachIndexed { i, s ->
                    val q = ((p - i * 0.15f) / 0.7f).coerceIn(0f, 1f)
                    arrow(listOf(s, at(0.9f, 0.5f + (i - 1) * 0.07f)), Ill.RED, Ill.RED_D, w * 0.7f, q)
                    token(s, tr, Ill.MINE)
                }
                ball(at(0.955f, 0.44f), tr * 0.7f, ((p - 0.7f) / 0.3f).coerceIn(0f, 1f))
            }
            else -> token(r.center, tr, Ill.MINE)
        }
    }
}

// ------------------------------------------------------------------ desarme: medidor de intensidade

@Composable
fun TackleIllustration(tackle: String?, modifier: Modifier = Modifier) {
    val lvl = when (Osm.tackle(tackle) ?: tackle) {
        "Cuidadoso" -> 0
        "Agressivo" -> 2
        "Extremo" -> 3
        else -> 1
    }
    val p = drawProgress(lvl, 900)
    Canvas(modifier) {
        Ill.flow = -1f
        val cx = size.width / 2
        val cy = size.height * 0.62f
        val R = min(size.width * 0.44f, size.height * 0.55f)
        val sw = R * 0.22f
        val cols = listOf(Color(0xFF34D399), Color(0xFFFACC15), Color(0xFFFB923C), Color(0xFFEF4444))
        val gap = 4f
        // sombra do arco
        drawArc(Color(0x55000000), 180f, 180f, false, Offset(cx - R, cy - R + sw * 0.35f), Size(R * 2, R * 2), style = Stroke(sw, cap = StrokeCap.Round))
        for (i in 0 until 4) {
            val on = i <= lvl
            val c = if (on) cols[i] else Color(0xFF243556)
            if (on) drawArc(c.copy(alpha = 0.25f), 180f + i * 45f + gap / 2, 45f - gap, false, Offset(cx - R, cy - R), Size(R * 2, R * 2), style = Stroke(sw * 1.9f))
            drawArc(
                if (on) Brush.sweepGradient(listOf(lighten(c, 0.25f), c, darken(c, 0.15f)), Offset(cx, cy)) else Brush.linearGradient(listOf(c, c)),
                180f + i * 45f + gap / 2, 45f - gap, false, Offset(cx - R, cy - R), Size(R * 2, R * 2), style = Stroke(sw)
            )
            if (on) drawArc(Color(0x55FFFFFF), 180f + i * 45f + gap / 2, 45f - gap, false, Offset(cx - R + sw * 0.3f, cy - R + sw * 0.3f), Size(R * 2 - sw * 0.6f, R * 2 - sw * 0.6f), style = Stroke(sw * 0.15f))
        }
        // marcações da escala por dentro
        for (k in 0..16) {
            val a = Math.toRadians(180.0 + k * 180.0 / 16)
            val r0 = R - sw * 0.85f
            val r1 = r0 - (if (k % 4 == 0) sw * 0.55f else sw * 0.3f)
            drawLine(Color(0x66C9D8F0), Offset(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat()), Offset(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat()), 1.6f, StrokeCap.Round)
        }
        // ponteiro gira até o nível
        val target = 180f + (lvl + 0.5f) * 45f
        val ang = Math.toRadians((180f + (target - 180f) * p).toDouble())
        val tip = Offset(cx + (cos(ang) * R * 0.78f).toFloat(), cy + (sin(ang) * R * 0.78f).toFloat())
        drawLine(Color(0x66000000), Offset(cx, cy) + Offset(2f, 4f), tip + Offset(2f, 4f), sw * 0.38f, StrokeCap.Round)
        drawLine(Brush.linearGradient(listOf(Color.White, Color(0xFFC9D3E0)), Offset(cx, cy), tip), Offset(cx, cy), tip, sw * 0.34f, StrokeCap.Round)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFF98A6BD)), Offset(cx - 3f, cy - 3f), sw), sw * 0.55f, Offset(cx, cy))
        drawCircle(cols[lvl], sw * 0.25f, Offset(cx, cy))
        // faíscas do "pé forte" nos níveis altos
        val spark = ((p - 0.6f) / 0.4f).coerceIn(0f, 1f)
        if (lvl >= 2 && spark > 0f) {
            val n = if (lvl == 3) 6 else 4
            for (k in 0 until n) {
                val a = Math.toRadians((200 + k * (140.0 / (n - 1))))
                val r0 = R * 1.12f
                val r1 = r0 + R * 0.16f * spark
                drawLine(
                    cols[lvl].copy(alpha = spark), Offset(cx + (cos(a) * r0).toFloat(), cy + (sin(a) * r0).toFloat()),
                    Offset(cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat()), sw * 0.18f, StrokeCap.Round
                )
            }
        }
    }
}

// ------------------------------------------------------------------ táticas por sector

@Composable
fun SectorIllustration(attack: String?, mid: String?, def: String?, modifier: Modifier = Modifier) {
    val a = Osm.ATTACK.indexOf(Osm.attack(attack) ?: attack).coerceAtLeast(0)
    val m = Osm.MIDFIELD.indexOf(Osm.midfield(mid) ?: mid).coerceAtLeast(0)
    val d = Osm.DEFENSE.indexOf(Osm.defense(def) ?: def).coerceAtLeast(0)
    val pa = drawProgress(a, 800)
    val pm = drawProgress(m, 800)
    val pd = drawProgress(d, 800)
    val fl = flowPhase()
    Canvas(modifier) {
        Ill.flow = fl
        val r = Rect(Offset(size.width * 0.04f, size.height * 0.03f), Size(size.width * 0.92f, size.height * 0.93f))
        pitch(r, vertical = true)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val w = r.width * 0.07f
        val tr = r.width * 0.068f
        // defesas (laranja, embaixo)
        when (d) {
            0 -> holdCross(at(0.5f, 0.84f), r.width * 0.12f, Ill.ORANGE, Ill.ORANGE_D, w * 0.6f, pd)
            1 -> for (x in listOf(0.22f, 0.78f)) arrow(listOf(at(x, 0.86f), at(x, 0.6f)), Ill.ORANGE, Ill.ORANGE_D, w, pd)
            else -> for (x in listOf(0.22f, 0.5f, 0.78f)) arrow(listOf(at(x, 0.86f), at(x, 0.6f)), Ill.ORANGE, Ill.ORANGE_D, w, pd)
        }
        // médios (dourado, no meio)
        when (m) {
            0 -> for (s in listOf(-1f, 1f)) arrow(listOf(at(0.5f + s * 0.12f, 0.46f), at(0.5f + s * 0.24f, 0.36f), at(0.5f + s * 0.2f, 0.26f)), Ill.GOLD, Ill.GOLD_D, w * 0.85f, pm)
            1 -> for (x in listOf(0.2f, 0.8f)) holdCross(at(x, 0.5f), r.width * 0.11f, Ill.GOLD, Ill.GOLD_D, w * 0.55f, pm)
            else -> for (s in listOf(-1f, 1f)) arrow(listOf(at(0.5f + s * 0.12f, 0.54f), at(0.5f + s * 0.24f, 0.64f), at(0.5f + s * 0.2f, 0.74f)), Ill.GOLD, Ill.GOLD_D, w * 0.85f, pm)
        }
        // avançados (azul, em cima)
        when (a) {
            0 -> arrow(listOf(at(0.5f, 0.3f), at(0.5f, 0.1f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
            1 -> arrow(listOf(at(0.5f, 0.1f), at(0.5f, 0.3f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
            else -> arrow(listOf(at(0.5f, 0.1f), at(0.5f, 0.46f)), Ill.BLUE, Ill.BLUE_D, w * 1.15f, pa)
        }
        // fichas de cada linha por cima das setas
        token(at(0.5f, if (a == 0) 0.33f else 0.1f), tr, Ill.BLUE_D)
        for (x in listOf(0.2f, 0.5f, 0.8f)) if (!(m == 1 && x == 0.5f)) token(at(x, 0.5f), tr, Ill.GOLD_D)
        for (x in listOf(0.22f, 0.5f, 0.78f)) token(at(x, 0.88f), tr, Ill.ORANGE_D)
    }
}

/** "Manter posição": quatro setinhas saindo de um ponto. */
fun DrawScope.holdCross(c: Offset, len: Float, light: Color, dark: Color, w: Float, p: Float) {
    // anel no ponto + setinhas finas para os 4 lados (fica na posição)
    drawCircle(dark.copy(alpha = 0.35f), len * 0.42f * p, c)
    drawCircle(light.copy(alpha = 0.9f), len * 0.42f * p, c, style = Stroke(w * 0.35f))
    for ((dx, dy) in listOf(1f to 0f, -1f to 0f, 0f to 1f, 0f to -1f)) {
        arrow(listOf(c + Offset(dx * len * 0.5f, dy * len * 0.5f), c + Offset(dx * len * 1.05f, dy * len * 1.05f)), light, dark, w * 0.55f, p)
    }
}

// ------------------------------------------------------------------ marcação e fora-de-jogo

@Composable
fun MarkingIllustration(marking: String?, modifier: Modifier = Modifier) {
    val man = (Osm.marking(marking) ?: marking) == "Homem-a-homem"
    val p = drawProgress(man, 900)
    Canvas(modifier) {
        Ill.flow = -1f
        val r = Rect(Offset(size.width * 0.04f, size.height * 0.05f), Size(size.width * 0.92f, size.height * 0.88f))
        pitch(r, vertical = true)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val tr = r.width * 0.082f
        if (!man) {
            // cada um cuida da sua zona: círculos que crescem em volta de cada defensor
            val mine = listOf(at(0.24f, 0.66f), at(0.5f, 0.76f), at(0.76f, 0.66f), at(0.5f, 0.5f))
            val rivals = listOf(at(0.33f, 0.36f), at(0.7f, 0.42f), at(0.5f, 0.26f))
            mine.forEach {
                drawCircle(Ill.BLUE.copy(alpha = 0.2f * p), r.width * 0.17f * p, it)
                drawCircle(Ill.BLUE.copy(alpha = 0.75f * p), r.width * 0.17f * p, it, style = Stroke(2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))))
            }
            rivals.forEach { token(it, tr, Ill.RIVAL) }
            mine.forEach { token(it, tr, Ill.MINE) }
        } else {
            // cada um gruda no seu adversário (pelo lado do próprio gol)
            val rivals = listOf(at(0.22f, 0.32f), at(0.5f, 0.26f), at(0.78f, 0.32f))
            val mine = listOf(at(0.22f, 0.74f), at(0.5f, 0.8f), at(0.78f, 0.74f))
            rivals.forEach { token(it, tr, Ill.RIVAL) }
            mine.forEachIndexed { i, s ->
                val stop = rivals[i] + Offset(0f, tr * 2.3f)
                val pos = s + (stop - s) * p
                drawLine(Color.White.copy(alpha = 0.8f * p), pos, rivals[i], 2.4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 4f)))
                token(pos, tr, Ill.MINE)
            }
        }
    }
}

@Composable
fun OffsideIllustration(offside: String?, modifier: Modifier = Modifier) {
    val on = offside == "Sim"
    val p = drawProgress(on, 900)
    val fl = flowPhase()
    Canvas(modifier) {
        Ill.flow = fl
        val r = Rect(Offset(size.width * 0.04f, size.height * 0.05f), Size(size.width * 0.92f, size.height * 0.88f))
        pitch(r, vertical = true)
        fun at(x: Float, y: Float) = Offset(r.left + r.width * x, r.top + r.height * y)
        val tr = r.width * 0.082f
        val xs = listOf(0.16f, 0.38f, 0.62f, 0.84f)
        val deep = listOf(0.7f, 0.73f, 0.69f, 0.72f)
        if (on) {
            // a defesa sobe junta: o atacante fica para trás = impedimento
            val lineY = 0.5f
            val y = r.top + r.height * lineY
            val attacker = at(0.5f, 0.72f)
            token(attacker, tr, Ill.RIVAL)
            xs.forEachIndexed { i, x ->
                val from = at(x, deep[i])
                token(from + (at(x, lineY) - from) * p, tr, Ill.MINE)
            }
            val lp = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
            drawLine(Color(0xFFFFD24A).copy(alpha = lp), Offset(r.left, y + tr * 1.5f), Offset(r.right, y + tr * 1.5f), 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f)))
            if (lp > 0f) {
                // bandeira do assistente levantada
                val base = Offset(r.right - r.width * 0.3f, r.top + r.height * 0.33f)
                val top = base - Offset(0f, r.height * 0.16f * lp)
                drawLine(Color(0xFF111827), base, top, 4f, StrokeCap.Round)
                val fw = r.width * 0.18f
                val fh = r.height * 0.1f
                for (i in 0 until 2) for (j in 0 until 2) {
                    drawRect(if ((i + j) % 2 == 0) Color(0xFFEF4444) else Color(0xFFFACC15), Offset(top.x + i * fw / 2, top.y + j * fh / 2), Size(fw / 2, fh / 2), alpha = lp)
                }
                drawRect(Color(0x55000000), Offset(top.x, top.y), Size(fw, fh), style = Stroke(1.5f), alpha = lp)
            }
        } else {
            // defesa fica atrás, em linha, cobrindo o espaço
            val attacker = at(0.5f, 0.45f)
            token(attacker, tr, Ill.RIVAL)
            xs.forEachIndexed { i, x ->
                val from = at(x, deep[i] - 0.12f)
                token(from + (at(x, deep[i]) - from) * p, tr, Ill.MINE)
            }
            for (x in listOf(0.27f, 0.73f)) arrow(listOf(at(x, 0.58f), at(x, 0.66f)), Ill.BLUE, Ill.BLUE_D, r.width * 0.05f, p)
        }
    }
}
