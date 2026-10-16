package com.osmaicoach.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Visual no estilo do próprio OSM: painéis azul-marinho com borda clara no topo, rótulo pequeno azul + valor branco
 * em negrito, campo verde em perspectiva, setas vermelhas/azuis/laranja e barras azuis com bolinha branca.
 */
object OsmColors {
    val NIGHT_TOP = Color(0xFF16346E)
    val NIGHT_MID = Color(0xFF0B1A3A)
    val NIGHT_BOTTOM = Color(0xFF060E22)
    val PANEL_TOP = Color(0xFF13305F)
    val PANEL_BOTTOM = Color(0xFF0B1C3E)
    val PANEL_EDGE = Color(0xFF3F7BD6)
    val BLUE = Color(0xFF1E9BFF)
    val CAPTION = Color(0xFF39A7FF)
    val ORANGE = Color(0xFFFF9F1C)
    val RED = Color(0xFFE53935)
    val YELLOW = Color(0xFFFFC21A)
    val GRASS_LIGHT = Color(0xFF3DB83A)
    val GRASS_DARK = Color(0xFF248F27)
    val GRASS_EDGE = Color(0xFF8A5A2B)
    val TRACK = Color(0xFF1A2742)
}

private val PANEL_SHAPE = RoundedCornerShape(10.dp)

/** Painel do OSM: degradê azul-marinho e um filete claro no topo. */
fun Modifier.osmPanel(shape: RoundedCornerShape = PANEL_SHAPE): Modifier = this
    .clip(shape)
    .background(Brush.verticalGradient(listOf(OsmColors.PANEL_TOP, OsmColors.PANEL_BOTTOM)))
    .border(1.dp, Brush.verticalGradient(listOf(OsmColors.PANEL_EDGE.copy(alpha = 0.55f), Color.Transparent)), shape)

/** Fundo da tela: céu de estádio à noite. */
val OSM_BACKGROUND = Brush.verticalGradient(listOf(OsmColors.NIGHT_TOP, OsmColors.NIGHT_MID, OsmColors.NIGHT_BOTTOM))

/** Cabeçalho de um controle como no jogo: nome pequeno azul e, embaixo, o valor branco em negrito. */
@Composable
fun OsmCaption(title: String, value: String, modifier: Modifier = Modifier, valueSize: Int = 15) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontSize = 11.sp, color = OsmColors.CAPTION, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = valueSize.sp, color = Color.White, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

/** Os três pontinhos do jogo (posição da opção na lista). */
@Composable
fun OsmDots(count: Int, selected: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (i in 0 until count) {
            Box(Modifier.size(5.dp).clip(RoundedCornerShape(50)).background(if (i == selected) Color.White else Color(0x66FFFFFF)))
        }
    }
}

// ------------------------------------------------------------------ desenhos

private fun DrawScope.osmArrowHead(tip: Offset, from: Offset, color: Color, len: Float) {
    val a = atan2(tip.y - from.y, tip.x - from.x)
    val p = Path().apply {
        moveTo(tip.x, tip.y)
        lineTo(tip.x - len * cos(a - 0.45f), tip.y - len * sin(a - 0.45f))
        lineTo(tip.x - len * cos(a + 0.45f), tip.y - len * sin(a + 0.45f))
        close()
    }
    drawPath(p, color)
}

/** Cor mais escura (borda e lado da sombra do relevo). */
private fun Color.shade(f: Float): Color = Color(red * f, green * f, blue * f, alpha)

