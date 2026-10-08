package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlin.math.sqrt
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
    val WIN = Color(0xFF2EA043)
    val DRAW = Color(0xFFF2C94C)
    val LOSS = Color(0xFFE5484D)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.migrate(applicationContext)
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
    "STADIUM" -> "Meu estádio"
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

object UiBus {
    var version by mutableIntStateOf(0)
}

data class EditRow(val label: String, val key: String?, val display: String? = null)

@Composable
private fun ManualDialog(slot: Int, spec: ManualFields.Spec, current: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(if (current == NI) "" else current) }
    var error by remember { mutableStateOf<String?>(null) }
    val opts = spec.options
    val buttonsOnly = opts != null && opts.size <= 6
    fun save(raw: String) {
        val n = ManualFields.normalize(spec.key, raw)
        if (n == null) {
            error = "Valor inválido. Exemplo: ${spec.example}"
            return
        }
        AppScope.scope.launch(Dispatchers.IO) {
            Repo(ctx).setManual(slot, spec.key, n)
            UiBus.version++
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text(spec.label, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                if (spec.hint.isNotBlank()) Text("Onde ver no jogo: ${spec.hint}", fontSize = 12.sp, color = C.MUTED)
                if (buttonsOnly && opts != null) {
                    for (o in opts) {
                        OutlinedButton(onClick = { save(o) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(o) }
                    }
                } else {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it; error = null }, singleLine = true,
                        label = { Text("Valor (ex.: ${spec.example})") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                    if (opts != null) Text("Formações: " + opts.joinToString(", "), fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                }
                val e = error
                if (e != null) Text(e, color = C.BAD, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                Text("O que você digita nunca é sobrescrito pela leitura automática.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { if (!buttonsOnly) TextButton(onClick = { save(text) }) { Text("Salvar") } },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    AppScope.scope.launch(Dispatchers.IO) {
                        Repo(ctx).clearField(slot, spec.key)
                        UiBus.version++
                    }
                    onDone()
                }) { Text("Limpar") }
                TextButton(onClick = onDone) { Text("Cancelar") }
            }
        }
    )
}

