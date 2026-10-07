package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private object C {
    val BG = Color(0xFF0B1220)
    val SURFACE = Color(0xFF141E33)
    val SURFACE2 = Color(0xFF1F2E4D)
    val PRIMARY = Color(0xFF3D8BFF)
    val GOLD = Color(0xFFFFC83D)
    val OK = Color(0xFF3DDC84)
    val BAD = Color(0xFFFF5C5C)
    val WARN = Color(0xFFFFB74D)
    val MUTED = Color(0xFF9FB0C8)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppScope.scope.launch { Repo(applicationContext).purgeLegacy() }
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = C.PRIMARY, secondary = C.GOLD, background = C.BG, surface = C.SURFACE,
                    onSurface = Color.White, onBackground = Color.White, surfaceVariant = C.SURFACE2
                )
            ) {
                Surface(Modifier.fillMaxSize(), color = C.BG) { App() }
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

private fun batteryUnrestricted(ctx: Context): Boolean {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(ctx.packageName)
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
    try {
        ctx.startActivity(i)
    } catch (e: Exception) {
        Diag.lastError = "Não consegui abrir: $action"
    }
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

private fun typeLabel(name: String): String = when (name) {
    "HUB" -> "Central dos slots"
    "PREGAME" -> "Pré-jogo"
    "SQUAD" -> "Elenco"
    "CALENDAR" -> "Calendário"
    "MARKET" -> "Mercado"
    "TRAINING" -> "Treinamento"
    "TACTIC" -> "Tática"
    "REPORT" -> "Relatório"
    "OTHER_OSM" -> "Outra tela do OSM"
    "NON_OSM" -> "Fora do OSM / anúncio"
    "NOISE" -> "Transição"
    else -> name
}

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

// ------------------------------------------------------------------ componentes visuais

@Composable
private fun Title(text: String) =
    Text(text, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
private fun KV(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = C.MUTED, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Panel(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = C.SURFACE)
    ) { Column(Modifier.padding(14.dp)) { content() } }
}

@Composable
private fun Pill(text: String, ok: Boolean? = null) {
    val bg = when (ok) {
        true -> Color(0xFF14532D)
        false -> Color(0xFF6B1D1D)
        null -> C.SURFACE2
    }
    Box(Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Crest(slot: Int, size: Dp) {
    val ctx = LocalContext.current
    val f = File(ctx.filesDir, "crests/s$slot.png")
    val stamp = if (f.exists()) f.lastModified() else 0L
    val bmp = remember(stamp) { if (stamp > 0L) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null }
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(size).clip(RoundedCornerShape(14.dp)))
    } else {
        Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
            Text("S$slot", fontWeight = FontWeight.Bold, color = C.MUTED, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Bar(pct: Int) {
    LinearProgressIndicator(
        progress = { pct.coerceIn(0, 100) / 100f },
        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
        color = C.PRIMARY,
        trackColor = C.SURFACE2
    )
}

// ------------------------------------------------------------------ dados de tela

data class SlotSummary(
    val slot: Int,
    val title: String,
    val known: Boolean,
    val competition: String?,
    val compType: String?,
    val roundDone: String?,
    val roundTotal: String?,
    val rival: String?,
    val human: String?,
    val pct: Int,
    val updated: Long
)

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

private fun known(f: Map<String, StoredField>, key: String): String? =
    f[key]?.value?.takeIf { FieldMerge.known(it) }

private suspend fun loadToday(ctx: Context): TodayData {
    val repo = Repo(ctx)
    val list = ArrayList<SlotSummary>()
    for (slot in 1..4) {
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).count { it.owner == "MY" }
        val matches = repo.dao.matchesOf(slot).size
        val market = repo.dao.snapshots("TRANSFER", slot).isNotEmpty()
        val c = Completeness.compute(f, players, matches, market)
        val name = known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
        list.add(
            SlotSummary(
                slot = slot, title = name ?: "Slot $slot ainda não lido", known = name != null,
                competition = known(f, K.COMPETITION), compType = known(f, K.COMP_TYPE),
                roundDone = known(f, K.ROUND_DONE), roundTotal = known(f, K.ROUND_TOTAL),
                rival = known(f, K.RIVAL_TEAM), human = known(f, K.RIVAL_HUMAN),
                pct = c.percent, updated = f.values.maxOfOrNull { it.updatedAt } ?: 0L
            )
        )
    }
    return TodayData(Control.activeSession(ctx) != null, list)
}

// ------------------------------------------------------------------ app

@Composable
private fun App() {
    var tab by remember { mutableIntStateOf(0) }
    var openSlot by remember { mutableIntStateOf(0) }
    val items = listOf("🏠" to "Hoje", "🕘" to "Sessões", "⚽" to "Slots", "🧠" to "Diretor", "⚙️" to "Ajustes")
    Scaffold(
        containerColor = C.BG,
        bottomBar = {
            NavigationBar(containerColor = C.SURFACE) {
                items.forEachIndexed { i, (icon, name) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; openSlot = 0 },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(name, fontSize = 11.sp) }
                    )
                }
            }
        }
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> TodayTab(onGoSettings = { tab = 4 }, onOpenSlot = { tab = 2; openSlot = it })
                1 -> SessionsTab()
                2 -> if (openSlot > 0) SlotScreen(openSlot, 0) { openSlot = 0 } else SlotsTab { openSlot = it }
                3 -> if (openSlot > 0) SlotScreen(openSlot, 5) { openSlot = 0 } else DirectorTab { openSlot = it }
                else -> SettingsTab()
            }
        }
    }
}

