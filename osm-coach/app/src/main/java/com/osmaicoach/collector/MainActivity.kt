package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppScope.scope.launch { Repo(applicationContext).importLegacy() }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) { App() }
            }
        }
    }
}

// ------------------------------------------------------------------ utilidades

private fun serviceEnabled(ctx: Context): Boolean {
    val s = android.provider.Settings.Secure.getString(
        ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val id = ComponentName(ctx, CaptureService::class.java).flattenToString()
    return s.split(':').any { it.equals(id, ignoreCase = true) }
}

private fun openOsm(ctx: Context): Boolean {
    val i = ctx.packageManager.getLaunchIntentForPackage(OSM_PACKAGE) ?: return false
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    ctx.startActivity(i)
    return true
}

private fun openSettings(ctx: Context, action: String, pkg: Boolean = false) {
    val i = Intent(action)
    if (pkg) i.data = Uri.parse("package:" + ctx.packageName)
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(i) } catch (e: Exception) { Diag.lastError = "Não consegui abrir: $action" }
}

private fun ago(ms: Long): String {
    if (ms <= 0L) return "nunca"
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "há ${s}s"
        s < 3600 -> "há ${s / 60} min"
        s < 86400 -> "há ${s / 3600} h"
        else -> "há ${s / 86400} d"
    }
}

private fun fmtTime(ms: Long): String = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date(ms))

@Composable
private fun rememberTick(ms: Long = 1500L): Int {
    var t by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(ms)
            t++
        }
    }
    return t
}

@Composable
private fun Title(text: String) = Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color(0xFFAAB4C0), modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Box2(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Column(Modifier.padding(12.dp)) { content() } }
}

// ------------------------------------------------------------------ dados de tela

data class SlotSummary(val slot: Int, val title: String, val pct: Int, val updated: Long)
data class TodayData(val active: Boolean = false, val slots: List<SlotSummary> = emptyList())

data class SlotData(
    val fields: Map<String, StoredField> = emptyMap(),
    val players: List<PlayerEntity> = emptyList(),
    val matches: List<MatchEntity> = emptyList(),
    val tactic: PlanEntity? = null,
    val market: PlanEntity? = null,
    val learning: List<LearningEntity> = emptyList(),
    val sections: Map<String, Long> = emptyMap(),
    val completeness: Completeness.Result? = null,
    val baseline: List<String> = emptyList()
)

data class SessionRow(val s: SessionEntity, val counts: String, val unassigned: Int)

private suspend fun loadSlot(ctx: Context, slot: Int): SlotData {
    val repo = Repo(ctx)
    val dao = repo.dao
    val f = repo.fieldMap(slot)
    val players = dao.playersOf(slot)
    val matches = dao.matchesOf(slot)
    val market = dao.snapshots("TRANSFER", slot).isNotEmpty()
    val comp = Completeness.compute(f, players.count { it.owner == "MY" }, matches.size, market)
    return SlotData(
        fields = f, players = players, matches = matches,
        tactic = dao.plan(slot, "tactic"), market = dao.plan(slot, "market"),
        learning = dao.learningOf(slot),
        sections = dao.lastBySection(slot).associate { it.type to it.c },
        completeness = comp, baseline = Director.marketBaseline(repo, slot)
    )
}

private suspend fun loadToday(ctx: Context): TodayData {
    val repo = Repo(ctx)
    val list = ArrayList<SlotSummary>()
    for (slot in 1..4) {
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).count { it.owner == "MY" }
        val matches = repo.dao.matchesOf(slot).size
        val market = repo.dao.snapshots("TRANSFER", slot).isNotEmpty()
        val c = Completeness.compute(f, players, matches, market)
        val title = f[K.TEAM]?.value ?: f[K.HUB_TITLE]?.value ?: "(ainda não lido)"
        val upd = f.values.maxOfOrNull { it.updatedAt } ?: 0L
        list.add(SlotSummary(slot, title, c.percent, upd))
    }
    return TodayData(Control.activeSession(ctx) != null, list)
}

// ------------------------------------------------------------------ app

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    var openSlot by remember { mutableIntStateOf(0) }
    val names = listOf("Hoje", "Sessões", "Slots", "Diretor", "Ajustes")
    Scaffold(bottomBar = {
        NavigationBar {
            names.forEachIndexed { i, n ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i; openSlot = 0 }, icon = { Text("●") }, label = { Text(n, fontSize = 11.sp) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayTab()
                1 -> SessionsTab()
                2 -> if (openSlot > 0) SlotScreen(openSlot, 0) { openSlot = 0 } else SlotsTab { openSlot = it }
                3 -> if (openSlot > 0) SlotScreen(openSlot, 5) { openSlot = 0 } else DirectorTab { tab = 3; openSlot = it }
                else -> SettingsTab()
            }
        }
    }
}