@Composable
private fun EditSection(slot: Int, d: SlotData, title: String, rows: List<EditRow>) {
    var editing by remember { mutableStateOf<EditRow?>(null) }
    Panel {
        Text(title, color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        for (r in rows) {
            val v = r.display ?: (if (r.key != null) fv(d, r.key) else NI)
            val manual = r.key != null && (d.fields[r.key]?.conf ?: 0.0) >= 1.5
            Row(
                Modifier.fillMaxWidth().clickable(enabled = r.key != null) { editing = r }.padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(r.label, color = C.MUTED, fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(v + (if (manual) " ✎" else ""), fontWeight = FontWeight.SemiBold, color = if (v == NI) C.WARN else Color.White)
                if (v == NI && r.key != null) Text("  ＋", color = C.PRIMARY, fontWeight = FontWeight.Bold)
            }
        }
        Text("Toque em um valor para corrigir ou preencher à mão.", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    val e = editing
    if (e != null && e.key != null) ManualDialog(slot, ManualFields.spec(e.key, e.label), fv(d, e.key)) { editing = null }
}

@Composable
private fun MatchDialog(slot: Int, round: Int, existing: MatchEntity?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var opp by remember { mutableStateOf(existing?.opponent ?: "") }
    var a by remember { mutableStateOf(existing?.scoreMine?.toString() ?: "") }
    var b by remember { mutableStateOf(existing?.scoreOpp?.toString() ?: "") }
    var home by remember { mutableStateOf(existing?.home) }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text("Rodada $round", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                OutlinedTextField(value = opp, onValueChange = { opp = it }, singleLine = true, label = { Text("Adversário") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols rival") }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.padding(top = 10.dp)) {
                    FilterPill("🏠 Casa", home == true) { home = true }
                    FilterPill("✈ Fora", home == false) { home = false }
                }
                Text("Deixe os gols vazios se o jogo ainda não foi disputado. O resultado entra no aprendizado da IA.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ma = a.toIntOrNull()
                val mb = b.toIntOrNull()
                AppScope.scope.launch(Dispatchers.IO) {
                    Repo(ctx).setManualMatch(slot, round, opp, ma, mb, home)
                    UiBus.version++
                }
                onDone()
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

@Composable
private fun ResultDialog(slot: Int, kind: String, title: String, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text("Resultado do jogo", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(title, fontSize = 12.sp, color = C.MUTED)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                    OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols rival") }, modifier = Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ma = a.toIntOrNull()
                val mb = b.toIntOrNull()
                if (ma != null && mb != null) {
                    AppScope.scope.launch(Dispatchers.IO) {
                        Director.setLogResult(Repo(ctx), slot, kind, ma, mb)
                        UiBus.version++
                    }
                    onDone()
                }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

@Composable
private fun <T> rememberLoaded(initial: T, tick: Int, key: Any?, loader: suspend () -> T): T {
    var state by remember { mutableStateOf(initial) }
    LaunchedEffect(tick, key) {
        state = withContext(Dispatchers.IO) { loader() }
    }
    return state
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(18.dp)),
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
    val updated: Long,
    val nextAt: Long? = null,
    val tacticReady: Boolean = false,
    val missing: Int = 0
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
    val baseline: List<String> = emptyList(),
    val marketPlan: MarketEngine.Plan? = null,
    val marketNote: String? = null,
    val logs: List<PlanEntity> = emptyList(),
    val reports: List<PlanEntity> = emptyList(),
    val fitness: Map<String, Pair<Int, Int>> = emptyMap(),
    val rivalProfile: PlanEntity? = null
)

data class SessionRow(val s: SessionEntity, val counts: String, val unassigned: Int)

private suspend fun loadSlot(ctx: Context, slot: Int): SlotData {
    val repo = Repo(ctx)
    val dao = repo.dao
    val f = repo.fieldMap(slot)
    val players = repo.playersOf(slot)
    val matches = dao.matchesOf(slot)
    val market = dao.snapshots("TRANSFER", slot).isNotEmpty()
    val comp = Completeness.compute(
        f, players.count { it.owner == "MY" }, matches.count { it.round != null }, market,
        f[K.ROUND_TOTAL]?.value?.toIntOrNull()
    )
    Director.resolveTacticLogs(repo, slot)
    return SlotData(
        fields = f, players = players, matches = matches,
        tactic = dao.plan(slot, "tactic"), market = dao.plan(slot, "market"),
        learning = dao.learningOf(slot),
        sections = dao.lastBySection(slot).associate { it.type to it.c },
        completeness = comp,
        marketPlan = Director.marketPlan(repo, slot),
        marketNote = dao.plan(slot, "market")?.json?.let { js ->
            try { JSONObject(js).optString("aiNote").ifBlank { null } } catch (e: Exception) { null }
        },
        logs = dao.tacticLogs(slot),
        reports = dao.matchReports(slot),
        fitness = repo.fitness(slot),
        rivalProfile = repo.rivalProfile(slot)
    )
}

private fun known(f: Map<String, StoredField>, key: String): String? =
    f[key]?.value?.takeIf { FieldMerge.known(it) }

private suspend fun tacticFor(repo: Repo, slot: Int, round: Int?): Boolean {
    val p = repo.dao.plan(slot, "tactic") ?: return false
    return try {
        round != null && JSONObject(p.json).optInt("forRound", -1) == round
    } catch (e: Exception) {
        false
    }
}

private fun countdown(ms: Long): String {
    val d = ms - System.currentTimeMillis()
    if (d <= 0L) return "horário passou — releia o pré-jogo"
    val m = d / 60000L
    return if (m >= 60) "em ${m / 60}h ${m % 60}min" else "em $m min"
}

private suspend fun loadToday(ctx: Context): TodayData {
    val repo = Repo(ctx)
    val list = ArrayList<SlotSummary>()
    for (slot in 1..4) {
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).count { it.owner == "MY" }
        val matches = repo.dao.matchesOf(slot).count { it.round != null }
        val market = repo.dao.snapshots("TRANSFER", slot).isNotEmpty()
        val c = Completeness.compute(f, players, matches, market, f[K.ROUND_TOTAL]?.value?.toIntOrNull())
        val name = known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
        list.add(
            SlotSummary(
                slot = slot, title = name ?: "Slot $slot ainda não lido", known = name != null,
                competition = known(f, K.COMPETITION), compType = known(f, K.COMP_TYPE),
                roundDone = known(f, K.ROUND_DONE), roundTotal = known(f, K.ROUND_TOTAL),
                rival = known(f, K.RIVAL_TEAM), human = known(f, K.RIVAL_HUMAN),
                pct = c.percent, updated = f.values.maxOfOrNull { it.updatedAt } ?: 0L,
                missing = c.missing.size,
                nextAt = known(f, K.MATCH_AT)?.toLongOrNull(),
                tacticReady = tacticFor(repo, slot, known(f, K.ROUND)?.toIntOrNull())
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
    var openTab by remember { mutableIntStateOf(0) }
    val items = listOf("🏠" to "Hoje", "🕘" to "Sessões", "⚽" to "Slots", "🧠" to "Diretor", "⚙️" to "Ajustes")
    // Botão Voltar do Android: fecha o slot aberto, depois volta para Hoje (em vez de fechar o app).
    BackHandler(enabled = openSlot > 0) { openSlot = 0 }
    BackHandler(enabled = openSlot == 0 && tab != 0) { tab = 0 }
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
                0 -> TodayTab(onGoSettings = { tab = 4 }, onOpenSlot = { slot, t -> tab = 2; openSlot = slot; openTab = t })
                1 -> SessionsTab()
                2 -> if (openSlot > 0) {
                    key(openSlot, openTab) { SlotScreen(openSlot, openTab) { openSlot = 0 } }
                } else SlotsTab { slot, t -> openSlot = slot; openTab = t }
                3 -> if (openSlot > 0) {
                    key(openSlot, openTab) { SlotScreen(openSlot, openTab) { openSlot = 0 } }
                } else DirectorTab { slot, t -> openSlot = slot; openTab = t }
                else -> SettingsTab()
            }
        }
    }
}

@Composable
private fun PendingRow(text: String, button: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.height(32.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) { Text(button, fontSize = 11.sp) }
    }
}

/** Faixa compacta: só mostra o que ainda falta configurar e fica recolhida quando a leitura já está ativa. */
@Composable
private fun SetupBanner(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean, hasKey: Boolean, onGoSettings: () -> Unit) {
    val ready = enabled && connected
    var open by remember { mutableStateOf(!ready) }
    val pending = (if (ready) 0 else 1) + (if (batteryOk) 0 else 1) + (if (hasKey) 0 else 1)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { open = !open },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = C.SURFACE)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("⚙ Configuração: $pending pendente(s)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = C.WARN)
                Text(if (open) "▲" else "▼", fontSize = 11.sp, color = C.MUTED)
            }
            if (open) {
                if (!ready) {
                    PendingRow("1. Ativar a leitura automática (Acessibilidade → OSM AI Coach)", "Abrir") {
                        openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    }
                    if (!enabled) {
                        PendingRow("Chave cinza? Info do app → ⋮ → Permitir configurações restritas", "Info do app") {
                            openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
                        }
                    } else {
                        Text("Ativado; aguardando o Android conectar o serviço…", fontSize = 11.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                if (!batteryOk) {
                    PendingRow("Bateria sem restrição (evita o Android desligar a leitura)", "Bateria") {
                        openSettings(ctx, android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    }
                }
                if (!hasKey) PendingRow("Chave de IA (Google AI Studio) para tática e mercado", "Ajustes", onGoSettings)
            }
        }
    }
}

@Composable
private fun ProcessCard() {
    val tick = rememberTick(500L)
    val now = System.currentTimeMillis()
    val show = ProcessState.running || (ProcessState.finishedAt > 0L && now - ProcessState.finishedAt < 180000L)
    if (!show || tick < 0) return
    Panel {
        if (ProcessState.running) {
            val secs = (now - ProcessState.startedAt) / 1000
            Text("⏳ " + ProcessState.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (ProcessState.total > 0) {
                LinearProgressIndicator(
                    progress = { (ProcessState.done.toFloat() / ProcessState.total.toFloat()).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)),
                    color = C.PRIMARY, trackColor = C.SURFACE2
                )
                Text("${ProcessState.done} de ${ProcessState.total} • ${secs}s", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = C.PRIMARY, trackColor = C.SURFACE2)
                Text("${secs}s", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            }
            if (AiStatus.stage.isNotBlank()) Text("IA: " + AiStatus.stage, fontSize = 12.sp, color = C.WARN)
        } else {
            Text("✔ Processamento concluído", fontWeight = FontWeight.Bold, color = C.OK)
            Text(ProcessState.summary, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun SlotCard(s: SlotSummary, onClick: () -> Unit, onTactic: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).clickable { onClick() },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = C.SURFACE)
    ) {
        val accent = if (s.tacticReady && s.missing == 0) C.OK else if (s.tacticReady) C.PRIMARY else C.WARN
        Row(
            Modifier.drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
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
                if (s.nextAt != null) {
                    Row(Modifier.padding(top = 2.dp)) {
                        Pill("⏱ " + countdown(s.nextAt))
                        Box(Modifier.clickable { onTactic() }) { Pill(if (s.tacticReady) "Tática ✔" else "Gerar tática →", s.tacticReady) }
                    }
                }
                if (s.missing > 0) {
                    Box(Modifier.clickable { onClick() }.padding(top = 4.dp)) { Pill("faltam ${s.missing} campos — toque para ver/preencher") }
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
private fun TodayTab(onGoSettings: () -> Unit, onOpenSlot: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(1000L)
    val scope = rememberCoroutineScope()
    val data = rememberLoaded(TodayData(), tick, UiBus.version) { loadToday(ctx) }
    var msg by remember { mutableStateOf("") }
    var showDiag by remember { mutableStateOf(false) }
    val enabled = serviceEnabled(ctx)
    val connected = Diag.serviceConnected
    val ready = enabled && connected
    val batteryOk = batteryUnrestricted(ctx)
    val hasKey = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFF0E2A5A), Color(0xFF1F6BFF))))
                .padding(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(painterResource(R.drawable.ic_launcher_fg), contentDescription = null, modifier = Modifier.size(58.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("OSM AI Coach", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Text("Você joga, o app lê, o diretor decide.", fontSize = 12.sp, color = Color(0xCCFFFFFF))
                }
                Pill(if (ready) "Leitura ativa" else "Leitura desligada", ready)
            }
        }

        if (!ready || !batteryOk || !hasKey) SetupBanner(ctx, enabled, connected, batteryOk, hasKey, onGoSettings)

        Spacer(Modifier.height(8.dp))
        if (data.active) {
            Button(
                onClick = {
                    AppScope.scope.launch { Control.end(ctx.applicationContext) }
                    msg = "Captura encerrada. Processando em segundo plano…"
                },
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
        ProcessCard()

        val upcoming = data.slots.filter { it.nextAt != null && it.nextAt > System.currentTimeMillis() - 7200000L }.sortedBy { it.nextAt }
        if (upcoming.isNotEmpty()) {
            Title("Próximos jogos")
            Panel {
                for (u in upcoming) {
                    Row(Modifier.fillMaxWidth().clickable { onOpenSlot(u.slot, 4) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("S${u.slot} • ${u.title}" + (if (u.rival != null) " vs ${u.rival}" else ""), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(countdown(u.nextAt!!), fontSize = 11.sp, color = C.MUTED)
                        }
                        Pill(if (u.tacticReady) "Tática ✔" else "Gerar tática →", u.tacticReady)
                    }
                }
            }
        }

        Title("Seus slots")
        for (s in data.slots) SlotCard(s, { onOpenSlot(s.slot, 0) }, { onOpenSlot(s.slot, 4) })

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
private fun Thumb(path: String?) {
    val bmp = remember(path) {
        if (path != null && File(path).exists()) {
            val o = BitmapFactory.Options()
            o.inSampleSize = 4
            BitmapFactory.decodeFile(path, o)?.asImageBitmap()
        } else null
    }
    if (bmp != null) {
        Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.width(110.dp).height(62.dp).clip(RoundedCornerShape(8.dp)))
    } else {
        Box(Modifier.width(110.dp).height(62.dp).clip(RoundedCornerShape(8.dp)).background(C.SURFACE2))
    }
}

@Composable
private fun SavedScreens(tick: Int) {
    val ctx = LocalContext.current
    val list = rememberLoaded(emptyList<ScreenEntity>(), tick, UiBus.version) { Repo(ctx).dao.savedScreens(12) }
    Title("Telas guardadas para a IA ler")
    Text(
        "Telas que o app não leu sozinho (por exemplo, o relatório do rival) ficam aqui. Toque em “Ler com IA” para extrair os dados.",
        fontSize = 12.sp, color = C.MUTED
    )
    if (list.isEmpty()) Text("Nenhuma tela guardada ainda.", color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    for (s in list) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.imagePath)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(s.type) + " • " + (if (s.slotId > 0) "S${s.slotId}" else "sem slot"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Text(fmtTime(s.at) + " • " + s.aiState, fontSize = 11.sp, color = C.MUTED)
                    if (s.note.isNotBlank()) Text(s.note, fontSize = 11.sp, color = C.MUTED, maxLines = 3)
                }
            }
            OutlinedButton(
                onClick = { startReadOne(ctx, s.id) },
                enabled = !ProcessState.running && s.slotId > 0,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text(if (s.slotId > 0) "Ler com IA" else "Sem slot — não dá para aplicar") }
        }
    }
}

@Composable
private fun SessionsTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    val rows = rememberLoaded(emptyList<SessionRow>(), tick, null) {
        val dao = Repo(ctx).dao
        dao.sessions().map { sess ->
            val counts = dao.typeCounts(sess.id).joinToString(" • ") { "${typeLabel(it.type)} ${it.c}" }
            SessionRow(sess, counts, dao.unassignedCount(sess.id))
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Sessões", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        ProcessCard()
        OutlinedButton(
            onClick = {
                if (!ProcessState.running) {
                    val app = ctx.applicationContext
                    AppScope.scope.launch(Dispatchers.IO) {
                        try {
                            Processor.run(app, "manual")
                        } catch (e: Exception) {
                            ProcessState.finish("Erro: " + (e.message ?: "?"))
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
        ) { Text("Reprocessar quadros sem slot e fila de IA") }
        SavedScreens(tick)
        Title("Histórico")
        if (rows.isEmpty()) Text("Nenhuma sessão ainda.", color = C.MUTED)
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
private fun SlotsTab(onOpen: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick()
    val data = rememberLoaded(TodayData(), tick, UiBus.version) { loadToday(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Slots", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        for (s in data.slots) SlotCard(s, { onOpen(s.slot, 0) }, { onOpen(s.slot, 4) })
    }
}

data class DirSlot(
    val s: SlotSummary,
    val forecast: String,
    val sells: List<String>,
    val buys: List<String>,
    val trains: List<String>,
    val radar: String?,
    val cash: String,
    val sellSlots: Int,
    val alerts: List<String> = emptyList(),
    val stadium: String? = null,
    val goal: String? = null
)

private suspend fun loadDirector(ctx: Context): List<DirSlot> {
    val repo = Repo(ctx)
    val today = loadToday(ctx)
    val out = ArrayList<DirSlot>()
    for (s in today.slots) {
        val f = repo.fieldMap(s.slot)
        val inp = Director.tacticInput(repo, s.slot)
        val ready = inp.players.count { it.strength != null } >= 8
        val res = if (ready) TacticEngine.recommend(inp) else null
        val forecast = if (res == null) "faltam dados do elenco (leia o Plantel do seu time)" else {
            val d = res.diff
            res.tactic.formation + " • " + res.tactic.playStyle +
                (if (d != null) " • força " + (if (d >= 0) "+" else "") + d + " • chance de vitória ≈ " + Forecast.winPercent(d, inp.home) + "%" else "")
        }
        val plan = Director.marketPlan(repo, s.slot)
        val alerts = ArrayList<String>()
        val nextIn = s.nextAt?.let { it - System.currentTimeMillis() }
        if (!s.tacticReady && nextIn != null && nextIn in 0L..10800000L) alerts.add("⚠ S${s.slot}: jogo em ${countdown(s.nextAt ?: 0L)} e a tática ainda não foi gerada.")
        if (plan != null) {
            val free = 5 - plan.trainingActive
            if (free > 0 && plan.train.isNotEmpty()) alerts.add("🔵 S${s.slot}: $free treino(s) livre(s) — ${plan.train.joinToString(", ") { it.name }}.")
            if (plan.sell.isNotEmpty()) alerts.add("🔴 S${s.slot}: ${plan.sell.size} venda(s) sugerida(s) para liberar caixa.")
        }
        if (s.missing > 0) alerts.add("📋 S${s.slot}: faltam ${s.missing} campos de leitura (toque no slot para preencher).")
        alerts.addAll(Director.priceDrops(repo, s.slot))
        val stadiumLine = Director.stadiumPlan(f)
        if (stadiumLine != null && stadiumLine.contains("cabe no caixa")) alerts.add("🏟 S${s.slot}: um melhoramento do estádio cabe no caixa agora.")
        val gp = f[K.MY_OBJECTIVE]?.value?.toIntOrNull()
        val np = f[K.LEAGUE_POS]?.value?.toIntOrNull()
        val goalLine = if (gp != null && np != null) {
            if (np <= gp) "🎯 Meta: top $gp • hoje ${np}º ✔" else "🎯 Meta: top $gp • hoje ${np}º ⚠ (${np - gp} posição(ões) fora)"
        } else null
        if (gp != null && np != null && np > gp) alerts.add("🎯 S${s.slot}: fora da meta da temporada (top $gp, hoje ${np}º).")
        val top = plan?.radar?.maxByOrNull { it.gain }
        out.add(
            DirSlot(
                s = s,
                forecast = forecast,
                sells = plan?.sell?.map { it.name + " (" + it.cat + " " + (it.strength ?: "?") + ")" } ?: emptyList(),
                buys = plan?.buy?.map { it.name + " (+" + it.gain + ")" } ?: emptyList(),
                trains = plan?.train?.map { it.name + " → " + (if (it.trainer == "universal") "universal" else it.trainer) } ?: emptyList(),
                radar = top?.let { it.cat + ": " + it.name + " (" + it.strength + ")" + (if (it.affordable) " ✔ cabe no caixa" else " — falta caixa") },
                cash = f[K.CASH]?.value ?: NI,
                sellSlots = MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value),
                alerts = alerts,
                stadium = stadiumLine,
                goal = goalLine
            )
        )
    }
    return out
}

@Composable
private fun DirectorTab(onOpen: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(3000L)
    val list = rememberLoaded(emptyList<DirSlot>(), tick, UiBus.version) { loadDirector(ctx) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Diretor", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Text("Visão geral dos 4 slots: o que fazer e em que ordem, tudo calculado com os dados já lidos.", color = C.MUTED, fontSize = 12.sp)

        // IA sempre no topo
        Panel {
            Text("🤖 IA", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            KV("Chamadas ao Gemini hoje", AiClient.usedToday(ctx).toString())
            KV("Último erro", Diag.lastError ?: "nenhum")
            Text("Comentário e refino da IA: abra o slot → abas Tática e Diretor (botões no topo).", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }

        val alertLines = list.flatMap { it.alerts }
        if (alertLines.isNotEmpty()) {
            Title("Alertas do diretor")
            Panel {
                for (a in alertLines) Text(a, fontSize = 12.sp, modifier = Modifier.padding(vertical = 3.dp))
            }
        }
        val upcoming = list.filter { it.s.nextAt != null && it.s.nextAt > now - 7200000L }.sortedBy { it.s.nextAt }
        if (upcoming.isNotEmpty()) {
            Title("Agenda")
            Panel {
                for (u in upcoming) {
                    Row(Modifier.fillMaxWidth().clickable { onOpen(u.s.slot, 4) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("S${u.s.slot} • ${u.s.title}" + (if (u.s.rival != null) " vs ${u.s.rival}" else ""), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(countdown(u.s.nextAt!!) + " • previsão: " + u.forecast, fontSize = 11.sp, color = C.MUTED)
                        }
                        Pill(if (u.s.tacticReady) "Tática ✔" else "Gerar →", u.s.tacticReady)
                    }
                }
            }
        }

        for (d in list) {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Crest(d.s.slot, 44.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("S${d.s.slot} • ${d.s.title}", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row {
                            if (d.s.rival != null) Pill("vs ${d.s.rival}")
                            if (d.s.human != null) Pill(if (d.s.human == "Sim") "Humano" else "CPU", d.s.human == "Sim")
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("⚽ Previsão de tática: ${d.forecast}", fontSize = 13.sp)
                Text("💰 Caixa ${d.cash} • vagas para vender: ${d.sellSlots}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                Text("🔴 Vender: " + (if (d.sells.isEmpty()) "nada por enquanto" else d.sells.joinToString(", ")), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("🟢 Comprar: " + (if (d.buys.isEmpty()) "nada que caiba no caixa" else d.buys.joinToString(", ")), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                Text("🔵 Treino: " + (if (d.trains.isEmpty()) "treinadores ocupados" else d.trains.joinToString(", ")), fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                if (d.radar != null) Text("📡 Radar: ${d.radar}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                if (d.goal != null) Text(d.goal, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                if (d.stadium != null) Text("🏟 ${d.stadium}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onOpen(d.s.slot, 4) }, modifier = Modifier.weight(1f)) { Text("Tática", fontSize = 12.sp) }
                    OutlinedButton(onClick = { onOpen(d.s.slot, 6) }, modifier = Modifier.weight(1f)) { Text("Plano completo", fontSize = 12.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SlotScreen(slot: Int, startTab: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    val data = rememberLoaded(SlotData(), tick, Pair(slot, UiBus.version)) { loadSlot(ctx, slot) }
    var tab by remember { mutableIntStateOf(startTab) }
    val tabs = listOf("Resumo", "Pré-jogo", "Elenco", "Calendário", "Tática", "Resultado", "Diretor", "Aprendizado")
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
                0 -> SlotSummaryTab(slot, data)
                1 -> SlotPregame(slot, data)
                2 -> SlotSquad(slot, data)
                3 -> SlotCalendar(slot, data)
                4 -> SlotTactic(slot, data)
                5 -> SlotResult(slot, data) { tab = 4 }
                6 -> SlotDirector(slot, data)
                else -> SlotLearning(slot, data)
            }
        }
    }
}

private fun fv(d: SlotData, key: String): String = known(d.fields, key) ?: NI

@Composable
private fun Section(title: String, rows: List<Pair<String, String>>) {
    Panel {
        Text(title, color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        for ((k, v) in rows) KV(k, v)
    }
}

@Composable
private fun SlotSummaryTab(slot: Int, d: SlotData) {
    var missingEdit by remember { mutableStateOf<Completeness.Item?>(null) }
    val c = d.completeness
    val at = fv(d, K.MATCH_AT).toLongOrNull()
    Panel {
        Text("Próximo jogo", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val rival = fv(d, K.RIVAL_TEAM)
        Text(if (rival == NI) "Adversário ainda não lido" else "vs $rival", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 6.dp)) {
            val human = fv(d, K.RIVAL_HUMAN)
            if (human != NI) Pill(if (human == "Sim") "Humano" else "CPU", human == "Sim")
            val home = fv(d, K.HOME)
            if (home != NI) Pill(if (home == "Casa") "🏠 Casa" else "✈ Fora")
            if (at != null) Pill(fmtTime(at))
            val ref = fv(d, K.REFEREE)
            if (ref != NI) Pill("Árbitro: $ref", ref != "Rigoroso")
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            ForceBox("Minha força", fv(d, K.MY_STRENGTH), C.PRIMARY)
            ForceBox("Rival", fv(d, K.RIVAL_STRENGTH), C.LOSS)
        }
    }
    EditSection(
        slot, d, "Competição",
        listOf(
            EditRow("Time", K.TEAM, fv(d, K.TEAM).let { if (it == NI) fv(d, K.HUB_TITLE) else it }),
            EditRow("Competição", K.COMPETITION),
            EditRow("Tipo", K.COMP_TYPE),
            EditRow("Rodadas concluídas", K.ROUND_DONE),
            EditRow("Total de rodadas", K.ROUND_TOTAL),
            EditRow("Próxima rodada", K.ROUND),
            EditRow("Posição na liga", K.LEAGUE_POS),
            EditRow("Pontos", K.POINTS),
            EditRow("Caixa", K.CASH),
            EditRow("Objetivo da temporada (posição)", K.MY_OBJECTIVE),
            EditRow("Nível do estádio (relvado) — bônus em casa", K.MY_STAD_PITCH),
            EditRow("Capacidade — receita por jogo", K.MY_STAD_CAP),
            EditRow("Treino do estádio — evolução nos treinos", K.MY_STAD_TRAIN)
        )
    )
    val stadiumPlan = Director.stadiumPlan(d.fields)
    if (stadiumPlan != null) {
        Panel {
            Text("🏟 Plano do estádio", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(stadiumPlan, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    val goalPos = fv(d, K.MY_OBJECTIVE).toIntOrNull()
    val nowPos = fv(d, K.LEAGUE_POS).toIntOrNull()
    if (goalPos != null && nowPos != null) {
        Panel {
            Text("🎯 Meta da temporada", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (nowPos <= goalPos) "Objetivo: terminar em ${goalPos}º ou melhor. Hoje: ${nowPos}º — dentro da meta ✔"
                else "Objetivo: terminar em ${goalPos}º ou melhor. Hoje: ${nowPos}º — ${nowPos - goalPos} posição(ões) fora da meta ⚠",
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    if (c != null) {
        Panel {
            Text("Completude: ${c.percent}% (${c.known.size} de ${c.known.size + c.missing.size} campos)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Bar(c.percent)
            Spacer(Modifier.height(8.dp))
            Text("Conhecidos: " + c.known.joinToString(", ").ifBlank { "nenhum" }, fontSize = 12.sp)
        }
        Panel {
            Text("Faltando — toque para preencher à mão", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            if (c.missing.isEmpty()) Text("Nada faltando ✔", color = C.OK, modifier = Modifier.padding(top = 4.dp))
            for (mi in c.missingItems) {
                Row(Modifier.fillMaxWidth().clickable { missingEdit = mi }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(mi.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("onde ver: " + ManualFields.spec(mi.key, mi.label).hint, fontSize = 11.sp, color = C.MUTED)
                    }
                    Text("＋ preencher", color = C.PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            for (m in c.missing) {
                if (c.missingItems.none { x -> x.label == m }) {
                    Text("• $m — abra essa tela no jogo e role até o fim", fontSize = 12.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
    val me = missingEdit
    if (me != null) ManualDialog(slot, ManualFields.spec(me.key, me.label), fv(d, me.key)) { missingEdit = null }
    Panel {
        Text("Última atualização por seção", fontWeight = FontWeight.Bold)
        for (t in listOf("PREGAME", "SQUAD", "CALENDAR", "MARKET", "REPORT")) {
            KV(typeLabel(t), ago(d.sections[t] ?: 0L))
        }
    }
}

@Composable
private fun ForceBox(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SlotPregame(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val at = fv(d, K.MATCH_AT).toLongOrNull()
    // IA sempre no topo
    OutlinedButton(
        onClick = { startReadLatest(ctx, slot) },
        enabled = !ProcessState.running,
        modifier = Modifier.fillMaxWidth()
    ) { Text("🤖 Ler a análise do rival com IA agora") }
    ProcessCard()
    EditSection(
        slot, d, "Jogo",
        listOf(
            EditRow("Rodada", K.ROUND),
            EditRow("Data/hora do jogo", K.MATCH_AT, if (at != null) fmtTime(at) else NI),
            EditRow("Casa/fora", K.HOME),
            EditRow("Adversário", K.RIVAL_TEAM),
            EditRow("Humano/CPU", K.RIVAL_HUMAN),
            EditRow("Apelido do rival", K.RIVAL_NICK),
            EditRow("Árbitro (termômetro)", K.REFEREE)
        )
    )
    EditSection(
        slot, d, "Forças",
        listOf(
            EditRow("Minha força", K.MY_STRENGTH),
            EditRow("Meu bônus", K.MY_BONUS),
            EditRow("Força do rival", K.RIVAL_STRENGTH),
            EditRow("Valor do meu elenco", K.MY_VALUE),
            EditRow("Valor do elenco rival", K.RIVAL_VALUE),
            EditRow("Meu GOL", K.MY_GOL), EditRow("Meu DEF", K.MY_DEF), EditRow("Meu MEI", K.MY_MID), EditRow("Meu ATA", K.MY_ATK),
            EditRow("GOL do rival", K.RIVAL_GOL), EditRow("DEF do rival", K.RIVAL_DEF),
            EditRow("MEI do rival", K.RIVAL_MID), EditRow("ATA do rival", K.RIVAL_ATK),
            EditRow("Formação do rival", K.RIVAL_FORMATION)
        )
    )
    val rivalIsHuman = fv(d, K.RIVAL_HUMAN) == "Sim"
    val reportRows = ArrayList<EditRow>()
    reportRows.add(EditRow("Plano de jogo (estilo)", K.RIVAL_PLAN))
    reportRows.add(EditRow("Marcação", K.RIVAL_MARKING))
    reportRows.add(EditRow("Fora-de-jogo", K.RIVAL_OFFSIDE))
    reportRows.add(EditRow("Entradas (desarme)", K.RIVAL_TACKLE))
    reportRows.add(EditRow("Treino secreto", K.RIVAL_SECRET))
    reportRows.add(EditRow("Estágio (campo de treinamento)", K.RIVAL_CAMP))
    reportRows.add(EditRow("Nível do estádio", K.STADIUM))
    if (rivalIsHuman) {
        reportRows.add(EditRow("Apelido do usuário", K.RIVAL_NICK))
        reportRows.add(EditRow("Bônus do rival (círculo do pré-jogo)", K.RIVAL_LOGIN_BONUS))
    }
    EditSection(slot, d, "Relatório do rival (análise do analista)", reportRows)
    val prof = d.rivalProfile
    if (prof != null) {
        val pj = try { JSONObject(prof.json) } catch (e: Exception) { null }
        val arr = pj?.optJSONArray("entries")
        if (pj != null && arr != null && arr.length() > 0) {
            fun mode(key: String): String {
                val counts = HashMap<String, Int>()
                for (i in 0 until arr.length()) {
                    val v = arr.optJSONObject(i)?.optString(key) ?: ""
                    if (v.isNotBlank()) counts[v] = (counts[v] ?: 0) + 1
                }
                val best = counts.maxByOrNull { it.value } ?: return NI
                return "${best.key} (${best.value}x)"
            }
            Panel {
                Text("Perfil do rival humano — ${pj.optString("nick")}", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Análises guardadas: ${arr.length()} jogo(s). Quanto mais jogos, mais confiável.", fontSize = 11.sp, color = C.MUTED)
                KV("Formação mais usada", mode("formation"))
                KV("Estilo mais usado", mode("plan"))
                KV("Marcação", mode("marking"))
                KV("Fora-de-jogo", mode("offside"))
                KV("Entradas", mode("tackle"))
            }
        }
    }
    Text(
        "NI = ainda não lido. Formação, força, valor e setores do rival vêm do Plantel dele. Estilo, marcação, fora-de-jogo, entradas, estágio e nível do estádio vêm do Relatório do analista (a tela com a nota à esquerda): alterne os botões Tática e Equipa inicial. Apelido e bônus de login só existem se o rival for humano.",
        fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
    )
}

private fun catColor(cat: String): Color = when (cat) {
    "ATA" -> Color(0xFFE5484D)
    "MEI" -> Color(0xFF3D8BFF)
    "DEF" -> Color(0xFF2EA043)
    else -> Color(0xFFF2C94C)
}

@Composable
private fun TrainingDialog(slot: Int, p: PlayerEntity, onDone: () -> Unit) {
    val ctx = LocalContext.current
    fun set(v: Boolean?) {
        AppScope.scope.launch(Dispatchers.IO) {
            Repo(ctx).setTrainingOverride(slot, p.nameKey, v)
            UiBus.version++
        }
        onDone()
    }
    AlertDialog(
        onDismissRequest = onDone,
        containerColor = C.SURFACE,
        title = { Text(p.name, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Este jogador está em treino (camisa laranja)? Cada slot tem até 5 treinos: 4 treinadores de posição + 1 universal.", fontSize = 12.sp, color = C.MUTED)
                OutlinedButton(onClick = { set(true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("🟠 Está treinando") }
                OutlinedButton(onClick = { set(false) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Não está treinando") }
                OutlinedButton(onClick = { set(null) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Voltar ao automático") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDone) { Text("Cancelar") } }
    )
}

@Composable
private fun SlotSquad(slot: Int, d: SlotData) {
    var trainEdit by remember { mutableStateOf<PlayerEntity?>(null) }
    val mine = d.players.filter { it.owner == "MY" }
    val counts = HashMap<String, Int>()
    for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
    Panel {
        Text("Meu elenco: ${mine.size} jogadores", fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (n in MarketPlanner.needs(counts)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(catColor(n.cat).copy(alpha = 0.25f)).padding(horizontal = 14.dp, vertical = 8.dp)) {
                        Text("${n.have}/${n.target}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                    Text(n.cat, fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        KV("Treinando (camisa laranja)", "${mine.count { it.training == true }} de 5")
        Text("São no máximo 5 treinos por vez (4 treinadores de posição + 1 universal). Toque num jogador para corrigir se ele está treinando.", fontSize = 11.sp, color = C.MUTED)
        KV("Força geral / GOL / DEF / MEI / ATA", listOf(K.MY_STRENGTH, K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK).joinToString(" / ") { fv(d, it) })
    }
    if (mine.isEmpty()) {
        Text("Nenhum jogador seu lido ainda. No jogo: Plantel do SEU time, depois role a lista devagar até o fim.", color = C.MUTED, fontSize = 12.sp)
    }
    for (cat in listOf("ATA", "MEI", "DEF", "GOL")) {
        val list = mine.filter { it.cat == cat }.sortedByDescending { it.strength ?: 0 }
        if (list.isEmpty()) continue
        Title(cat)
        Panel {
            for (p in list) {
                Row(Modifier.fillMaxWidth().clickable { trainEdit = p }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(catColor(cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Text(p.strength?.toString() ?: "?", fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text("${p.posCode ?: "?"} • ${p.age ?: "?"} anos • ${p.valueText ?: NI}", fontSize = 12.sp, color = C.MUTED)
                        val ft = d.fitness[p.nameKey]
                        if (ft != null) {
                            Text("Condição ${ft.first}% • Moral ${ft.second}%", fontSize = 11.sp, color = if (ft.first < 70 || ft.second < 50) C.WARN else C.MUTED)
                        }
                    }
                    if (p.training == true) Pill("🟠 Treinando")
                }
            }
        }
    }
    val te = trainEdit
    if (te != null) TrainingDialog(slot, te) { trainEdit = null }
}

@Composable
private fun CountPill(text: String, color: Color) {
    Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50)).background(color).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (color == C.DRAW) Color(0xFF222222) else Color.White)
    }
}

@Composable
private fun ResultBadge(result: String?) {
    val c = when (result) {
        "V" -> C.WIN
        "E" -> C.DRAW
        "D" -> C.LOSS
        else -> C.SURFACE2
    }
    Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(c), contentAlignment = Alignment.Center) {
        Text(result ?: "–", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = if (result == "E") Color(0xFF222222) else Color.White)
    }
}

@Composable
private fun MatchCard(m: MatchEntity, modifier: Modifier, highlight: Boolean, onClick: () -> Unit) {
    val tint = when (m.result) {
        "V" -> Color(0xFF12351F)
        "E" -> Color(0xFF3A3210)
        "D" -> Color(0xFF3A1616)
        else -> C.SURFACE2
    }
    val score = if (m.scoreMine != null && m.scoreOpp != null) "${m.scoreMine}-${m.scoreOpp}" else "–"
    val place = when (m.home) {
        true -> "🏠"
        false -> "✈"
        null -> ""
    }
    val shape = RoundedCornerShape(12.dp)
    val frame = if (highlight) modifier.border(2.dp, C.GOLD, shape) else modifier
    Column(
        frame.clickable { onClick() }.height(128.dp).clip(shape).background(tint).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(place + " " + (if (m.round != null) "J${m.round}" else m.label.take(8)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
            ResultBadge(m.result)
        }
        Text(score, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text(
            (m.opponent ?: NI) + (if (m.opponentNick != null) " 👤" else ""),
            fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
        )
        Text(m.date ?: m.time ?: "", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        if (highlight) Text("PRÓXIMO", fontSize = 9.sp, color = C.GOLD, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun UnreadCard(round: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clickable { onClick() }.height(128.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.SURFACE2, RoundedCornerShape(12.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("J$round", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
        Text("não lido", fontSize = 11.sp, color = C.MUTED)
        Text("role o jogo ou toque para preencher", fontSize = 9.sp, color = C.MUTED, textAlign = TextAlign.Center)
    }
}

@Composable
private fun FilterPill(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.padding(end = 6.dp).clip(RoundedCornerShape(50))
            .background(if (selected) C.PRIMARY else C.SURFACE2).clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (selected) Color.White else C.MUTED) }
}

@Composable
private fun SlotCalendar(slot: Int, d: SlotData) {
    var mode by remember { mutableIntStateOf(0) }
    var editRound by remember { mutableStateOf<Int?>(null) }
    if (d.matches.isEmpty() && fv(d, K.ROUND_TOTAL).toIntOrNull() == null) {
        Text("Calendário ainda não lido neste slot. No jogo: menu → Calendário do SEU time (no topo da lista) e role. Ou informe à mão:", color = C.MUTED)
        OutlinedButton(onClick = { editRound = fv(d, K.ROUND).toIntOrNull() ?: 1 }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text("＋ Informar uma rodada")
        }
        val e0 = editRound
        if (e0 != null) MatchDialog(slot, e0, null) { editRound = null }
        return
    }
    val sorted = d.matches.sortedBy { it.round ?: 999 }
    val total = fv(d, K.ROUND_TOTAL).toIntOrNull()
    val read = sorted.count { it.round != null }
    val byRound = sorted.filter { it.round != null }.associateBy { it.round!! }
    val unread = if (total != null) (1..total).filter { it !in byRound } else emptyList()
    if (total != null && total > 0) {
        Panel {
            Text("Calendário lido: $read de $total rodadas", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Bar(read * 100 / total)
            if (unread.isNotEmpty()) {
                Text(
                    "Faltam as rodadas ${unread.first()}–${unread.last()}. Role o calendário do jogo até o fim para ler.",
                    fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    Row(Modifier.padding(bottom = 8.dp)) {
        CountPill("V ${sorted.count { it.result == "V" }}", C.WIN)
        CountPill("E ${sorted.count { it.result == "E" }}", C.DRAW)
        CountPill("D ${sorted.count { it.result == "D" }}", C.LOSS)
        CountPill("${sorted.count { it.result == null }} a jogar", C.SURFACE2)
    }
    Row(Modifier.padding(bottom = 8.dp)) {
        FilterPill("Todos", mode == 0) { mode = 0 }
        FilterPill("Resultados", mode == 1) { mode = 1 }
        FilterPill("Próximos", mode == 2) { mode = 2 }
    }
    val nextRound = sorted.firstOrNull { it.result == null && it.round != null }?.round
    // Lista final: rodadas da liga em ordem (com "não lido" nos buracos), depois jogos de copa/outros.
    val cells = ArrayList<Pair<Int?, MatchEntity?>>()
    if (total != null && total > 0) {
        for (r in 1..total) cells.add(Pair(r, byRound[r]))
    } else {
        for (m in sorted.filter { it.round != null }) cells.add(Pair(m.round, m))
    }
    for (m in sorted.filter { it.round == null }) cells.add(Pair(null, m))
    val shown = cells.filter { (_, m) ->
        when (mode) {
            1 -> m != null && m.result != null
            2 -> m == null || m.result == null
            else -> true
        }
    }
    for (row in shown.chunked(3)) {
        Row(Modifier.fillMaxWidth()) {
            for ((r, m) in row) {
                val mod = Modifier.weight(1f).padding(3.dp)
                if (m != null) MatchCard(m, mod, m.round != null && m.round == nextRound) { if (m.round != null) editRound = m.round }
                else UnreadCard(r ?: 0, mod) { if (r != null) editRound = r }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
    val er = editRound
    if (er != null) MatchDialog(slot, er, byRound[er]) { editRound = null }
}

private fun formationLines(f: String): List<Int> = Formations.lines(f)

private val CY = Color(0xFF29B6F6)
private val OR = Color(0xFFFFA726)
private val RD = Color(0xFFE5484D)

private fun DrawScope.arrow(from: Offset, to: Offset, color: Color, width: Float) {
    drawLine(color, from, to, width)
    val dx = to.x - from.x
    val dy = to.y - from.y
    val len = sqrt(dx * dx + dy * dy)
    if (len < 1f) return
    val ux = dx / len
    val uy = dy / len
    val head = width * 3.2f
    drawLine(color, to, Offset(to.x - ux * head - uy * head * 0.6f, to.y - uy * head + ux * head * 0.6f), width)
    drawLine(color, to, Offset(to.x - ux * head + uy * head * 0.6f, to.y - uy * head - ux * head * 0.6f), width)
}

/** Setas como nas telas "Avançadas" do OSM: ciano = ataque/pressão, laranja = apoio/recuo. */
private fun arrowSet(option: String): List<Triple<Float, Float, Color>> {
    val n = Txt.norm(option)
    return when {
        n.contains("atacar apenas") -> listOf(Triple(0f, -1f, CY))
        n.contains("ajudar a defender") || n.contains("ajudar a defesa") ->
            listOf(Triple(0f, -1f, CY), Triple(-0.7f, 0.7f, OR), Triple(0.7f, 0.7f, OR))
        n.contains("manter") -> listOf(Triple(-1f, 0f, OR), Triple(1f, 0f, OR))
        n.contains("pressionar") -> listOf(Triple(0f, -1f, CY), Triple(-0.7f, -0.7f, OR), Triple(0.7f, -0.7f, OR))
        n.contains("defender atras") -> listOf(Triple(0f, 1f, OR), Triple(-0.7f, 0.7f, OR), Triple(0.7f, 0.7f, OR))
        else -> emptyList()
    }
}

@Composable
private fun SectorIcon(option: String) {
    Canvas(Modifier.size(64.dp, 84.dp).clip(RoundedCornerShape(10.dp))) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF1E7B3B))
        drawRect(Color(0xCCFFFFFF), topLeft = Offset(w * 0.06f, h * 0.05f), size = Size(w * 0.88f, h * 0.9f), style = Stroke(2f))
        val c = Offset(w * 0.5f, h * 0.5f)
        drawCircle(Color(0xCCFFFFFF), radius = w * 0.2f, center = c, style = Stroke(2f))
        for ((dx, dy, col) in arrowSet(option)) {
            arrow(c, Offset(c.x + dx * w * 0.38f, c.y + dy * h * 0.38f), col, 4f)
        }
        drawCircle(Color.White, radius = 7f, center = c)
    }
}

/** Campo em perspectiva (trapézio), como a tela de análise do OSM. */
private fun DrawScope.miniPitch(w: Float, h: Float) {
    val top = h * 0.14f
    val bottom = h * 0.92f
    val inset = w * 0.13f
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(inset, top)
    path.lineTo(w - inset, top)
    path.lineTo(w * 0.97f, bottom)
    path.lineTo(w * 0.03f, bottom)
    path.close()
    drawPath(path, Color(0xFF237F3E))
    drawPath(path, Color(0xCCFFFFFF), style = Stroke(2f))
    drawLine(Color(0xCCFFFFFF), Offset(w * 0.5f, top), Offset(w * 0.5f, bottom), 2f)
    drawOval(Color(0xCCFFFFFF), topLeft = Offset(w * 0.43f, h * 0.40f), size = Size(w * 0.14f, h * 0.22f), style = Stroke(2f))
}

@Composable
private fun StyleIcon(style: String) {
    val n = Txt.norm(style)
    Canvas(Modifier.size(132.dp, 78.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E2A5A))) {
        val w = size.width
        val h = size.height
        miniPitch(w, h)
        when {
            n.contains("alas") -> {
                arrow(Offset(w * 0.18f, h * 0.34f), Offset(w * 0.86f, h * 0.34f), RD, 6f)
                arrow(Offset(w * 0.12f, h * 0.76f), Offset(w * 0.9f, h * 0.76f), RD, 6f)
            }
            n.contains("passe") -> {
                val pts = listOf(Offset(0.14f, 0.78f), Offset(0.32f, 0.36f), Offset(0.5f, 0.74f), Offset(0.68f, 0.34f), Offset(0.88f, 0.52f))
                for (i in 0 until pts.size - 2) drawLine(RD, Offset(pts[i].x * w, pts[i].y * h), Offset(pts[i + 1].x * w, pts[i + 1].y * h), 6f)
                arrow(Offset(pts[3].x * w, pts[3].y * h), Offset(pts[4].x * w, pts[4].y * h), RD, 6f)
            }
            n.contains("remate") -> {
                arrow(Offset(w * 0.2f, h * 0.8f), Offset(w * 0.8f, h * 0.3f), RD, 6f)
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.2f, h * 0.8f))
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.52f, h * 0.56f))
            }
            n.contains("contra") -> {
                // recua e sai em velocidade: seta curta para trás + seta longa para o gol adversário
                arrow(Offset(w * 0.78f, h * 0.30f), Offset(w * 0.46f, h * 0.30f), OR, 5f)
                arrow(Offset(w * 0.16f, h * 0.84f), Offset(w * 0.88f, h * 0.34f), RD, 7f)
                drawCircle(Color.White, radius = 6f, center = Offset(w * 0.16f, h * 0.84f))
            }
            n.contains("long") || n.contains("bola") -> {
                // lançamento: arco da defesa até o ataque
                var prev = Offset(w * 0.14f, h * 0.84f)
                for (i in 1..10) {
                    val t = i / 10f
                    val x = w * (0.14f + 0.74f * t)
                    val y = h * (0.84f - 0.50f * t) - h * 0.30f * (1f - (2f * t - 1f) * (2f * t - 1f))
                    val cur = Offset(x, y)
                    if (i < 10) drawLine(RD, prev, cur, 6f) else arrow(prev, cur, RD, 6f)
                    prev = cur
                }
            }
        }
    }
}

@Composable
private fun LineupPitch(plan: JSONObject) {
    val rows = ArrayList<List<Pair<String, Int>>>()
    val arr = plan.optJSONArray("lineup")
    if (arr != null) {
        for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            val row = ArrayList<Pair<String, Int>>()
            for (j in 0 until r.length()) {
                val o = r.optJSONObject(j)
                row.add(Pair(o?.optString("n") ?: "?", o?.optInt("s") ?: 0))
            }
            rows.add(row)
        }
    }
    if (rows.isEmpty()) {
        val ls = formationLines(plan.optString("formation"))
        if (ls.isNotEmpty()) {
            rows.add(listOf(Pair("GOL", 0)))
            for (n in ls) rows.add(List(n) { Pair("", 0) })
        }
    }
    val advA = arrowSet(plan.optString("advAttack"))
    val advM = arrowSet(plan.optString("advMid"))
    val advD = arrowSet(plan.optString("advDef"))
    Canvas(Modifier.fillMaxWidth().height(400.dp).clip(RoundedCornerShape(16.dp))) {
        val w = size.width
        val h = size.height
        val dp = density
        drawRect(Color(0xFF1E7B3B))
        val stripe = h / 8f
        for (i in 0 until 8 step 2) drawRect(Color(0x14FFFFFF), topLeft = Offset(0f, i * stripe), size = Size(w, stripe))
        val line = Color(0xCCFFFFFF)
        drawRect(line, topLeft = Offset(w * 0.04f, h * 0.03f), size = Size(w * 0.92f, h * 0.94f), style = Stroke(3f))
        drawLine(line, Offset(w * 0.04f, h * 0.5f), Offset(w * 0.96f, h * 0.5f), 3f)
        drawCircle(line, radius = h * 0.1f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(3f))
        drawRect(line, topLeft = Offset(w * 0.3f, h * 0.03f), size = Size(w * 0.4f, h * 0.12f), style = Stroke(3f))
        drawRect(line, topLeft = Offset(w * 0.3f, h * 0.85f), size = Size(w * 0.4f, h * 0.12f), style = Stroke(3f))
        if (rows.isEmpty()) return@Canvas
        val namePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 10f * dp
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
        }
        val numPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textSize = 11f * dp
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        val k = rows.size - 1
        for ((ri, row) in rows.withIndex()) {
            val y = if (ri == 0) 0.9f else if (k <= 1) 0.5f else 0.75f - (ri - 1) * (0.6f / (k - 1))
            val arrows: List<Triple<Float, Float, Color>> = when {
                ri == 0 -> emptyList()
                ri == 1 -> advD
                ri == rows.size - 1 -> advA
                else -> advM
            }
            for ((j, pl) in row.withIndex()) {
                val x = (j + 1f) / (row.size + 1f)
                val c = Offset(w * x, h * y)
                val r = 16f * dp
                for ((dx, dy, col) in arrows) {
                    arrow(
                        Offset(c.x + dx * (r + 2f * dp), c.y + dy * (r + 2f * dp)),
                        Offset(c.x + dx * (r + 18f * dp), c.y + dy * (r + 18f * dp)), col, 3f * dp
                    )
                }
                val markerColor = when {
                    ri == 0 -> Color(0xFFFFC83D)
                    ri == 1 -> Color(0xFF3D8BFF)
                    ri == rows.size - 1 -> Color(0xFFFF6B5C)
                    else -> Color(0xFF9B6BFF)
                }
                drawCircle(Color(0x66000000), radius = r + 4f * dp, center = Offset(c.x, c.y + 2f * dp))
                drawCircle(Color.White, radius = r + 2f * dp, center = c)
                drawCircle(markerColor, radius = r, center = c)
                if (pl.first.isNotBlank()) {
                    val tw = namePaint.measureText(pl.first)
                    drawRoundRect(
                        Color(0xB30B1220), topLeft = Offset(c.x - tw / 2f - 6f * dp, c.y + r + 4f * dp),
                        size = Size(tw + 12f * dp, 15f * dp), cornerRadius = CornerRadius(8f * dp, 8f * dp)
                    )
                }
                drawIntoCanvas { cv ->
                    if (pl.second > 0) cv.nativeCanvas.drawText(pl.second.toString(), c.x, c.y + 4f * dp, numPaint)
                    if (pl.first.isNotBlank()) cv.nativeCanvas.drawText(pl.first, c.x, c.y + r + 15f * dp, namePaint)
                }
            }
        }
    }
}

@Composable
private fun TacticBar(label: String, value: Int) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = C.MUTED)
            Text(value.toString(), fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
        }
        Bar(value)
    }
}

@Composable
private fun GenPanel(kind: String, slot: Int, label: String, onStart: () -> Unit) {
    val tick = rememberTick(1000L)
    val job = GenState.get(kind, slot)
    val running = job != null && job.ok == null
    Button(
        enabled = !running,
        onClick = onStart,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp)
    ) { Text(if (running) "Trabalhando… (continua em segundo plano)" else label, fontWeight = FontWeight.Bold) }
    if (job == null || tick < 0) return
    val end = if (job.ok == null) System.currentTimeMillis() else job.finishedAt
    val secs = (end - job.startedAt) / 1000
    val time = if (secs >= 60) "${secs / 60} min ${secs % 60}s" else "${secs}s"
    Panel {
        when (job.ok) {
            null -> {
                Text("⏳ Trabalhando… $time", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)), color = C.PRIMARY, trackColor = C.SURFACE2)
                Text(
                    if (AiStatus.stage.isNotBlank()) AiStatus.stage else "Montando os dados…",
                    fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp)
                )
                Text("Pode trocar de aba ou sair do app: continua.", fontSize = 11.sp, color = C.MUTED)
            }
            true -> Text("✔ Pronto em $time", color = C.OK, fontWeight = FontWeight.Bold)
            false -> {
                Text("✘ Não deu certo após $time", color = C.BAD, fontWeight = FontWeight.Bold)
                Text(job.error ?: "erro desconhecido", fontSize = 12.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@Composable
private fun SlotTactic(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    GenPanel("tactic", slot, "Gerar tática para o próximo jogo") { startGeneration(ctx, "tactic", slot) }
    val plan = d.tactic
    if (plan == null) {
        Text("Nenhuma tática gerada ainda. A tática é calculada na hora com o seu elenco, a força do rival, o árbitro e o que já deu certo ou errado antes.", color = C.MUTED, modifier = Modifier.padding(top = 8.dp), fontSize = 12.sp)
        return
    }
    val j = try {
        JSONObject(plan.json)
    } catch (e: Exception) {
        null
    }
    if (j == null) return
    val forRound = j.optInt("forRound", -1)
    val curRound = fv(d, K.ROUND).toIntOrNull()
    if (curRound != null && forRound != curRound) {
        Text(
            "⚠ A tática guardada é de outra rodada (${if (forRound > 0) forRound else "?"}); o próximo jogo é a rodada $curRound. Toque em “Gerar tática” para criar a desta rodada.",
            color = C.WARN, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
        return
    }
    // IA sempre no topo
    Spacer(Modifier.height(8.dp))
    GenPanel("tactic_ai", slot, "🤖 Refinar com IA (opcional)") { startGeneration(ctx, "tactic_ai", slot) }
    Text("Gerada ${ago(plan.at)} para o jogo contra ${j.optString("rival")} — coloque no jogo:", color = C.MUTED, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Formação ${j.optString("formation")}", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Text(j.optString("playStyle"), color = C.GOLD, fontWeight = FontWeight.SemiBold)
                if (j.optBoolean("refined")) Pill("🤖 refinada pela IA")
            }
            StyleIcon(j.optString("playStyle"))
        }
        Spacer(Modifier.height(8.dp))
        LineupPitch(j)
        Text("Os números são a força de cada titular escolhido. Setas: ciano = ataque/pressão, laranja = apoio/recuo.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    }
    val lineupArr = j.optJSONArray("lineup")
    if (lineupArr != null && lineupArr.length() > 1) {
        Panel {
            Text("Escalação sugerida", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (i in 0 until lineupArr.length()) {
                val row = lineupArr.optJSONArray(i) ?: continue
                val label = if (i == 0) "GOL" else if (i == 1) "DEF" else if (i == lineupArr.length() - 1) "ATA" else "MEI"
                val names = ArrayList<String>()
                for (k in 0 until row.length()) {
                    val o = row.optJSONObject(k) ?: continue
                    names.add(o.optString("n") + " (" + o.optInt("s") + ")")
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Text(label, color = C.MUTED, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.width(44.dp))
                    Text(names.joinToString("  •  "), fontSize = 13.sp, modifier = Modifier.weight(1f))
                }
            }
        }
    }
    Panel {
        Text("Como colocar no jogo", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        val steps = listOf(
            "Tática → Formação: " + j.optString("formation"),
            "Estilo de jogo: " + j.optString("playStyle"),
            "Pressão " + j.optInt("pressure") + "  •  Mentalidade/Estilo " + j.optInt("mentality") + "  •  Temporização " + j.optInt("tempo"),
            "Marcação: " + j.optString("marking") + "  •  Fora de jogo: " + j.optString("offside") + "  •  Desarme: " + j.optString("tackle"),
            "Avançadas → Ataque: " + j.optString("advAttack") + "  •  Meio: " + j.optString("advMid") + "  •  Defesa: " + j.optString("advDef")
        )
        for ((i, t) in steps.withIndex()) {
            Text("${i + 1}. $t", fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Panel {
        Text("Controles", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        TacticBar("Pressão", j.optInt("pressure"))
        TacticBar("Mentalidade / Estilo", j.optInt("mentality"))
        TacticBar("Ritmo / Temporização", j.optInt("tempo"))
        Row(Modifier.padding(top = 8.dp)) {
            Pill("Marcação: " + j.optString("marking"))
            Pill("Impedimento: " + j.optString("offside"))
            Pill("Desarme: " + j.optString("tackle"))
        }
    }
    Panel {
        Text("Avançadas por setor (como no jogo)", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            for ((label, opt) in listOf("Ataque" to j.optString("advAttack"), "Meio" to j.optString("advMid"), "Defesa" to j.optString("advDef"))) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    SectorIcon(opt)
                    Text(label, fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                    Text(opt, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                }
            }
        }
    }
    val ra = j.optJSONArray("rationale")
    if (ra != null && ra.length() > 0) {
        Panel {
            Text("Por que esta tática", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (i in 0 until ra.length()) Text("• " + ra.optString(i), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
    val rk = j.optJSONArray("ranking")
    if (rk != null && rk.length() > 1) {
        Panel {
            Text("Outras formações calculadas", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (i in 0 until rk.length()) {
                val r = rk.optJSONArray(i) ?: continue
                Text("${i + 1}. ${r.optString(0)}  (pontuação ${r.optLong(1)})", fontSize = 12.sp, color = C.MUTED)
            }
        }
    }
}

@Composable
private fun SlotDirector(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    val plan = d.marketPlan
    if (plan == null) {
        Text("Leia o elenco do SEU time (Plantel no jogo, role a lista) para o diretor montar vendas, compras e treino.", color = C.MUTED)
        return
    }
    // IA sempre no topo
    GenPanel("market_ai", slot, "🤖 Pedir comentário da IA (opcional)") { startGeneration(ctx, "market_ai", slot) }
    if (d.marketNote != null) {
        Panel {
            Text("🤖 Comentário da IA", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(d.marketNote, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Panel {
        Text("Resumo do diretor", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(plan.summary, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        Text("Preços de venda são estimados pelo valor do jogador; confirme no jogo.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
    }
    if (plan.sell.isNotEmpty()) {
        Title("🔴 Vender")
        Panel {
            for (s in plan.sell) {
                Text("${s.name}  •  ${s.cat} ${s.strength ?: "?"}  •  ≈ ${s.valueM?.let { "%.1fM".format(it).replace('.', ',') } ?: NI}", fontWeight = FontWeight.SemiBold)
                Text(s.reason, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
    if (plan.buy.isNotEmpty()) {
        Title("🟢 Comprar")
        Panel {
            for (b in plan.buy) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.name}  •  ${b.cat} ${b.strength}", fontWeight = FontWeight.SemiBold)
                    CountPill("+${b.gain}", C.WIN)
                }
                Text("%.1fM".format(b.priceM).replace('.', ',') + " — " + b.reason, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
    if (plan.train.isNotEmpty()) {
        Title("🔵 Treino (${plan.trainingActive} de 5 ocupados)")
        Panel {
            for (t in plan.train) {
                Text("${t.name}  →  " + (if (t.trainer == "universal") "treinador universal" else "treinador de ${t.trainer}"), fontWeight = FontWeight.SemiBold)
                Text(t.reason, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 8.dp))
            }
        }
    }
    if (plan.radar.isNotEmpty()) {
        Title("📡 Radar de mercado")
        Panel {
            Text("Melhor alvo por posição no que já foi lido na lista de transferências:", fontSize = 12.sp, color = C.MUTED)
            for (r in plan.radar) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.cat}: ${r.name} (${r.strength})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("%.1fM".format(r.priceM).replace('.', ',') + " • ganho +${r.gain} no setor", fontSize = 11.sp, color = C.MUTED)
                    }
                    Pill(if (r.affordable) "cabe no caixa" else "falta caixa", r.affordable)
                }
            }
        }
    }
    val extra = plan.steps.filter { !it.startsWith("Vender") && !it.startsWith("Comprar") && !it.startsWith("Treinar") }
    if (extra.isNotEmpty()) {
        Panel { for (e in extra) Text("• $e", fontSize = 12.sp, modifier = Modifier.padding(vertical = 2.dp)) }
    }
}

@Composable
private fun SlotResult(slot: Int, d: SlotData, goTactic: () -> Unit) {
    val ctx = LocalContext.current
    data class LogRow(val round: Int, val formation: String, val style: String, val rival: String, val result: String?, val home: Boolean?)
    val rows = d.logs.mapNotNull { p ->
        try {
            val j = JSONObject(p.json)
            LogRow(
                j.optInt("round", -1), j.optString("formation"), j.optString("playStyle"), j.optString("rival"),
                if (j.isNull("result")) null else j.optString("result"),
                if (j.isNull("home")) null else j.optBoolean("home")
            )
        } catch (e: Exception) {
            null
        }
    }.filter { it.round > 0 }.sortedByDescending { it.round }
    if (rows.isEmpty()) {
        Panel {
            Text("⚠ Gere a tática primeiro", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(
                "O resultado só pode ser registrado para um jogo que teve tática gerada. É a ligação tática → resultado que a IA usa para aprender o que funciona. Gere a tática do próximo jogo na aba Tática e volte aqui depois da partida.",
                fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp)
            )
            Button(onClick = goTactic, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Ir para a aba Tática") }
        }
        return
    }
    var sel by remember { mutableIntStateOf(rows.firstOrNull { it.result == null }?.round ?: rows.first().round) }
    val cur = rows.firstOrNull { it.round == sel } ?: rows.first()
    Text("Escolha o jogo (só aparecem jogos que tiveram tática gerada):", color = C.MUTED, fontSize = 12.sp)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        for (r in rows.take(12)) {
            FilterPill("J${r.round}" + (if (r.result == null) " • pendente" else " ✔"), r.round == cur.round) { sel = r.round }
        }
    }
    val existing = d.reports.firstOrNull { it.kind == "mr_R${cur.round}" }
    val ej = existing?.json?.let { js ->
        try {
            JSONObject(js)
        } catch (e: Exception) {
            null
        }
    }
    key(cur.round) {
        val hasScore = ej != null && ej.has("sh") && ej.has("sa")
        val mh: Boolean? = if (ej != null && ej.has("mineHome")) ej.optBoolean("mineHome") else cur.home
        val initMine = if (hasScore && ej != null) (if (mh != false) ej.optInt("sh") else ej.optInt("sa")).toString() else ""
        val initOpp = if (hasScore && ej != null) (if (mh != false) ej.optInt("sa") else ej.optInt("sh")).toString() else ""
        var a by remember { mutableStateOf(initMine) }
        var b by remember { mutableStateOf(initOpp) }
        var home by remember { mutableStateOf(mh) }
        var poss by remember { mutableStateOf("") }
        var mShots by remember { mutableStateOf("") }
        var oShots by remember { mutableStateOf("") }
        var mFouls by remember { mutableStateOf("") }
        var oFouls by remember { mutableStateOf("") }
        var oForm by remember { mutableStateOf("") }
        var mom by remember { mutableStateOf("") }
        var msg by remember { mutableStateOf<String?>(null) }
        Panel {
            Text("J${cur.round} • ${cur.formation} • ${cur.style} vs ${cur.rival}", fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                if (cur.result == null) "Resultado ainda não registrado." else "Resultado atual: ${cur.result}. Você pode corrigir abaixo.",
                fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp)
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp)) {
                FilterPill("🏠 Casa", home == true) { home = true }
                FilterPill("✈ Fora", home == false) { home = false }
            }
            Text("Estatísticas (opcional — ajudam a IA a aprender)", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = poss, onValueChange = { poss = it.filter { c -> c.isDigit() }.take(3) }, singleLine = true, label = { Text("Minha posse (%)") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oForm, onValueChange = { oForm = it.take(12) }, singleLine = true, label = { Text("Formação do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mShots, onValueChange = { mShots = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus remates") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oShots, onValueChange = { oShots = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Remates do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = mFouls, onValueChange = { mFouls = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Minhas faltas") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = oFouls, onValueChange = { oFouls = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Faltas do rival") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(value = mom, onValueChange = { mom = it.take(30) }, singleLine = true, label = { Text("Homem do jogo") }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            val canSave = a.isNotBlank() && b.isNotBlank() && home != null
            Button(
                enabled = canSave,
                onClick = {
                    val ma = a.toIntOrNull()
                    val mb = b.toIntOrNull()
                    val hh = home
                    if (ma != null && mb != null && hh != null) {
                        val round = cur.round
                        val p = poss.toIntOrNull()?.coerceIn(0, 100)
                        val ms = mShots.toIntOrNull()
                        val os = oShots.toIntOrNull()
                        val mf = mFouls.toIntOrNull()
                        val of = oFouls.toIntOrNull()
                        val form = oForm.ifBlank { null }
                        val star = mom.ifBlank { null }
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).registerManualResult(slot, round, ma, mb, hh, p, ms, os, mf, of, form, star)
                            UiBus.version++
                        }
                        msg = "✔ Resultado registrado. A IA já usa este jogo para aprender."
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Registrar resultado", fontWeight = FontWeight.Bold) }
            if (!canSave) Text("Informe os gols dos dois times e se foi em casa ou fora.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            val m = msg
            if (m != null) Text(m, color = C.OK, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun SlotLearning(slot: Int, d: SlotData) {
    data class Row2(
        val kind: String, val round: Int, val formation: String, val style: String, val rival: String,
        val result: String?, val score: String, val human: Boolean?, val home: Boolean?
    )
    var resultFor by remember { mutableStateOf<Row2?>(null) }
    val rows = d.logs.mapNotNull { p ->
        try {
            val j = JSONObject(p.json)
            Row2(
                p.kind, j.optInt("round", -1), j.optString("formation"), j.optString("playStyle"), j.optString("rival"),
                if (j.isNull("result")) null else j.optString("result"),
                if (j.isNull("scoreMine")) "" else "${j.optInt("scoreMine")}-${j.optInt("scoreOpp")}",
                if (j.isNull("human")) null else j.optBoolean("human"),
                if (j.isNull("home")) null else j.optBoolean("home")
            )
        } catch (e: Exception) {
            null
        }
    }.sortedByDescending { it.round }
    val hist = rows.map { HistRow(it.formation, it.style, it.result, it.human, it.home) }

    // Análise dos jogos (tela de resultado do OSM): estatísticas reais de cada partida.
    if (d.reports.isNotEmpty()) {
        Panel {
            Text("Análise dos jogos (estatísticas lidas)", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (p in d.reports.sortedByDescending { it.at }.take(6)) {
                val j = try { JSONObject(p.json) } catch (e: Exception) { null } ?: continue
                val mineHome = j.optBoolean("mineHome", true)
                val st = j.optJSONObject("stats")
                fun mine(label: String): String = st?.optJSONArray(label)?.optString(if (mineHome) 0 else 1) ?: NI
                fun opp(label: String): String = st?.optJSONArray(label)?.optString(if (mineHome) 1 else 0) ?: NI
                val myGoals = if (mineHome) j.optInt("sh") else j.optInt("sa")
                val oppGoals = if (mineHome) j.optInt("sa") else j.optInt("sh")
                val oppName = (if (mineHome) j.optString("awayTeam") else j.optString("homeTeam")).ifBlank { "adversário" }
                val ratings = j.optJSONArray(if (mineHome) "rh" else "ra")
                var sum = 0
                var cnt = 0
                if (ratings != null) {
                    for (i in 0 until ratings.length()) {
                        sum += ratings.optJSONArray(i)?.optInt(1) ?: 0
                        cnt++
                    }
                }
                val res = if (myGoals > oppGoals) "V" else if (myGoals == oppGoals) "E" else "D"
                Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ResultBadge(res)
                        Spacer(Modifier.width(8.dp))
                        Text("J${j.optInt("round")} • $myGoals-$oppGoals vs $oppName", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                    Text(
                        "Posse ${mine("posse de bola")} x ${opp("posse de bola")} • Remates ${mine("remates")} x ${opp("remates")} • " +
                            "Faltas ${mine("faltas")} x ${opp("faltas")} • Cantos ${mine("cantos")} x ${opp("cantos")}",
                        fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp)
                    )
                    val extra = ArrayList<String>()
                    if (opp("formacao") != NI) extra.add("formação do rival ${opp("formacao")}")
                    if (j.optString("mom").isNotBlank()) extra.add("homem do jogo ${j.optString("mom")}")
                    if (cnt > 0) extra.add("nota média do seu time ${"%.1f".format(sum.toDouble() / cnt)}")
                    if (extra.isNotEmpty()) Text(extra.joinToString(" • "), fontSize = 11.sp, color = C.MUTED)
                    val adv = j.optString("advice")
                    if (adv.isNotBlank()) Text("“$adv”", fontSize = 11.sp, color = C.GOLD, modifier = Modifier.padding(top = 2.dp))
                }
            }
            Text("A IA usa essas estatísticas (posse, remates, faltas) para ajustar tática e disciplina.", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        }
    }
    // Resultados do campeonato (lidos do calendário): é daqui que a IA aprende.
    val played = d.matches.filter { it.result != null && it.round != null }.sortedBy { it.round }
    if (played.isNotEmpty()) {
        val gf = played.fold(0) { acc, m -> acc + (m.scoreMine ?: 0) }
        val ga = played.fold(0) { acc, m -> acc + (m.scoreOpp ?: 0) }
        Panel {
            Text("Resultados lidos do calendário", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(Modifier.padding(top = 6.dp)) {
                CountPill("V ${played.count { it.result == "V" }}", C.WIN)
                CountPill("E ${played.count { it.result == "E" }}", C.DRAW)
                CountPill("D ${played.count { it.result == "D" }}", C.LOSS)
                CountPill("gols $gf-$ga", C.SURFACE2)
            }
            Text(
                "Últimos: " + played.takeLast(8).joinToString("  ") { "J${it.round} ${it.result}" },
                fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
    Panel {
        Text("O que o app aprendeu com as suas táticas", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        if (rows.isEmpty()) {
            Text("Ainda sem histórico. Gere a tática antes de cada jogo: quando o resultado aparecer no calendário (ou você informar), ele entra aqui e passa a pesar na escolha da formação e do estilo.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
        val groups = listOf(
            "Por formação" to Learning.stats(hist),
            "Por estilo de jogo" to Learning.byStyle(hist),
            "Por contexto" to Learning.byContext(hist)
        )
        for ((title, list) in groups) {
            if (list.isEmpty()) continue
            Text(title, fontSize = 12.sp, color = C.MUTED, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            for (st in list) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(st.formation, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(120.dp))
                    CountPill("${st.v}V", C.WIN)
                    CountPill("${st.e}E", C.DRAW)
                    CountPill("${st.d}D", C.LOSS)
                    Text("${st.games} jogo(s)", fontSize = 11.sp, color = C.MUTED)
                }
            }
        }
    }
    if (rows.isNotEmpty()) {
        Panel {
            Text("Táticas usadas e resultado", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (r in rows.take(12)) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = r.result == null) { resultFor = r }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ResultBadge(r.result)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "J${r.round} • ${r.formation} • ${r.style} vs ${r.rival}" +
                            (if (r.score.isNotBlank()) " • ${r.score}" else " • toque para informar o resultado"),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
    val rf = resultFor
    if (rf != null) ResultDialog(slot, rf.kind, "J${rf.round} • ${rf.formation} vs ${rf.rival}") { resultFor = null }
    if (d.learning.isNotEmpty()) {
        Title("Eventos de leitura")
        Panel {
            for (l in d.learning) {
                Text("${fmtTime(l.at)} • ${l.kind}: ${l.text}", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun SettingsTab() {
    val ctx = LocalContext.current
    val tick = rememberTick(2000L)
    var gKey by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_KEY, "")) }
    var gModel by remember { mutableStateOf(Settings.get(ctx, Settings.GEMINI_MODEL, Settings.DEFAULT_GEMINI_MODEL)) }
    var clKey by remember { mutableStateOf(Settings.get(ctx, Settings.CLAUDE_KEY, "")) }
    var clModel by remember { mutableStateOf(Settings.get(ctx, Settings.CLAUDE_MODEL, Settings.DEFAULT_CLAUDE_MODEL)) }
    var cKey by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_KEY, "")) }
    var cBase by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_BASE, Settings.DEFAULT_COMPAT_BASE)) }
    var cModel by remember { mutableStateOf(Settings.get(ctx, Settings.COMPAT_MODEL, Settings.DEFAULT_COMPAT_MODEL)) }
    var cap by remember { mutableStateOf(Settings.get(ctx, Settings.DAILY_CAP, "80")) }
    var pref by remember { mutableStateOf(Settings.get(ctx, Settings.PREFERRED, "gemini")) }
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
            Text("IA para tática, mercado e leitura do relatório", fontWeight = FontWeight.Bold)
            Text("Gemini (gratuito) é o padrão. Claude e Groq/xAI são opcionais.", fontSize = 12.sp, color = C.MUTED)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((id, name) in listOf("gemini" to "Gemini", "claude" to "Claude", "compat" to "Groq/xAI")) {
                    val pick = { pref = id; Settings.put(ctx, Settings.PREFERRED, id) }
                    if (pref == id) Button(onClick = pick, modifier = Modifier.weight(1f)) { Text(name, fontSize = 12.sp) }
                    else OutlinedButton(onClick = pick, modifier = Modifier.weight(1f)) { Text(name, fontSize = 12.sp) }
                }
            }
            OutlinedTextField(gKey, { gKey = it }, label = { Text("Chave Google AI Studio (Gemini)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(gModel, { gModel = it }, label = { Text("Modelo Gemini (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clKey, { clKey = it }, label = { Text("Chave Claude (Console da Anthropic)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(clModel, { clModel = it }, label = { Text("Modelo Claude") }, modifier = Modifier.fillMaxWidth())
            Text(
                "A assinatura do Claude (Pro/Max) não inclui a API: para usar o Claude aqui é preciso uma chave e créditos no Console da Anthropic, com cobrança separada.",
                fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp)
            )
            OutlinedTextField(cKey, { cKey = it }, label = { Text("Chave Groq/xAI (formato OpenAI)") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cBase, { cBase = it }, label = { Text("URL base (Groq ou xAI)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cModel, { cModel = it }, label = { Text("Modelo (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cap, { cap = it }, label = { Text("Teto diário de chamadas do Gemini") }, modifier = Modifier.fillMaxWidth())
            KV("Chamadas ao Gemini hoje", AiClient.usedToday(ctx).toString())
            Button(
                onClick = {
                    Settings.put(ctx, Settings.GEMINI_KEY, gKey)
                    Settings.put(ctx, Settings.GEMINI_MODEL, gModel.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
                    Settings.put(ctx, Settings.CLAUDE_KEY, clKey)
                    Settings.put(ctx, Settings.CLAUDE_MODEL, clModel.ifBlank { Settings.DEFAULT_CLAUDE_MODEL })
                    Settings.put(ctx, Settings.COMPAT_KEY, cKey)
                    Settings.put(ctx, Settings.COMPAT_BASE, cBase.ifBlank { Settings.DEFAULT_COMPAT_BASE })
                    Settings.put(ctx, Settings.COMPAT_MODEL, cModel.ifBlank { Settings.DEFAULT_COMPAT_MODEL })
                    Settings.put(ctx, Settings.DAILY_CAP, cap.ifBlank { "80" })
                    saved = "Salvo."
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Salvar") }
            if (saved.isNotBlank()) Text(saved, color = C.OK)
            OutlinedButton(
                onClick = { startAiTest(ctx) },
                enabled = !AiTest.running,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text(if (AiTest.running) "Testando…" else "Testar a IA agora (mostra o tempo)") }
            if (AiTest.result.isNotBlank()) Text(AiTest.result, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}