@Composable
private fun Step(n: Int, title: String, ok: Boolean?, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Box(
            Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(if (ok == true) Color(0xFF14532D) else C.SURFACE2),
            contentAlignment = Alignment.Center
        ) { Text(if (ok == true) "✔" else n.toString(), fontWeight = FontWeight.Bold, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (ok != true) content()
        }
    }
}

@Composable
private fun SetupCard(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean, hasKey: Boolean, onGoSettings: () -> Unit) {
    Panel {
        Text("Configuração inicial", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text("Faltam poucos passos para o app ler o jogo sozinho.", color = C.MUTED, fontSize = 13.sp)
        Step(1, "Ativar a leitura automática", enabled && connected) {
            Text(
                "Toque no botão, procure “OSM AI Coach — leitura automática” (pode estar em “Apps instalados” ou “Serviços baixados”) e ligue a chave.",
                color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
            if (enabled && !connected) Text("Ativado, aguardando o Android conectar o serviço…", color = C.WARN, fontSize = 12.sp)
            Button(onClick = { openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Abrir acessibilidade") }
        }
        Step(2, "Se o Android bloquear (chave cinza ou “configuração restrita”)", null) {
            Text(
                "Abra as informações do app → menu ⋮ no canto superior → “Permitir configurações restritas”. Depois volte ao passo 1.",
                color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp)
            )
            OutlinedButton(onClick = { openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) {
                Text("Abrir informações do app")
            }
        }
        Step(3, "Bateria sem restrição (evita o Android desligar a leitura)", batteryOk) {
            Text("Na lista, procure OSM AI Coach e escolha “Não otimizar”.", color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
            OutlinedButton(onClick = { openSettings(ctx, android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) {
                Text("Abrir ajustes de bateria")
            }
        }
        Step(4, "Chave de IA do Google AI Studio (pode fazer depois)", hasKey) {
            OutlinedButton(onClick = onGoSettings, modifier = Modifier.padding(top = 4.dp)) { Text("Ir para Ajustes") }
        }
    }
}

@Composable
private fun SlotCard(s: SlotSummary, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = C.SURFACE)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            Crest(s.slot, 68.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("S${s.slot}", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(s.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (s.known) Color.White else C.MUTED)
                }
                val sub = listOfNotNull(
                    s.competition,
                    if (s.roundDone != null && s.roundTotal != null) "Rodada ${s.roundDone}/${s.roundTotal}" else null
                ).joinToString(" • ")
                if (sub.isNotBlank()) Text(sub, color = C.MUTED, fontSize = 12.sp)
                Row(Modifier.padding(top = 4.dp)) {
                    if (s.compType != null) Pill(s.compType)
                    if (s.rival != null) Pill("vs ${s.rival}")
                    if (s.human != null) Pill(if (s.human == "Sim") "Humano" else "CPU", s.human == "Sim")
                }
                Spacer(Modifier.height(6.dp))
                Bar(s.pct)
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("${s.pct}% dos campos lidos", fontSize = 11.sp, color = C.MUTED)
                    Text(ago(s.updated), fontSize = 11.sp, color = C.MUTED)
                }
            }
        }
    }
}

@Composable
private fun TodayTab(onGoSettings: () -> Unit, onOpenSlot: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(1000L)
    val scope = rememberCoroutineScope()
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    var msg by remember { mutableStateOf("") }
    var showDiag by remember { mutableStateOf(false) }
    val enabled = serviceEnabled(ctx)
    val connected = Diag.serviceConnected
    val ready = enabled && connected
    val batteryOk = batteryUnrestricted(ctx)
    val hasKey = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("OSM AI Coach", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
            Row {
                Pill(if (ready) "Leitura ativa" else "Leitura desligada", ready)
            }
        }
        Text("Jogue normalmente: o app lê as telas, guarda por slot e a IA monta a tática.", color = C.MUTED, fontSize = 12.sp)

        if (!ready || !batteryOk) SetupCard(ctx, enabled, connected, batteryOk, hasKey, onGoSettings)

        Spacer(Modifier.height(8.dp))
        if (data.active) {
            Button(
                onClick = { scope.launch { Control.end(ctx); msg = "Captura encerrada. Processando em segundo plano…" } },
                modifier = Modifier.fillMaxWidth().height(84.dp),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = C.BAD)
            ) { Text("ENCERRAR CAPTURA E PROCESSAR", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { if (!openOsm(ctx)) msg = "OSM não encontrado." }, modifier = Modifier.fillMaxWidth()) {
                Text("Voltar ao OSM (a mesma sessão continua)")
            }
            Panel {
                Text("● Capturando", color = C.OK, fontWeight = FontWeight.Bold)
                KV("Slot atual", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "não identificado")
                KV("Tela atual", typeLabel(Diag.currentType))
                KV("Telas lidas", Diag.valid.get().toString())
                KV("Campos novos", Diag.extracted.get().toString())
            }
        } else {
            Button(
                onClick = {
                    scope.launch {
                        Control.start(ctx)
                        if (!openOsm(ctx)) msg = "OSM não encontrado neste aparelho."
                    }
                },
                enabled = ready,
                modifier = Modifier.fillMaxWidth().height(72.dp),
                shape = RoundedCornerShape(20.dp)
            ) { Text("Abrir OSM e iniciar captura", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (!ready) Text("Conclua o passo 1 da configuração para liberar o botão.", color = C.WARN, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (msg.isNotBlank()) Text(msg, color = C.WARN, modifier = Modifier.padding(top = 8.dp))

        Title("Seus slots")
        for (s in data.slots) SlotCard(s) { onOpenSlot(s.slot) }

        Title("Últimos eventos")
        Panel {
            val ev = Diag.recentEvents()
            if (ev.isEmpty()) Text("Nenhum evento ainda. Inicie a captura e navegue no OSM.", color = C.MUTED, fontSize = 12.sp)
            for (e in ev) Text(e, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
        }

        OutlinedButton(onClick = { showDiag = !showDiag }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(if (showDiag) "Ocultar diagnóstico" else "Mostrar diagnóstico completo")
        }
        if (showDiag) {
            Panel {
                KV("Acessibilidade ativada", if (enabled) "sim" else "NÃO")
                KV("Serviço realmente conectado", if (connected) "sim" else "NÃO")
                KV("Pacote do jogo", if (Diag.lastOsmEventAt > 0) "$OSM_PACKAGE (evento ${ago(Diag.lastOsmEventAt)})" else "sem eventos do OSM")
                KV("Sessão atual", Control.activeSession(ctx) ?: "nenhuma")
                KV("Slot atual", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "não identificado")
                KV("Tipo da tela atual", typeLabel(Diag.currentType))
                KV("Último screenshot", ago(Diag.lastShotAt))
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
        Spacer(Modifier.height(16.dp))
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
                val counts = dao.typeCounts(s.id).joinToString(" • ") { "${typeLabel(it.type)} ${it.c}" }
                SessionRow(s, counts, dao.unassignedCount(s.id))
            }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Sessões", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        OutlinedButton(
            onClick = {
                scope.launch(Dispatchers.IO) {
                    msg = "Reprocessando…"
                    try {
                        Processor.run(ctx, "manual")
                        msg = "Reprocessamento concluído."
                    } catch (e: Exception) {
                        msg = "Erro: " + (e.message ?: "?")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Reprocessar quadros sem slot e fila de IA") }
        if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(top = 6.dp), color = C.WARN)
        if (rows.isEmpty()) Text("Nenhuma sessão ainda.", color = C.MUTED, modifier = Modifier.padding(top = 12.dp))
        for (r in rows) {
            Panel {
                Text(r.s.id, fontWeight = FontWeight.Bold)
                KV("Estado", r.s.state)
                KV("Início", fmtTime(r.s.startedAt))
                KV("Fim", r.s.endedAt?.let { fmtTime(it) } ?: "em andamento")
                KV("Sem slot", r.unassigned.toString())
                Text(r.counts.ifBlank { "sem quadros válidos" }, color = C.MUTED, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun SlotsTab(onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Slots", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        for (s in data.slots) SlotCard(s) { onOpen(s.slot) }
    }
}

@Composable
private fun DirectorTab(onOpen: (Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    val data by produceState(TodayData(), tick) { value = withContext(Dispatchers.IO) { loadToday(ctx) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Diretor", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Escolha o slot para gerar a tática e o plano de mercado. A IA só usa dados já lidos; o que for NI não é presumido.", color = C.MUTED, fontSize = 12.sp)
        for (s in data.slots) SlotCard(s) { onOpen(s.slot) }
    }
}

@Composable
private fun SlotScreen(slot: Int, startTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    val data by produceState(SlotData(), tick, slot) { value = withContext(Dispatchers.IO) { loadSlot(ctx, slot) } }
    var tab by remember { mutableIntStateOf(startTab) }
    val tabs = listOf("Resumo", "Pré-jogo", "Elenco", "Calendário", "Tática", "Diretor", "Aprendizado")
    val title = known(data.fields, K.TEAM) ?: known(data.fields, K.HUB_TITLE) ?: "Slot $slot ainda não lido"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(10.dp))
            Crest(slot, 48.dp)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("S$slot • $title", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(known(data.fields, K.COMPETITION) ?: "competição não lida", color = C.MUTED, fontSize = 12.sp)
            }
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp, containerColor = C.BG) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t, fontSize = 13.sp) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
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

private fun fv(d: SlotData, key: String): String = known(d.fields, key) ?: NI

@Composable
private fun SlotSummaryTab(d: SlotData) {
    val c = d.completeness
    Panel {
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
        Panel {
            Text("Completude: ${c.percent}% (${c.known.size} de ${c.known.size + c.missing.size} campos)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Bar(c.percent)
            Spacer(Modifier.height(8.dp))
            Text("Conhecidos: " + c.known.joinToString(", ").ifBlank { "nenhum" }, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text("Faltantes: " + c.missing.joinToString(", ").ifBlank { "nenhum" }, fontSize = 12.sp, color = C.WARN)
        }
    }
    Panel {
        Text("Última atualização por seção", fontWeight = FontWeight.Bold)
        for (t in listOf("PREGAME", "SQUAD", "CALENDAR", "MARKET", "REPORT")) {
            KV(typeLabel(t), ago(d.sections[t] ?: 0L))
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
    Panel { for ((k, v) in rows) KV(k, v) }
    Text("NI = ainda não lido. O relatório do adversário depende da IA (precisa de chave em Ajustes).", fontSize = 12.sp, color = C.MUTED)
}

@Composable
private fun SlotSquad(d: SlotData) {
    val mine = d.players.filter { it.owner == "MY" }
    val counts = HashMap<String, Int>()
    for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
    Panel {
        Text("Meu elenco: ${mine.size} jogadores", fontWeight = FontWeight.Bold)
        for (n in MarketPlanner.needs(counts)) KV(n.cat, "${n.have}/${n.target}")
        KV("Treinando", mine.count { it.training == true }.toString())
        KV(
            "Força geral / GOL / DEF / MEI / ATA",
            listOf(K.MY_STRENGTH, K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK).joinToString(" / ") { fv(d, it) }
        )
    }
    for (cat in listOf("ATA", "MEI", "DEF", "GOL")) {
        val list = mine.filter { it.cat == cat }.sortedByDescending { it.strength ?: 0 }
        if (list.isEmpty()) continue
        Title(cat)
        Panel {
            for (p in list) {
                val tag = if (p.training == true) "  🟠 treinando" else ""
                Text(
                    "${p.name}  •  ${p.posCode ?: "?"}  •  ${p.age ?: "?"}a  •  força ${p.strength ?: NI}  •  ${p.valueText ?: NI}$tag",
                    fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
    val rivalCount = d.players.count { it.owner == "RIVAL" }
    if (rivalCount > 0) Text("Elenco rival lido: $rivalCount jogadores", modifier = Modifier.padding(top = 10.dp), color = C.MUTED)
}

@Composable
private fun SlotCalendar(d: SlotData) {
    if (d.matches.isEmpty()) {
        Text("Calendário ainda não lido neste slot.", color = C.MUTED)
        return
    }
    Panel {
        for (m in d.matches.sortedBy { it.round ?: 999 }) {
            val score = if (m.scoreMine != null && m.scoreOpp != null) "${m.scoreMine}x${m.scoreOpp} ${m.result ?: ""}" else "a jogar"
            val place = when (m.home) {
                true -> "casa"
                false -> "fora"
                null -> "?"
            }
            val nick = if (m.opponentNick != null) " (${m.opponentNick})" else ""
            Text(
                "${m.label} • ${m.opponent ?: NI}$nick • $place • ${m.date ?: m.time ?: NI} • $score",
                fontSize = 13.sp, modifier = Modifier.padding(vertical = 3.dp)
            )
        }
    }
}

@Composable
private fun SlotTactic(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            msg = "Gerando tática…"
            scope.launch(Dispatchers.IO) {
                val r = Director.generateTactic(ctx, Repo(ctx), slot)
                msg = if (r.ok) "Tática gerada." else (r.error ?: "Falhou.")
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Gerar tática para o próximo jogo", fontWeight = FontWeight.Bold) }
    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(vertical = 6.dp), color = C.WARN)
    val plan = d.tactic
    if (plan == null) {
        Text("Nenhuma tática gerada ainda.", color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        return
    }
    val j = try {
        JSONObject(plan.json)
    } catch (e: Exception) {
        null
    }
    Text("Gerada ${ago(plan.at)} — coloque manualmente no jogo:", color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    if (j != null) {
        Panel {
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
        if (ra != null) for (i in 0 until ra.length()) Text("• " + ra.optString(i), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun SlotDirector(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Title("Plano local (sem IA)")
    Panel { for (l in d.baseline) Text("• $l", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    Button(
        enabled = !busy,
        onClick = {
            busy = true
            msg = "Gerando plano de mercado…"
            scope.launch(Dispatchers.IO) {
                val r = Director.generateMarket(ctx, Repo(ctx), slot)
                msg = if (r.ok) "Plano gerado." else (r.error ?: "Falhou.")
                busy = false
            }
        },
        modifier = Modifier.fillMaxWidth().height(56.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text("Gerar plano de mercado e treino (IA)", fontWeight = FontWeight.Bold) }
    if (msg.isNotBlank()) Text(msg, modifier = Modifier.padding(vertical = 6.dp), color = C.WARN)
    val plan = d.market ?: return
    val j = try {
        JSONObject(plan.json)
    } catch (e: Exception) {
        return
    }
    Text("Gerado ${ago(plan.at)}", color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
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
    Panel {
        for (l in list("sell", "VENDER") + list("buy", "COMPRAR") + list("train", "TREINAR")) {
            Text("• $l", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
        val s = j.optString("summary")
        if (s.isNotBlank()) Text(s, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun SlotLearning(d: SlotData) {
    if (d.learning.isEmpty()) {
        Text("Sem eventos de aprendizado ainda.", color = C.MUTED)
        return
    }
    Panel {
        for (l in d.learning) {
            Text("${fmtTime(l.at)} • ${l.kind}: ${l.text}", fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Ajustes", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Panel {
            Text("Serviço de leitura", fontWeight = FontWeight.Bold)
            KV("Acessibilidade ativada", if (serviceEnabled(ctx)) "sim" else "NÃO")
            KV("Realmente conectado", if (Diag.serviceConnected && tick >= 0) "sim" else "NÃO")
            KV("Bateria sem restrição", if (batteryUnrestricted(ctx)) "sim" else "NÃO")
            Spacer(Modifier.height(6.dp))
            Button(onClick = { openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir configurações de acessibilidade")
            }
            OutlinedButton(
                onClick = { openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Info do app (permitir configurações restritas)") }
            OutlinedButton(
                onClick = { openSettings(ctx, android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Bateria: não otimizar este app") }
        }
        Panel {
            Text("IA gratuita (só como fallback e para tática/mercado)", fontWeight = FontWeight.Bold)
            OutlinedTextField(gKey, { gKey = it }, label = { Text("Chave Google AI Studio (Gemini)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gModel, { gModel = it }, label = { Text("Modelo Gemini") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cKey, { cKey = it }, label = { Text("Chave alternativa (Groq/xAI, formato OpenAI)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cBase, { cBase = it }, label = { Text("URL base alternativa") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cModel, { cModel = it }, label = { Text("Modelo alternativo") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cap, { cap = it }, label = { Text("Teto diário de chamadas") }, modifier = Modifier.fillMaxWidth())
            KV("Chamadas hoje", AiClient.usedToday(ctx).toString())
            Button(
                onClick = {
                    Settings.put(ctx, Settings.GEMINI_KEY, gKey)
                    Settings.put(ctx, Settings.GEMINI_MODEL, gModel.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
                    Settings.put(ctx, Settings.COMPAT_KEY, cKey)
                    Settings.put(ctx, Settings.COMPAT_BASE, cBase.ifBlank { Settings.DEFAULT_COMPAT_BASE })
                    Settings.put(ctx, Settings.COMPAT_MODEL, cModel.ifBlank { Settings.DEFAULT_COMPAT_MODEL })
                    Settings.put(ctx, Settings.DAILY_CAP, cap.ifBlank { "80" })
                    saved = "Salvo."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salvar") }
            if (saved.isNotBlank()) Text(saved, color = C.OK)
        }
    }
}