/** Seta com relevo, como as do OSM: sombra no chão, borda escura, cor e um brilho fino em cima. */
private fun DrawScope.osmArrow(from: Offset, to: Offset, color: Color, w: Float) {
    val sh = Offset(w * 0.45f, w * 0.55f)
    drawLine(Color(0x55000000), from + sh, to + sh, strokeWidth = w, cap = StrokeCap.Round)
    osmArrowHead(to + sh, from + sh, Color(0x55000000), w * 2.6f)
    drawLine(color.shade(0.6f), from, to, strokeWidth = w * 1.45f, cap = StrokeCap.Round)
    osmArrowHead(to, from, color.shade(0.6f), w * 3.1f)
    drawLine(color, from, to, strokeWidth = w, cap = StrokeCap.Round)
    osmArrowHead(to, from, color, w * 2.6f)
    val hl = Offset(-w * 0.18f, -w * 0.22f)
    drawLine(Color(0x66FFFFFF), from + hl, to + hl - (to - from) * 0.12f, strokeWidth = w * 0.28f, cap = StrokeCap.Round)
}

/** Caminho (curva) com o mesmo relevo das setas. */
private fun DrawScope.relief(path: Path, color: Color, w: Float) {
    val sh = Offset(w * 0.45f, w * 0.55f)
    drawContext.canvas.save()
    drawContext.canvas.translate(sh.x, sh.y)
    drawPath(path, Color(0x55000000), style = Stroke(w, cap = StrokeCap.Round))
    drawContext.canvas.restore()
    drawPath(path, color.shade(0.6f), style = Stroke(w * 1.45f, cap = StrokeCap.Round))
    drawPath(path, color, style = Stroke(w, cap = StrokeCap.Round))
}

