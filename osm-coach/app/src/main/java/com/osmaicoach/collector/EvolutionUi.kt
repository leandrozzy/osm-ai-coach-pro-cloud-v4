package com.osmaicoach.collector

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val GOOD = Color(0xFF4ADE80)
private val BADC = Color(0xFFFF6B6B)
private val MUTE = Color(0xFF9FB6D8)

private fun signColor(v: Double?): Color = when {
    v == null -> MUTE
    v > 0.0001 -> GOOD
    v < -0.0001 -> BADC
    else -> MUTE
}

/** Cartão no estilo do jogo: legenda azul pequena em cima e o conteúdo embaixo. */
@Composable
private fun EvoCard(title: String, icon: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).osmPanel().padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 16.sp)
            Text(
                title.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = OsmColors.CAPTION,
                modifier = Modifier.padding(start = 8.dp), letterSpacing = 0.8.sp
            )
        }
        Column(Modifier.padding(top = 10.dp)) { content() }
    }
}

/** Número grande com rótulo e variação colorida (verde sobe, vermelho cai). */
@Composable
private fun Metric(label: String, value: String, delta: String?, deltaColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(Color(0x22000000)).padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
        Text(label, fontSize = 10.sp, color = MUTE, maxLines = 1)
        if (delta != null) Text(delta, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = deltaColor, maxLines = 1)
    }
}

@Composable
private fun ResultDot(r: String) {
    val c = when (r) {
        "V" -> GOOD
        "D" -> BADC
        else -> OsmColors.YELLOW
    }
    Box(
        Modifier.padding(end = 4.dp).width(24.dp).height(24.dp).clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.9f)),
        contentAlignment = Alignment.Center
    ) { Text(r, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1C3E)) }
}

/** Valor do elenco semana a semana: barras com o topo arredondado e a última em destaque. */
@Composable
private fun WeekBars(weeks: List<Weekly.Week>) {
    val vals = weeks.takeLast(8).map { it.value }
    if (vals.count { it != null } < 2) return
    val mx = vals.filterNotNull().maxOrNull() ?: return
    val mn = (vals.filterNotNull().minOrNull() ?: 0.0) * 0.9
    Canvas(Modifier.fillMaxWidth().height(70.dp).padding(top = 10.dp)) {
        val n = vals.size
        val gap = 8.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        vals.forEachIndexed { i, v ->
            if (v == null) return@forEachIndexed
            val f = if (mx - mn <= 0.0) 1f else ((v - mn) / (mx - mn)).toFloat().coerceIn(0.08f, 1f)
            val h = size.height * f
            val last = i == n - 1
            drawRoundRect(
                Brush.verticalGradient(
                    if (last) listOf(OsmColors.YELLOW, Color(0xFFFF9F1A)) else listOf(OsmColors.BLUE, Color(0xFF0E5FB8)),
                    startY = size.height - h, endY = size.height
                ),
                topLeft = Offset(i * (bw + gap), size.height - h), size = Size(bw, h), cornerRadius = CornerRadius(6.dp.toPx())
            )
        }
    }
}