@Composable
private fun TodayTab() {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val scope = rememberCoroutineScope()
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    var msg by remember { mutableStateOf("") }
    val enabled = serviceEnabled(ctx)
    val connected = Diag.serviceConnected

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Title("OSM AI Coach")
        if (data.active) {
            Button(
                onClick = { scope.launch { Control.end(ctx); msg = "Captura encerrada. Processando em segundo plano…" } },
                modifier = Modifier.fillMaxWidth().height(84.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
            ) { Text("ENCERRAR CAPTURA E PROCESSAR", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { if (!openOsm(ctx)) msg = "OSM não encontrado." }, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar ao OSM (a mesma sessão continua)")
            }
        } else {
            Button(
                onClick = {
                    if (!enabled || !connected) {
                        msg = "Ative o serviço de acessibilidade em Ajustes antes de iniciar."
                    } else scope.launch {
                        Control.start(ctx)
                        if (!openOsm(ctx)) msg = "OSM não encontrado neste aparelho."
                    }
                },
                modifier = Modifier.fillMaxWidth().height(72.dp)
            ) { Text("Abrir OSM e iniciar captura", fontSize = 18.sp) }
        }
        if (msg.isNotBlank()) Text(msg, color = Color(0xFFFFB74D), modifier = Modifier.padding(top = 8.dp))

        Title("Slots")
        for (s in data.slots) {
            Box2 {
                Text("S${s.slot} • ${s.title}", fontWeight = FontWeight.Bold)
                KV("Completude (campos reais)", "${s.pct}%")
                KV("Última atualização", ago(s.updated))
            }
        }

        Title("Diagnóstico")
        Box2 {
            KV("Acessibilidade ativada", if (enabled) "sim" else "NÃO")
            KV("Serviço realmente conectado", if (connected) "sim" else "NÃO")
            KV("Pacote do jogo", if (Diag.lastOsmEventAt > 0) "$OSM_PACKAGE (evento ${ago(Diag.lastOsmEventAt)})" else "sem eventos do OSM")
            KV("Sessão atual", Control.activeSession(ctx) ?: "nenhuma")
            KV("Slot atual", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "não identificado")
            KV("Tipo da tela atual", Diag.currentType)
            KV("Frames válidos", Diag.valid.get().toString())
            KV("Frames descartados", Diag.discarded.get().toString())
            KV("Deduplicados", Diag.dedup.get().toString())
            KV("OCR realizado", Diag.ocr.get().toString())
            KV("Parser aplicado", Diag.parsed.get().toString())
            KV("Campos extraídos/alterados", Diag.extracted.get().toString())
            KV("Sem slot (preservados)", Diag.unassigned.get().toString())
            KV("Chamadas de IA (sessão)", Diag.aiCalls.get().toString())
            KV("Última atualização de dados", ago(Diag.lastUpdateAt))
            KV("Último erro", Diag.lastError ?: "nenhum")
        }
    }
}

@Composable
private fun SessionsTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    val rows by produceState(emptyList<SessionRow>(), tick) {
        value = withContext(Dispatchers.IO) {
            val dao = Repo(ctx).dao
            dao.sessions().map { s ->
                val counts = dao.typeCounts(s.id).joinToString(" • ") { "${it.type} ${it.c}" }
                SessionRow(s, counts, dao.unassignedCount(s.id))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Title("Sessões")
        OutlinedButton(onClick = {
            scope.launch(Dispatchers.IO) {
                msg = "Reprocessando…"
                try {
                    Processor.run(ctx, "manual")
                    msg = "Reprocessamento concluído."
                } catch (e: Exception) {
                    msg = "Erro: " + (e.message ?: "?")
                }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("Reprocessar quadros sem slot e fila de IA") }
        if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(top = 6.dp))
        if (rows.isEmpty()) Text("Nenhuma sessão ainda.", modifier = Modifier.padding(top = 12.dp))
        for (r in rows) {
            Box2 {
                Text(r.s.id, fontWeight = FontWeight.Bold)
                KV("Estado", r.s.state)
                KV("Início", fmtTime(r.s.startedAt))
                KV("Fim", r.s.endedAt?.let { fmtTime(it) } ?: "em andamento")
                KV("Sem slot", r.unassigned.toString())
                Text(r.counts.ifBlank { "sem quadros válidos" }, color = Color(0xFFAAB4C0), fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SlotsTab(onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Title("Slots")
        for (s in data.slots) {
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text("S${s.slot} • ${s.title}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    KV("Completude", "${s.pct}%")
                    KV("Atualizado", ago(s.updated))
                    Button(onClick = { onOpen(s.slot) }) { Text("Abrir") }
                }
            }
        }
    }
}

@Composable
private fun DirectorTab(onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Title("Diretor")
        Text("Tática e plano de mercado por slot. A IA só usa dados já lidos; o que for NI não é presumido.", color = Color(0xFFAAB4C0))
        for (s in data.slots) {
            Box2 {
                Text("S${s.slot} • ${s.title}", fontWeight = FontWeight.Bold)
                KV("Completude", "${s.pct}%")
                Button(onClick = { onOpen(s.slot) }) { Text("Abrir tática e mercado") }
            }
        }
    }
}

@Composable
private fun SlotScreen(slot: Int, startTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    val data by produceState(SlotData(), tick, slot) { value = withContext(Dispatchers.IO) { loadSlot(ctx, slot) } }
    var tab by remember { mutableIntStateOf(startTab) }
    val tabs = listOf("Resumo", "Pré-jogo", "Elenco", "Calendário", "Tática", "Diretor", "Aprendizado")
    val title = data.fields[K.TEAM]?.value ?: data.fields[K.HUB_TITLE]?.value ?: "(não lido)"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onBack) { Text("← Voltar") }
            Text("S$slot • $title", fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(top = 8.dp))
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
            when (tab) {
                0 -> SlotSummaryTab(data)
                1 -> SlotPregame(data)
                2 -> SlotSquad(data)
                3 -> SlotCalendar(data)
                4 -> SlotTactic(slot, data)
                5 -> SlotDirector(slot, data)
                else -> SlotLearning(data)
            }
        }
    }
}

private fun fv(d: SlotData, key: String): String {
    val f = d.fields[key] ?: return NI
    return if (FieldMerge.known(f.value)) f.value else NI
}

@Composable
private fun SlotSummaryTab(d: SlotData) {
    val c = d.completeness
    Box2 {
        KV("Time", fv(d, K.TEAM).let { if (it == NI) fv(d, K.HUB_TITLE) else it })
        KV("Competição", fv(d, K.COMPETITION))
        KV("Tipo", fv(d, K.COMP_TYPE))
        val done = fv(d, K.ROUND_DONE)
        val total = fv(d, K.ROUND_TOTAL)
        KV("Rodadas concluídas/total", if (done == NI) NI else "$done/$total")
        KV("Próxima rodada", fv(d, K.ROUND))
        KV("Posição na liga", fv(d, K.LEAGUE_POS))
        KV("Caixa", fv(d, K.CASH))
    }
    if (c != null) {
        Box2 {
            Text("Completude: ${c.percent}% (${c.known.size} de ${c.known.size + c.missing.size} campos)", fontWeight = FontWeight.Bold)
            Text("Conhecidos: " + c.known.joinToString(", ").ifBlank { "nenhum" }, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("Faltantes: " + c.missing.joinToString(", ").ifBlank { "nenhum" }, fontSize = 12.sp, color = Color(0xFFFFB74D))
        }
    }
    Box2 {
        Text("Última atualização por seção", fontWeight = FontWeight.Bold)
        for (t in listOf("PREGAME", "SQUAD", "CALENDAR", "MARKET", "REPORT")) {
            KV(t, ago(d.sections[t] ?: 0L))
        }
    }
}

@Composable
private fun SlotPregame(d: SlotData) {
    val at = fv(d, K.MATCH_AT).toLongOrNull()
    val rows = listOf(
        "Rodada" to fv(d, K.ROUND),
        "Data/hora do jogo" to (if (at != null) fmtTime(at) else NI),
        "Casa/fora" to fv(d, K.HOME),
        "Adversário" to fv(d, K.RIVAL_TEAM),
        "Humano/CPU" to fv(d, K.RIVAL_HUMAN),
        "Apelido do rival" to fv(d, K.RIVAL_NICK),
        "Minha força" to fv(d, K.MY_STRENGTH),
        "Força do rival" to fv(d, K.RIVAL_STRENGTH),
        "Valor do meu elenco" to fv(d, K.MY_VALUE),
        "Valor do elenco rival" to fv(d, K.RIVAL_VALUE),
        "Formação rival" to fv(d, K.RIVAL_FORMATION),
        "Plano de jogo rival" to fv(d, K.RIVAL_PLAN),
        "Marcação rival" to fv(d, K.RIVAL_MARKING),
        "Impedimento rival" to fv(d, K.RIVAL_OFFSIDE),
        "Desarme rival" to fv(d, K.RIVAL_TACKLE),
        "Treino secreto rival" to fv(d, K.RIVAL_SECRET),
        "Campo de treinamento rival" to fv(d, K.RIVAL_CAMP),
        "Bônus de login rival" to fv(d, K.RIVAL_LOGIN_BONUS),
        "Árbitro (termômetro)" to fv(d, K.REFEREE),
        "Estádio" to fv(d, K.STADIUM),
        "Bônus de estádio" to fv(d, K.STADIUM_BONUS)
    )
    Box2 { for ((k, v) in rows) KV(k, v) }
    Text("NI = ainda não lido. Relatório do adversário depende da IA (precisa de chave em Ajustes).", fontSize = 12.sp, color = Color(0xFFAAB4C0))
}

@Composable
private fun SlotSquad(d: SlotData) {
    val mine = d.players.filter { it.owner == "MY" }
    val counts = HashMap<String, Int>()
    for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
    Box2 {
        Text("Meu elenco: ${mine.size} jogadores", fontWeight = FontWeight.Bold)
        for (n in MarketPlanner.needs(counts)) KV(n.cat, "${n.have}/${n.target}")
        KV("Treinando", mine.count { it.training == true }.toString())
        KV("Força geral / GOL / DEF / MEI / ATA",
            listOf(K.MY_STRENGTH, K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK).joinToString(" / ") { fv(d, it) })
    }
    for (cat in listOf("ATA", "MEI", "DEF", "GOL")) {
        val list = mine.filter { it.cat == cat }.sortedByDescending { it.strength ?: 0 }
        if (list.isEmpty()) continue
        Title(cat)
        for (p in list) {
            val tag = if (p.training == true) "  [TREINANDO]" else ""
            Text("${p.name}  ${p.posCode ?: "?"}  ${p.age ?: "?"}a  força ${p.strength ?: NI}  ${p.valueText ?: NI}$tag", fontSize = 13.sp)
        }
    }
    val rivalCount = d.players.count { it.owner == "RIVAL" }
    if (rivalCount > 0) Text("Elenco rival lido: $rivalCount jogadores", modifier = Modifier.padding(top = 10.dp), color = Color(0xFFAAB4C0))
}

@Composable
private fun SlotCalendar(d: SlotData) {
    if (d.matches.isEmpty()) {
        Text("Calendário ainda não lido neste slot.")
        return
    }
    for (m in d.matches.sortedBy { it.round ?: 999 }) {
        val score = if (m.scoreMine != null && m.scoreOpp != null) "${m.scoreMine}x${m.scoreOpp} ${m.result ?: ""}" else "a jogar"
        val place = when (m.home) { true -> "casa"; false -> "fora"; null -> "?" }
        val nick = if (m.opponentNick != null) " (${m.opponentNick})" else ""
        Text("${m.label} • ${m.opponent ?: NI}$nick • $place • ${m.date ?: m.time ?: NI} • $score", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun SlotTactic(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Button(enabled = !busy, onClick = {
        busy = true
        msg = "Gerando tática…"
        scope.launch(Dispatchers.IO) {
            val r = Director.generateTactic(ctx, Repo(ctx), slot)
            msg = if (r.ok) "Tática gerada." else (r.error ?: "Falhou.")
            busy = false
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Gerar tática para o próximo jogo") }
    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(vertical = 6.dp), color = Color(0xFFFFB74D))
    val plan = d.tactic
    if (plan == null) {
        Text("Nenhuma tática gerada ainda.")
        return
    }
    val j = try { JSONObject(plan.json) } catch (e: Exception) { null }
    Text("Gerada ${ago(plan.at)} — coloque manualmente no jogo:", color = Color(0xFFAAB4C0), modifier = Modifier.padding(top = 6.dp))
    if (j != null) {
        Box2 {
            KV("Formação", j.optString("formation"))
            KV("Estilo de jogo", j.optString("playStyle"))
            KV("Pressão", j.optInt("pressure").toString())
            KV("Mentalidade/Estilo", j.optInt("mentality").toString())
            KV("Ritmo/Temporização", j.optInt("tempo").toString())
            KV("Marcação", j.optString("marking"))
            KV("Impedimento", j.optString("offside"))
            KV("Desarme", j.optString("tackle"))
            KV("Avançadas Ataque", j.optString("advAttack"))
            KV("Avançadas Meio", j.optString("advMid"))
            KV("Avançadas Defesa", j.optString("advDef"))
        }
        val ra = j.optJSONArray("rationale")
        if (ra != null) for (i in 0 until ra.length()) Text("• " + ra.optString(i), fontSize = 13.sp)
    }
}

@Composable
private fun SlotDirector(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Title("Plano local (sem IA)")
    Box2 { for (l in d.baseline) Text("• $l", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    Button(enabled = !busy, onClick = {
        busy = true
        msg = "Gerando plano de mercado…"
        scope.launch(Dispatchers.IO) {
            val r = Director.generateMarket(ctx, Repo(ctx), slot)
            msg = if (r.ok) "Plano gerado." else (r.error ?: "Falhou.")
            busy = false
        }
    }, modifier = Modifier.fillMaxWidth()) { Text("Gerar plano de mercado e treino (IA)") }
    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(vertical = 6.dp), color = Color(0xFFFFB74D))
    val plan = d.market ?: return
    val j = try { JSONObject(plan.json) } catch (e: Exception) { return }
    Text("Gerado ${ago(plan.at)}", color = Color(0xFFAAB4C0))
    fun list(key: String, label: String): List<String> {
        val a = j.optJSONArray(key) ?: return emptyList()
        val out = ArrayList<String>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val extra = if (o.has("trainer")) " (${o.optString("trainer")})" else ""
            out.add("$label ${o.optString("name")}$extra — ${o.optString("reason")}")
        }
        return out
    }
    Box2 {
        for (l in list("sell", "VENDER") + list("buy", "COMPRAR") + list("train", "TREINAR")) Text("• $l", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        val s = j.optString("summary")
        if (s.isNotBlank()) Text(s, fontSize = 12.sp, color = Color(0xFFAAB4C0), modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SlotLearning(d: SlotData) {
    if (d.learning.isEmpty()) {
        Text("Sem eventos de aprendizado ainda.")
        return
    }
    for (l in d.learning) {
        Text("${fmtTime(l.at)} • ${l.kind}: ${l.text}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
    }
}

@Composable
private fun SettingsTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    var gKey by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_KEY, "")) }
    var gModel by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)) }
    var cKey by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_KEY, "")) }
    var cBase by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE)) }
    var cModel by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_MODEL, Settings.DEFAULT_COMPAT_MODEL)) }
    var cap by remember { mutableStateOf(Settings.get(ctx, Settings.DAILY_CAP, "80")) }
    var saved by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Title("Ajustes")
        Box2 {
            Text("Serviço de leitura", fontWeight = FontWeight.Bold)
            KV("Acessibilidade ativada", if (serviceEnabled(ctx)) "sim" else "NÃO")
            KV("Realmente conectado", if (Diag.serviceConnected && tick >= 0) "sim" else "NÃO")
            Button(onClick = { openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir configurações de acessibilidade")
            }
            OutlinedButton(onClick = { openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }, modifier = Modifier.fillMaxWidth()) {
                Text("Info do app (permitir configurações restritas)")
            }
            OutlinedButton(onClick = { openSettings(ctx, android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Bateria: não otimizar este app")
            }
        }
        Box2 {
            Text("IA gratuita (usada só como fallback e para tática/mercado)", fontWeight = FontWeight.Bold)
            OutlinedTextField(gKey, { gKey = it }, label = { Text("Chave Google AI Studio (Gemini)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gModel, { gModel = it }, label = { Text("Modelo Gemini") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cKey, { cKey = it }, label = { Text("Chave alternativa (Groq/xAI, formato OpenAI)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cBase, { cBase = it }, label = { Text("URL base alternativa") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cModel, { cModel = it }, label = { Text("Modelo alternativo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cap, { cap = it }, label = { Text("Teto diário de chamadas") }, modifier = Modifier.fillMaxWidth())
            KV("Chamadas hoje", AiClient.usedToday(ctx).toString())
            Button(onClick = {
                Settings.put(ctx, Settings.GEMINI_KEY, gKey)
                Settings.put(ctx, Settings.GEMINI_MODEL, gModel.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
                Settings.put(ctx, Settings.COMPAT_KEY, cKey)
                Settings.put(ctx, Settings.COMPAT_BASE, cBase.ifBlank { Settings.DEFAULT_COMPAT_BASE })
                Settings.put(ctx, Settings.COMPAT_MODEL, cModel.ifBlank { Settings.DEFAULT_COMPAT_MODEL })
                Settings.put(ctx, Settings.DAILY_CAP, cap.ifBlank { "80" })
                saved = "Salvo."
            }, modifier = Modifier.fillMaxWidth()) { Text("Salvar") }
            if (saved.isNotBlank()) Text(saved, color = Color(0xFF81C784))
        }
    }
}