/** Campo verde em perspectiva (como o card "Estilo de jogo"). Devolve a função que leva (0..1, 0..1) ao trapézio. */
private fun DrawScope.perspectivePitch(): (Float, Float) -> Offset {
    val w = size.width
    val h = size.height
    val top = h * 0.12f
    val bottom = h * 0.86f
    val inset = w * 0.12f
    fun map(u: Float, v: Float): Offset {
        val y = top + (bottom - top) * v
        val half = (w / 2 - inset) + inset * v
        return Offset(w / 2 + (u - 0.5f) * 2f * half, y)
    }
    val turf = Path().apply {
        val a = map(0f, 0f); val b = map(1f, 0f); val c = map(1f, 1f); val d = map(0f, 1f)
        moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
    }
    // borda de terra embaixo (o "bolo" do campo do jogo)
    val edge = Path().apply {
        val c = map(1f, 1f); val d = map(0f, 1f)
        moveTo(d.x, d.y); lineTo(c.x, c.y); lineTo(c.x, c.y + h * 0.05f); lineTo(d.x, d.y + h * 0.05f); close()
    }
    drawPath(edge, OsmColors.GRASS_EDGE)
    drawPath(turf, Brush.verticalGradient(listOf(OsmColors.GRASS_DARK, OsmColors.GRASS_LIGHT), map(0f, 0f).y, map(0f, 1f).y))
    for (i in 1 until 8 step 2) {
        val stripe = Path().apply {
            val a = map(i / 8f, 0f); val b = map((i + 1) / 8f, 0f); val c = map((i + 1) / 8f, 1f); val d = map(i / 8f, 1f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(stripe, Color(0x14FFFFFF))
    }
    val line = Color(0xB3FFFFFF)
    val lw = 1.4.dp.toPx()
    drawPath(turf, line, style = Stroke(lw))
    drawLine(line, map(0.5f, 0f), map(0.5f, 1f), lw)
    // áreas
    for (side in listOf(0f, 1f)) {
        val x0 = if (side == 0f) 0f else 0.86f
        val x1 = if (side == 0f) 0.14f else 1f
        val p = Path().apply {
            val a = map(x0, 0.25f); val b = map(x1, 0.25f); val c = map(x1, 0.75f); val d = map(x0, 0.75f)
            moveTo(a.x, a.y); lineTo(b.x, b.y); lineTo(c.x, c.y); lineTo(d.x, d.y); close()
        }
        drawPath(p, line, style = Stroke(lw))
    }
    val c = map(0.5f, 0.5f)
    drawOval(line, Offset(c.x - w * 0.06f, c.y - h * 0.09f), Size(w * 0.12f, h * 0.18f), style = Stroke(lw))
    return ::map
}

/** Desenho do estilo de jogo igual ao do OSM (o ataque vai para a direita). */
@Composable
fun StylePitch(style: String?, modifier: Modifier = Modifier) {
    val st = Osm.style(style) ?: style ?: ""
    Canvas(modifier) {
        val m = perspectivePitch()
        val red = OsmColors.RED
        val blue = OsmColors.BLUE
        val w = 4.dp.toPx()
        when (st) {
            "Jogar pelas alas" -> {
                osmArrow(m(0.2f, 0.12f), m(0.85f, 0.12f), red, w)
                osmArrow(m(0.2f, 0.88f), m(0.85f, 0.88f), red, w)
            }
            "Jogo de passe" -> {
                val p = Path()
                val start = m(0.12f, 0.55f)
                p.moveTo(start.x, start.y)
                var last = start
                var prev = start
                for (i in 1..24) {
                    val u = 0.12f + i * 0.032f
                    val v = 0.55f + 0.18f * sin(i * 0.9f).toFloat()
                    val pt = m(u, v)
                    p.lineTo(pt.x, pt.y)
                    prev = last
                    last = pt
                }
                relief(p, red, w)
                osmArrowHead(last, prev, red.shade(0.6f), w * 3.1f)
                osmArrowHead(last, prev, red, w * 2.6f)
            }
            "Bola longa" -> {
                val a = m(0.12f, 0.6f)
                val b = m(0.86f, 0.6f)
                val ctrl = Offset((a.x + b.x) / 2, size.height * -0.25f)
                val p = Path().apply { moveTo(a.x, a.y); quadraticTo(ctrl.x, ctrl.y, b.x, b.y) }
                relief(p, red, w)
                osmArrowHead(b, Offset((ctrl.x + b.x) / 2, (ctrl.y + b.y) / 2), red, w * 2.6f)
            }
            "Contra-ataque" -> {
                // o rival ataca (azul, ondulado para a esquerda); a bola recuperada sai longa (vermelho) para a direita
                val p = Path()
                val s = m(0.85f, 0.62f)
                p.moveTo(s.x, s.y)
                var last = s
                var prev = s
                for (i in 1..14) {
                    val u = 0.85f - i * 0.045f
                    val v = 0.62f + 0.08f * sin(i * 1.1f).toFloat()
                    val pt = m(u, v)
                    p.lineTo(pt.x, pt.y)
                    prev = last
                    last = pt
                }
                relief(p, blue, w * 0.8f)
                osmArrowHead(last, prev, blue, w * 2.2f)
                val a = m(0.22f, 0.45f)
                val b = m(0.82f, 0.45f)
                val ctrl = Offset((a.x + b.x) / 2, size.height * -0.15f)
                val q = Path().apply { moveTo(a.x, a.y); quadraticTo(ctrl.x, ctrl.y, b.x, b.y) }
                relief(q, red, w)
                osmArrowHead(b, Offset((ctrl.x + b.x) / 2, (ctrl.y + b.y) / 2), red, w * 2.6f)
            }
            "Remate à vista" -> {
                for ((i, v) in listOf(0.35f, 0.5f, 0.65f).withIndex()) {
                    osmArrow(m(0.52f, v), m(0.7f, 0.42f + i * 0.08f), red, w * 0.8f)
                }
                for ((u, v) in listOf(0.74f to 0.38f, 0.78f to 0.52f, 0.73f to 0.62f, 0.8f to 0.45f)) {
                    val c = m(u, v)
                    drawOval(Color(0x55000000), Offset(c.x - w * 0.9f, c.y + w * 0.7f), Size(w * 2.2f, w * 0.8f))
                    drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFBDBDBD)), Offset(c.x - w * 0.4f, c.y - w * 0.4f), w * 1.6f), w * 1.1f, c)
                    drawCircle(Color(0xFF222222), w * 1.1f, c, style = Stroke(1.dp.toPx()))
                }
            }
        }
    }
}