/** Placar da tática: quanto o app acertou e qual tática mais rendeu. */
@Composable
fun ScoreboardCard(s: Scoreboard.Stats) {
    EvoCard("Placar da tática", "🎯") {
        if (s.games == 0) {
            Text("Ainda sem jogos com resultado. Depois de cada jogo, abra o resultado no OSM: o placar começa a contar.", fontSize = 12.sp, color = MUTE)
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("jogos", "${s.games}", "${s.v}V ${s.e}E ${s.d}D", MUTE, Modifier.weight(1f))
            Metric("vitórias", "${s.winRate}%", s.avgWinPred?.let { "previsto $it%" }, MUTE, Modifier.weight(1f))
            Metric(
                "acertos", if (s.predicted > 0) "${(s.hits * 100.0 / s.predicted).roundToInt()}%" else "—",
                if (s.predicted > 0) "${s.hits} de ${s.predicted}" else null, MUTE, Modifier.weight(1f)
            )
        }
        if (s.lastFive.isNotEmpty()) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Últimos: ", fontSize = 12.sp, color = MUTE)
                for (r in s.lastFive) ResultDot(r)
            }
        }
        s.best?.let {
            Text(
                "🏆 Melhor: ${it.label} — ${it.v}V ${it.e}E ${it.d}D (${"%.1f".format(it.points).replace('.', ',')} pts/jogo)",
                fontSize = 12.sp, color = GOOD, modifier = Modifier.padding(top = 8.dp)
            )
        }
        s.worst?.takeIf { it.label != s.best?.label }?.let {
            Text(
                "⚠ Pior: ${it.label} — ${it.v}V ${it.e}E ${it.d}D: o app evita repetir.",
                fontSize = 12.sp, color = BADC, modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/** Evolução do time: semana, livro de caixa, ganho do treino e alertas de valor. */
@Composable
fun EvolutionPanel(p: Repo.ProgressData) {
    // ---- semana
    EvoCard("Resumo da semana", "📅") {
        val d = p.week
        if (d == null) {
            Text("Leia o Plantel do seu time para começar o resumo semanal.", fontSize = 12.sp, color = MUTE)
        } else {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Metric("valor do elenco", d.cur.value?.let { moneyText(it) } ?: "?", d.value?.let { moneyText(it, true) }, signColor(d.value), Modifier.weight(1f))
                Metric("caixa", d.cur.cash?.let { moneyText(it) } ?: "?", d.cash?.let { moneyText(it, true) }, signColor(d.cash), Modifier.weight(1f))
                Metric(
                    "força do XI", d.cur.xi?.let { "%.1f".format(it).replace('.', ',') } ?: "?",
                    d.xi?.let { (if (it > 0) "+" else "") + "%.1f".format(it).replace('.', ',') }, signColor(d.xi), Modifier.weight(1f)
                )
            }
            if (d.prev == null) {
                Text("Primeira semana registrada: a comparação aparece na próxima.", fontSize = 11.sp, color = MUTE, modifier = Modifier.padding(top = 8.dp))
            } else if (d.games > 0) {
                Text("Nesta semana: ${d.games} jogo(s), ${d.wins} vitória(s).", fontSize = 12.sp, color = Color.White, modifier = Modifier.padding(top = 8.dp))
            }
            WeekBars(p.weeks)
        }
    }
    // ---- livro de caixa
    EvoCard("Livro de caixa", "💰") {
        val l = p.ledger
        if (l.buys + l.sells == 0) {
            Text(
                "Compras e vendas aparecem aqui sozinhas: jogador novo no Plantel é contratação; quem some numa leitura completa do Plantel foi vendido.",
                fontSize = 12.sp, color = MUTE
            )
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("gasto", moneyText(l.spent), "${l.buys} compra(s)", MUTE, Modifier.weight(1f))
            Metric("recebido", moneyText(l.received), "${l.sells} venda(s)", MUTE, Modifier.weight(1f))
            Metric("saldo", moneyText(l.balance, true), null, signColor(l.balance), Modifier.weight(1f))
        }
        for (deal in l.deals.take(6)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(deal.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val txt = when {
                    deal.profit != null -> "${moneyText(deal.bought!!)} → ${moneyText(deal.sold!!)}"
                    deal.sold != null -> "vendido ${moneyText(deal.sold)}"
                    deal.bought != null -> "comprado ${moneyText(deal.bought)}"
                    else -> "?"
                }
                Text(txt + (if (deal.estimated) "*" else ""), fontSize = 12.sp, color = MUTE)
                deal.profit?.let {
                    Text(" ${moneyText(it, true)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = signColor(it))
                }
            }
        }
        if (l.deals.any { it.estimated }) {
            Text("* preço estimado pelo valor do Plantel (o da lista de transferências vale quando o jogador foi visto lá).", fontSize = 10.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
    // ---- treino
    EvoCard("Quanto o treino rendeu", "🏋") {
        val t = p.train
        if (t == null || t.trained == 0) {
            Text(
                "Leia o Plantel de vez em quando (ao menos 1x por semana) com treinos rodando: eu comparo a força de quem treinou com a de quem não treinou.",
                fontSize = 12.sp, color = MUTE
            )
            return@EvoCard
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Metric("força • treinaram", fmt1(t.trainedGain, true), "${t.trained} jogador(es)", MUTE, Modifier.weight(1f))
            Metric("força • sem treino", fmt1(t.othersGain, true), "${t.others} jogador(es)", MUTE, Modifier.weight(1f))
            Metric("valor do elenco", moneyText(t.valueGain, true), "${t.days} dias", MUTE, Modifier.weight(1f))
        }
        val verdict = when {
            t.edge >= 1.0 -> "✔ O treino está rendendo: +${fmt1(t.edge)} de força por jogador treinado em ${t.days} dias. Vale manter os 5 treinadores ocupados."
            t.edge > 0 -> "Rende pouco por enquanto (+${fmt1(t.edge)}). Treine quem é titular e jovem: é onde a força vira vitória e valor."
            else -> "Ainda sem ganho medido. Leia o Plantel antes e depois de alguns treinos para eu medir."
        }
        Text(verdict, fontSize = 12.sp, color = if (t.edge >= 1.0) GOOD else Color.White, modifier = Modifier.padding(top = 8.dp))
        if (t.top.isNotEmpty()) {
            Text("Quem mais subiu: " + t.top.joinToString(" • ") { "${it.first} +${it.second}" }, fontSize = 11.sp, color = MUTE, modifier = Modifier.padding(top = 4.dp))
        }
    }
    // ---- alerta de valor caindo
    if (p.falling.isNotEmpty()) {
        EvoCard("Vender antes que caia mais", "📉") {
            for ((name, v0, v1) in p.falling.take(4)) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("${moneyText(v0)} → ${moneyText(v1)}", fontSize = 12.sp, color = BADC, textAlign = TextAlign.End)
                }
            }
            Text("Valor caiu 10% ou mais em 3 semanas: se não é titular, venda agora.", fontSize = 11.sp, color = MUTE, modifier = Modifier.padding(top = 6.dp))
        }
    }
    ScoreboardCard(p.score)
}

private fun fmt1(v: Double, sign: Boolean = false): String =
    (if (sign && v > 0) "+" else "") + "%.1f".format(v).replace('.', ',')