/** Rosto do "Desarme" como no jogo: mais calmo no Cuidadoso, mais bravo no Extremo. */
@Composable
fun TackleFace(tackle: String?, modifier: Modifier = Modifier) {
    val lvl = when (Osm.tackle(tackle) ?: tackle) {
        "Cuidadoso" -> 0
        "Normal" -> 1
        "Agressivo" -> 2
        "Extremo" -> 3
        else -> 1
    }
    Canvas(modifier) {
        val r = size.minDimension * 0.42f
        val c = Offset(size.width / 2, size.height / 2)
        // cabelo e rosto
        // sombra no chão e volume: luz vindo do alto à esquerda, como os bonecos 3D do jogo
        drawOval(Color(0x55000000), Offset(c.x - r * 0.7f, c.y + r * 0.98f), Size(r * 1.4f, r * 0.22f))
        drawCircle(
            Brush.radialGradient(listOf(Color(0xFF8A5530), Color(0xFF4A2A14)), Offset(c.x - r * 0.3f, c.y - r * 0.6f), r * 1.4f),
            r * 1.02f, Offset(c.x, c.y - r * 0.12f)
        )
        val skin = if (lvl == 3) Color(0xFFF2A07B) else Color(0xFFF5C49C)
        drawOval(
            Brush.radialGradient(listOf(Color(0xFFFFE2C8), skin, skin.shade(0.82f)), Offset(c.x - r * 0.3f, c.y - r * 0.35f), r * 1.5f),
            Offset(c.x - r * 0.86f, c.y - r * 0.78f), Size(r * 1.72f, r * 1.82f)
        )
        drawCircle(skin, r * 0.16f, Offset(c.x - r * 0.88f, c.y + r * 0.05f))
        drawCircle(skin, r * 0.16f, Offset(c.x + r * 0.88f, c.y + r * 0.05f))
        if (lvl >= 2) {
            val cheek = Color(0x55E53935)
            drawCircle(cheek, r * 0.17f, Offset(c.x - r * 0.48f, c.y + r * 0.32f))
            drawCircle(cheek, r * 0.17f, Offset(c.x + r * 0.48f, c.y + r * 0.32f))
        }
        // olhos
        for (s in listOf(-1f, 1f)) {
            val e = Offset(c.x + s * r * 0.34f, c.y)
            drawOval(Color.White, Offset(e.x - r * 0.15f, e.y - r * 0.12f), Size(r * 0.3f, r * 0.26f))
            drawCircle(Color(0xFF2B1A10), r * 0.08f, Offset(e.x + s * r * 0.02f, e.y + r * 0.01f))
            drawCircle(Color.White, r * 0.025f, Offset(e.x + s * r * 0.02f - r * 0.03f, e.y - r * 0.03f))
            // sobrancelha: reta (calmo) até bem inclinada para dentro (bravo)
            // inclinação da sobrancelha: levantada (calmo), reta (normal), para dentro (bravo)
            val tilt = when (lvl) { 0 -> -r * 0.05f; 1 -> 0f; 2 -> r * 0.1f; else -> r * 0.16f }
            drawLine(
                Color(0xFF4A2A14), Offset(e.x - s * r * 0.2f, e.y - r * 0.24f),
                Offset(e.x + s * r * 0.2f, e.y - r * 0.24f + tilt), strokeWidth = r * 0.09f, cap = StrokeCap.Round
            )
        }
        // boca
        val mouth = Path()
        val my = c.y + r * 0.45f
        when (lvl) {
            0 -> { mouth.moveTo(c.x - r * 0.28f, my); mouth.quadraticTo(c.x, my + r * 0.22f, c.x + r * 0.28f, my) }
            1 -> { mouth.moveTo(c.x - r * 0.22f, my + r * 0.04f); mouth.lineTo(c.x + r * 0.22f, my + r * 0.04f) }
            2 -> { mouth.moveTo(c.x - r * 0.24f, my + r * 0.1f); mouth.quadraticTo(c.x, my - r * 0.08f, c.x + r * 0.24f, my + r * 0.1f) }
            else -> {
                drawOval(Color(0xFF7A1F1F), Offset(c.x - r * 0.2f, my - r * 0.06f), Size(r * 0.4f, r * 0.26f))
            }
        }
        if (lvl < 3) drawPath(mouth, Color(0xFF7A3B1F), style = Stroke(r * 0.08f, cap = StrokeCap.Round))
    }
}

/**
 * "Táticas por sector": campo verde de cima para baixo com as setas de cada setor, como o jogo desenha.
 * Avançados em azul (em cima), médios e defesas em laranja.
 */
@Composable
fun SectorPitch(attack: String?, mid: String?, def: String?, modifier: Modifier = Modifier) {
    val a = Osm.attack(attack) ?: attack
    val md = Osm.midfield(mid) ?: mid
    val d = Osm.defense(def) ?: def
    Canvas(modifier) {
        val W = size.width
        val H = size.height
        // campo inclinado (topo mais estreito) com a espessura de terra embaixo: o "bloco" 3D do jogo
        val top = H * 0.02f
        val bottom = H * 0.92f
        val inset = W * 0.1f
        fun p(x: Float, y: Float): Offset {
            val v = y.coerceIn(-0.1f, 1.1f)
            val half = (W / 2 - inset) + inset * v
            return Offset(W / 2 + (x - 0.5f) * 2f * half, top + (bottom - top) * v)
        }
        fun scaleAt(y: Float) = ((W / 2 - inset) + inset * y) / (W / 2)
        fun quad(x0: Float, y0: Float, x1: Float, y1: Float): Path = Path().apply {
            val q0 = p(x0, y0); val q1 = p(x1, y0); val q2 = p(x1, y1); val q3 = p(x0, y1)
            moveTo(q0.x, q0.y); lineTo(q1.x, q1.y); lineTo(q2.x, q2.y); lineTo(q3.x, q3.y); close()
        }
        val c3 = p(1f, 1f); val c4 = p(0f, 1f)
        drawOval(Color(0x66000000), Offset(c4.x - W * 0.02f, c3.y + H * 0.04f), Size(c3.x - c4.x + W * 0.04f, H * 0.05f))
        drawPath(Path().apply { moveTo(c4.x, c4.y); lineTo(c3.x, c3.y); lineTo(c3.x, c3.y + H * 0.045f); lineTo(c4.x, c4.y + H * 0.045f); close() }, OsmColors.GRASS_EDGE)
        drawPath(quad(0f, 0f, 1f, 1f), Brush.verticalGradient(listOf(OsmColors.GRASS_DARK, OsmColors.GRASS_LIGHT), top, bottom))
        for (i in 0 until 8 step 2) drawPath(quad(0f, i / 8f, 1f, (i + 1) / 8f), Color(0x12FFFFFF))
        val line = Color(0xE6FFFFFF)
        val lw = 1.4.dp.toPx()
        drawPath(quad(0.02f, 0.01f, 0.98f, 0.99f), line, style = Stroke(lw))
        drawLine(line, p(0.02f, 0.5f), p(0.98f, 0.5f), lw)
        val cc = p(0.5f, 0.5f)
        val cr = W * 0.13f * scaleAt(0.5f)
        drawOval(line, Offset(cc.x - cr, cc.y - cr * 0.85f), Size(cr * 2, cr * 1.7f), style = Stroke(lw))
        drawPath(quad(0.22f, 0.01f, 0.78f, 0.15f), line, style = Stroke(lw))
        drawPath(quad(0.22f, 0.85f, 0.78f, 0.99f), line, style = Stroke(lw))
        val blue = Color(0xFF2EA8FF)
        val orange = OsmColors.ORANGE
        val sw = 3.2.dp.toPx()
        fun arr(x0: Float, y0: Float, x1: Float, y1: Float, col: Color, k: Float = 1f) = osmArrow(p(x0, y0), p(x1, y1), col, sw * k * scaleAt((y0 + y1) / 2))
        // avançados
        when (a) {
            "Atacar apenas" -> arr(0.5f, 0.24f, 0.5f, 0.08f, blue)
            "Apoiar meio-campo" -> arr(0.5f, 0.1f, 0.5f, 0.24f, blue)
            else -> arr(0.5f, 0.08f, 0.5f, 0.34f, blue)
        }
        // médios
        fun cross(x: Float, y: Float, s: Float) {
            arr(x, y, x - s, y, orange, 0.6f); arr(x, y, x + s, y, orange, 0.6f)
            arr(x, y, x, y - s * 0.7f, orange, 0.6f); arr(x, y, x, y + s * 0.7f, orange, 0.6f)
        }
        when (md) {
            "Pressionar na frente" -> { arr(0.44f, 0.48f, 0.3f, 0.36f, orange); arr(0.56f, 0.48f, 0.7f, 0.36f, orange) }
            "Ajudar a defesa" -> { arr(0.44f, 0.52f, 0.3f, 0.64f, orange); arr(0.56f, 0.52f, 0.7f, 0.64f, orange) }
            else -> { cross(0.2f, 0.5f, 0.09f); cross(0.8f, 0.5f, 0.09f) }
        }
        // defesas
        when (d) {
            "Defesas atacantes" -> for (x in listOf(0.2f, 0.8f)) arr(x, 0.9f, x, 0.66f, orange)
            "Apoiar meio-campo" -> for (x in listOf(0.2f, 0.5f, 0.8f)) arr(x, 0.92f, x, 0.64f, orange)
            else -> cross(0.5f, 0.86f, 0.07f)
        }
    }
}

/** Barra do jogo: trilho escuro, parte azul até o valor, bolinha branca e o número numa caixinha preta. */
@Composable
fun OsmSlider(title: String, label: String, value: Int, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().osmPanel().padding(horizontal = 12.dp, vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OsmCaption(title, label)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.weight(1f).height(22.dp)) {
                val y = size.height / 2
                val f = value.coerceIn(0, 100) / 100f
                val th = 3.dp.toPx()
                drawLine(OsmColors.TRACK, Offset(0f, y), Offset(size.width, y), th, StrokeCap.Round)
                drawLine(OsmColors.BLUE, Offset(0f, y), Offset(size.width * f, y), th, StrokeCap.Round)
                val k = Offset(size.width * f, y)
                drawCircle(Color(0x66000000), 8.dp.toPx(), k + Offset(1.5.dp.toPx(), 2.dp.toPx()))
                drawCircle(Brush.radialGradient(listOf(Color.White, Color(0xFFC9D3E0)), k - Offset(2.dp.toPx(), 2.dp.toPx()), 10.dp.toPx()), 8.dp.toPx(), k)
            }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.size(width = 34.dp, height = 26.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black), contentAlignment = Alignment.Center) {
                Text("$value", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
        }
    }
}

/** Bonequinho de jogador (camisa e calção), usado em Marcação e Fora-de-jogo. */
private fun DrawScope.player(cx: Float, base: Float, hgt: Float, shirt: Color, shorts: Color, skin: Color = Color(0xFFE0A97C)) {
    val headR = hgt * 0.11f
    drawOval(Color(0x66000000), Offset(cx - hgt * 0.22f, base - hgt * 0.03f), Size(hgt * 0.44f, hgt * 0.07f))
    drawCircle(
        Brush.radialGradient(listOf(Color(0xFFFFE0C4), skin, skin.shade(0.75f)), Offset(cx - headR * 0.4f, base - hgt + headR * 0.6f), headR * 1.6f),
        headR, Offset(cx, base - hgt + headR)
    )
    val bodyTop = base - hgt + headR * 2.1f
    drawRoundRect(
        Brush.horizontalGradient(listOf(shirt.shade(1.25f.coerceAtMost(1f)), shirt, shirt.shade(0.65f)), cx - hgt * 0.16f, cx + hgt * 0.16f),
        Offset(cx - hgt * 0.16f, bodyTop), Size(hgt * 0.32f, hgt * 0.36f), CornerRadius(hgt * 0.05f)
    )
    drawRoundRect(shirt, Offset(cx - hgt * 0.25f, bodyTop + hgt * 0.02f), Size(hgt * 0.09f, hgt * 0.26f), CornerRadius(hgt * 0.04f))
    drawRoundRect(shirt, Offset(cx + hgt * 0.16f, bodyTop + hgt * 0.02f), Size(hgt * 0.09f, hgt * 0.26f), CornerRadius(hgt * 0.04f))
    val shortsTop = bodyTop + hgt * 0.36f
    drawRect(shorts, Offset(cx - hgt * 0.16f, shortsTop), Size(hgt * 0.32f, hgt * 0.14f))
    drawRect(skin, Offset(cx - hgt * 0.13f, shortsTop + hgt * 0.14f), Size(hgt * 0.08f, hgt * 0.2f))
    drawRect(skin, Offset(cx + hgt * 0.05f, shortsTop + hgt * 0.14f), Size(hgt * 0.08f, hgt * 0.2f))
    drawRect(Color.White, Offset(cx - hgt * 0.14f, base - hgt * 0.06f), Size(hgt * 0.1f, hgt * 0.06f))
    drawRect(Color.White, Offset(cx + hgt * 0.04f, base - hgt * 0.06f), Size(hgt * 0.1f, hgt * 0.06f))
}

@Composable
fun MarkingFigure(marking: String?, modifier: Modifier = Modifier) {
    val man = (Osm.marking(marking) ?: marking) == "Homem-a-homem"
    Canvas(modifier) {
        val base = size.height * 0.95f
        val hgt = size.height * 0.88f
        if (man) {
            player(size.width / 2 - hgt * 0.14f, base, hgt, OsmColors.RED, Color(0xFF8B1A1A))
            player(size.width / 2 + hgt * 0.14f, base, hgt * 0.97f, Color(0xFFEDEDED), Color(0xFF1E3A8A))
        } else {
            player(size.width / 2, base, hgt, Color(0xFF1E2A6B), Color(0xFF15204F))
            drawRect(OsmColors.YELLOW, Offset(size.width / 2 - hgt * 0.02f, base - hgt * 0.7f), Size(hgt * 0.04f, hgt * 0.28f))
        }
    }
}

@Composable
fun OffsideFigure(offside: String?, modifier: Modifier = Modifier) {
    val on = (offside ?: "").trim().equals("Sim", true)
    Canvas(modifier) {
        val base = size.height * 0.95f
        val hgt = size.height * 0.8f
        val cx = size.width / 2
        player(cx, base, hgt, OsmColors.YELLOW, Color(0xFF222222))
        if (on) {
            // bandeirinha levantada
            val hx = cx + hgt * 0.22f
            drawLine(Color(0xFF333333), Offset(hx, base - hgt * 0.62f), Offset(hx + hgt * 0.06f, base - hgt * 1.08f), 2.dp.toPx())
            val flag = Path().apply {
                moveTo(hx + hgt * 0.06f, base - hgt * 1.08f)
                lineTo(hx + hgt * 0.3f, base - hgt * 1.02f)
                lineTo(hx + hgt * 0.04f, base - hgt * 0.9f)
                close()
            }
            drawPath(flag, OsmColors.RED)
        } else {
            drawLine(Color(0xFF333333), Offset(cx + hgt * 0.2f, base - hgt * 0.62f), Offset(cx + hgt * 0.24f, base - hgt * 0.32f), 2.dp.toPx())
        }
    }
}

/** Card de duas metades do jogo (Estilo de jogo | Desarme, Marcação | Fora-de-jogo) com o filete verde no meio. */
@Composable
fun OsmSplitCard(left: @Composable () -> Unit, right: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Box(Modifier.weight(1f).osmPanel().padding(10.dp), contentAlignment = Alignment.TopCenter) { left() }
        Box(Modifier.width(6.dp).height(1.dp))
        Box(Modifier.weight(1f).osmPanel().padding(10.dp), contentAlignment = Alignment.TopCenter) { right() }
    }
}

/** Título de seção do jogo ("Define a tua tática para o próximo jogo"). */
@Composable
fun OsmTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(vertical = 8.dp)
    )
}


// ---------------------------------------------------------------------------------------------
// Arte 3D de verdade (renderizada em Blender). As versões em Canvas acima ficam como reserva.

/** Fundo do app: estádio noturno com refletores, coberto por um véu azul-marinho que garante contraste. */
@Composable
fun OsmAppBackground(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(OsmColors.NIGHT_BOTTOM)) {
        Image(osmArt("bg_stadium"), null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color(0x8C061331), 0.25f to Color(0xC7081A40),
                    0.6f to Color(0xE6081A40), 1f to Color(0xF2050E24)
                )
            )
        )
        content()
    }
}

@Composable
fun StyleArt(style: String?, modifier: Modifier = Modifier) {
    val name = when (Osm.style(style) ?: style) {
        "Jogar pelas alas" -> "style_alas"
        "Jogo de passe" -> "style_passe"
        "Contra-ataque" -> "style_contra"
        "Remate à vista" -> "style_remate"
        "Bola longa" -> "style_longa"
        else -> "style_passe"
    }
    Image(osmArt(name), style, modifier, contentScale = ContentScale.Fit)
}

@Composable
fun TackleArt(tackle: String?, modifier: Modifier = Modifier) {
    val name = when (Osm.tackle(tackle) ?: tackle) {
        "Cuidadoso" -> "tackle_cuidadoso"
        "Agressivo" -> "tackle_agressivo"
        "Extremo" -> "tackle_extremo"
        else -> "tackle_normal"
    }
    Image(osmArt(name), tackle, modifier, contentScale = ContentScale.Fit)
}

/** Campo em bloco 3D com as três camadas (avançados, médios, defesas) sobrepostas na mesma moldura. */
@Composable
fun SectorArt(attack: String?, mid: String?, def: String?, modifier: Modifier = Modifier) {
    val a = Osm.ATTACK.indexOf(Osm.attack(attack) ?: attack).coerceAtLeast(0)
    val m = Osm.MIDFIELD.indexOf(Osm.midfield(mid) ?: mid).coerceAtLeast(0)
    val d = Osm.DEFENSE.indexOf(Osm.defense(def) ?: def).coerceAtLeast(0)
    Box(modifier) {
        for (layer in listOf("sector_base", "sector_def_$d", "sector_mid_$m", "sector_att_$a"))
            Image(osmArt(layer), null, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
    }
}

@Composable
fun MarkingArt(marking: String?, modifier: Modifier = Modifier) {
    val man = (Osm.marking(marking) ?: marking) == "Homem-a-homem"
    Image(osmArt(if (man) "mark_homem" else "mark_zona"), marking, modifier, contentScale = ContentScale.Fit)
}

@Composable
fun OffsideArt(offside: String?, modifier: Modifier = Modifier) {
    Image(osmArt(if (offside == "Sim") "offside_sim" else "offside_nao"), offside, modifier, contentScale = ContentScale.Fit)
}
