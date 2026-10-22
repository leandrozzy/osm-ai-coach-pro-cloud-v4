package com.osmaicoach.collector

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.rotate
import androidx.compose.animation.animateContentSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.conflate
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
import kotlin.math.roundToInt
import kotlin.math.sqrt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// Cores do OSM: azul-marinho de estádio à noite, azul vivo dos botões e o amarelo dos destaques.
private object C {
    val BG = Color(0xFF0A1734)
    val SURFACE = Color(0xFF0F2650)
    val SURFACE2 = Color(0xFF1A3A73)
    val PRIMARY = Color(0xFF1E9BFF)
    val GOLD = Color(0xFFFFC21A)
    val OK = Color(0xFF3DDC84)
    val BAD = Color(0xFFFF5C5C)
    val WARN = Color(0xFFFFB74D)
    val MUTED = Color(0xFF8FB0D9)
    val WIN = Color(0xFF2EA043)
    val DRAW = Color(0xFFF2C94C)
    val LOSS = Color(0xFFE5484D)
}

class MainActivity : ComponentActivity() {
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(applicationContext) }
    }

    private fun handleIntent(i: Intent?) {
        val slot = i?.getIntExtra("slot", 0) ?: 0
        if (slot in 1..4) {
            Route.tab = i?.getIntExtra("tab", 4) ?: 4
            Route.slot = slot
            Route.nonce++
        }
    }

    override fun onResume() {
        super.onResume()
        AppVisible.resumed = true
        CaptureService.instance?.refreshOverlay()
    }

    override fun onPause() {
        AppVisible.resumed = false
        CaptureService.instance?.refreshOverlay()
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Health.install(applicationContext)
        Diag.init(applicationContext)
        Settings.migrate(applicationContext)
        AppScope.scope.launch { Repo(applicationContext).purgeLegacy() }
        Notifier.ensureChannels(applicationContext)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(applicationContext) }
        // Ao recriar a tela (girar o celular) o intent antigo continua aqui: não reabre o slot da notificação.
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val font = remember { osmFontFamily() }
            val base = androidx.compose.material3.Typography()
            fun f(t: androidx.compose.ui.text.TextStyle) = t.copy(fontFamily = font)
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = C.PRIMARY, secondary = C.GOLD, background = C.BG, surface = C.SURFACE,
                    onSurface = Color.White, onBackground = Color.White, surfaceVariant = C.SURFACE2,
                    onPrimary = Color.White, secondaryContainer = C.SURFACE2,
                    onSecondary = Color(0xFF0B1C3E), onSecondaryContainer = Color.White, onSurfaceVariant = Color(0xFFC9D8F0),
                    primaryContainer = C.SURFACE2, onPrimaryContainer = Color.White, outline = Color(0xFF3F6AA8)
                ),
                typography = base.copy(
                    displayLarge = f(base.displayLarge), displayMedium = f(base.displayMedium), displaySmall = f(base.displaySmall),
                    headlineLarge = f(base.headlineLarge), headlineMedium = f(base.headlineMedium), headlineSmall = f(base.headlineSmall),
                    titleLarge = f(base.titleLarge), titleMedium = f(base.titleMedium), titleSmall = f(base.titleSmall),
                    bodyLarge = f(base.bodyLarge), bodyMedium = f(base.bodyMedium), bodySmall = f(base.bodySmall),
                    labelLarge = f(base.labelLarge), labelMedium = f(base.labelMedium), labelSmall = f(base.labelSmall)
                )
            ) {
                androidx.compose.runtime.CompositionLocalProvider(
                    androidx.compose.material3.LocalTextStyle provides f(base.bodyLarge)
                ) {
                    androidx.compose.runtime.CompositionLocalProvider(
                        androidx.compose.material3.LocalContentColor provides Color.White
                    ) {
                        OsmAppBackground(Modifier.fillMaxSize()) { App() }
                    }
                }
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

/**
 * Bateria sem restrição: pede direto ao Android ("Permitir que o app rode em segundo plano?") para ESTE app.
 * A lista geral de otimização esconde o app em muitos celulares. Se o pedido não abrir, vai para Info do app
 * (lá: Bateria → Sem restrição).
 */
@android.annotation.SuppressLint("BatteryLife")
private fun openBatterySettings(ctx: Context) {
    if (batteryUnrestricted(ctx)) {
        openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
        return
    }
    val req = Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:" + ctx.packageName))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(req)
    } catch (e: Exception) {
        openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true)
    }
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
private fun EditSection(slot: Int, d: SlotData, title: String, rows: List<EditRow>, icon: String = "✏️", startOpen: Boolean = false) {
    var editing by remember { mutableStateOf<EditRow?>(null) }
    val missing = rows.count { r ->
        r.key != null && (r.display ?: fv(d, r.key)) == NI && !(r.key == K.RIVAL_NICK && fv(d, K.RIVAL_HUMAN) == "Não")
    }
    Fold(icon, title, if (missing > 0) "faltam $missing" else "✔", startOpen) {
        for (r in rows) {
            val raw = r.display ?: (if (r.key != null) fv(d, r.key) else NI)
            val v = when {
                r.key == K.RIVAL_HUMAN && raw == "Sim" -> "Humano"
                r.key == K.RIVAL_HUMAN && raw == "Não" -> "CPU"
                r.key == K.RIVAL_NICK && raw == NI && fv(d, K.RIVAL_HUMAN) == "Não" -> "— (CPU não tem apelido)"
                (d.fields[r.key ?: ""]?.conf ?: 1.0) < 0.6 && raw != NI && r.key?.endsWith(".mid") == true -> "≈$raw"
                else -> raw
            }
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
        Text("✎ toque num valor para corrigir", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
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
    val tickNow by rememberUpdatedState(tick)
    val load by rememberUpdatedState(loader)
    // Cada leitura termina antes da próxima começar: o relógio não cancela uma leitura lenta
    // (antes, com leitura > 1 s, a aba Hoje ficava sem os slots).
    LaunchedEffect(key) {
        snapshotFlow { tickNow }.conflate().collect {
            try {
                state = withContext(Dispatchers.IO) { load() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.lastError = "Tela: " + (e.message ?: e.javaClass.simpleName)
            }
        }
    }
    return state
}

// ------------------------------------------------------------------ componentes visuais

@Composable
private fun Title(text: String) {
    Row(Modifier.padding(top = 16.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 4.dp, height = 20.dp).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(OsmColors.YELLOW, Color(0xFFFF8A00)))))
        Spacer(Modifier.width(10.dp))
        Text(text, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
    }
}

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
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun Pill(text: String, ok: Boolean? = null) {
    val c = when (ok) {
        true -> Color(0xFF22C55E)
        false -> Color(0xFFEF4444)
        null -> Color(0xFF5FA8FF)
    }
    Box(
        Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(50))
            .background(Brush.verticalGradient(listOf(c.copy(alpha = 0.32f), c.copy(alpha = 0.18f))))
            .border(1.dp, c.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 3.dp)
    ) {
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
    val f by androidx.compose.animation.core.animateFloatAsState(pct.coerceIn(0, 100) / 100f, androidx.compose.animation.core.tween(800), label = "bar")
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50)).background(Color(0x33000000))) {
        Box(
            Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(50))
                .background(Brush.horizontalGradient(listOf(Color(0xFF1E6BFF), Color(0xFF3DB2FF), Color(0xFF6EE7FF))))
        )
    }
}

// ------------------------------------------------------------------ desenhos e blocos visuais (menos texto)

/** Explicação recolhida: só um "ⓘ" pequeno; o texto aparece ao tocar. */
@Composable
private fun Hint(text: String, label: String = "como funciona") {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = !open }.padding(horizontal = 2.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(16.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
                Text("i", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = C.PRIMARY)
            }
            Text(" " + label + (if (open) " ▲" else " ▼"), fontSize = 11.sp, color = C.MUTED)
        }
        if (open) Text(text, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/** Painel que abre e fecha pelo título (detalhes ficam guardados até o usuário querer ver). */
@Composable
private fun Fold(icon: String, title: String, badge: String? = null, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Column(Modifier.animateContentSize()) {
            Row(
                Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                        .background(Brush.linearGradient(listOf(Color(0xFF2B5BB8), Color(0xFF14306E))))
                        .border(1.dp, Color(0x4072B8FF), RoundedCornerShape(11.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(icon, fontSize = 16.sp) }
                Spacer(Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (badge != null) {
                    Box(Modifier.padding(end = 8.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2).padding(horizontal = 8.dp, vertical = 2.dp)) {
                        Text(badge, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = C.GOLD, maxLines = 1)
                    }
                }
                val rot by androidx.compose.animation.core.animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Text("▼", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.rotate(rot))
            }
            if (open) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) { content() }
        }
    }
}

/** Número grande com ícone e rótulo curto. */
@Composable
private fun StatTile(icon: String, value: String, label: String, color: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp))
            .background(Brush.verticalGradient(listOf(color.copy(alpha = 0.26f), color.copy(alpha = 0.08f))))
            .border(1.dp, Brush.verticalGradient(listOf(color.copy(alpha = 0.6f), color.copy(alpha = 0.12f))), RoundedCornerShape(14.dp))
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 18.sp)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, fontSize = 10.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Anel de progresso com o texto no meio. */
@Composable
private fun Ring(pct: Int, size: Dp, color: Color, center: String, sub: String? = null) {
    val ringAnim by androidx.compose.animation.core.animateFloatAsState(pct.coerceIn(0, 100) / 100f, androidx.compose.animation.core.tween(900), label = "ring")
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val sw = this.size.width * 0.11f
            val arc = Size(this.size.width - sw, this.size.height - sw)
            drawArc(C.SURFACE2, -90f, 360f, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw))
            drawArc(color.copy(alpha = 0.22f), -90f, 360f * ringAnim, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw * 2f, cap = StrokeCap.Round))
            drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.7f), color, Color.White.copy(alpha = 0.9f), color)),
                -90f, 360f * ringAnim, false, topLeft = Offset(sw / 2f, sw / 2f), size = arc, style = Stroke(sw, cap = StrokeCap.Round)
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontSize = (size.value * (if (center.length >= 4) 0.2f else 0.24f)).sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            if (sub != null) Text(sub, fontSize = (size.value * 0.11f).coerceAtLeast(7f).sp, color = C.MUTED, maxLines = 1)
        }
    }
}

/** Previsão compacta: vitória em destaque e a barra vitória/empate/derrota com os três números. */
@Composable
private fun WinMini(p: WinModel.Prob) {
    Column(Modifier.width(116.dp), horizontalAlignment = Alignment.End) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${p.win}%", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(" vitória", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 2.dp))
        }
        Box(Modifier.padding(top = 2.dp)) { VerdictChip(p) }
        Box(Modifier.padding(top = 3.dp)) { WinBar(p, 7.dp) }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("V${p.win}", fontSize = 9.sp, color = C.WIN, fontWeight = FontWeight.Bold)
            Text("E${p.draw}", fontSize = 9.sp, color = C.DRAW, fontWeight = FontWeight.Bold)
            Text("D${p.loss}", fontSize = 9.sp, color = C.LOSS, fontWeight = FontWeight.Bold)
        }
    }
}

private val SHIELD_COLORS = listOf(
    Color(0xFFE5484D), Color(0xFF9B6BFF), Color(0xFF2EA043), Color(0xFFFF8A3D), Color(0xFF00B4D8),
    Color(0xFFD6409F), Color(0xFF8D6E63), Color(0xFF607D8B)
)

private fun initials(name: String): String {
    val w = name.split(Regex("[\\s.\\-]+")).filter { it.isNotBlank() && it.first().isLetterOrDigit() }
    return when {
        w.isEmpty() -> "?"
        w.size == 1 -> w[0].take(2).uppercase()
        else -> (w[0].take(1) + w[1].take(1)).uppercase()
    }
}

/** Escudo desenhado para o rival (cor fixa pelo nome, com as iniciais). */
@Composable
private fun RivalShield(name: String?, size: Dp) {
    val col = if (name == null) C.SURFACE2 else SHIELD_COLORS[(name.hashCode() and 0x7fffffff) % SHIELD_COLORS.size]
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val p = androidx.compose.ui.graphics.Path()
            p.moveTo(w * 0.5f, h * 0.02f)
            p.lineTo(w * 0.95f, h * 0.16f)
            p.lineTo(w * 0.95f, h * 0.52f)
            p.quadraticBezierTo(w * 0.93f, h * 0.84f, w * 0.5f, h * 0.98f)
            p.quadraticBezierTo(w * 0.07f, h * 0.84f, w * 0.05f, h * 0.52f)
            p.lineTo(w * 0.05f, h * 0.16f)
            p.close()
            drawPath(p, Brush.verticalGradient(listOf(col, col.copy(red = col.red * 0.6f, green = col.green * 0.6f, blue = col.blue * 0.6f))))
            drawPath(p, Color(0x66FFFFFF), style = Stroke(2f * density))
            drawLine(Color(0x33FFFFFF), Offset(w * 0.5f, h * 0.08f), Offset(w * 0.5f, h * 0.9f), 3f * density)
        }
        Text(if (name == null) "?" else initials(name), fontSize = (size.value * 0.3f).sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
    }
}

private val REF_COLORS = listOf(Color(0xFF2F8CFF), Color(0xFF3DDC84), Color(0xFFFFD43B), Color(0xFFFF9F1A), Color(0xFFE5484D))

/**
 * Termômetro do árbitro igual ao do jogo, 5 cores (azul, verde, amarelo, laranja, vermelho), com o nível lido em
 * destaque e o desarme que ele pede.
 */
@Composable
private fun RefereeMeter(value: String?, compact: Boolean = false) {
    val lvl = Referee.level(value)
    val name = Referee.norm(value)
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🌡", fontSize = if (compact) 13.sp else 16.sp)
            Text(" Árbitro ", fontSize = 12.sp, color = C.MUTED)
            Text(name ?: "não lido", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = if (lvl == null) C.WARN else REF_COLORS[lvl])
            Spacer(Modifier.weight(1f))
            if (lvl != null) {
                Box(Modifier.clip(RoundedCornerShape(50)).background(REF_COLORS[lvl].copy(alpha = 0.22f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("desarme " + Referee.TACKLES[lvl], fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (i in 0..4) {
                val on = lvl == i
                Box(
                    Modifier.weight(1f).height(if (compact) 10.dp else 14.dp).clip(RoundedCornerShape(50))
                        .background(REF_COLORS[i].copy(alpha = if (lvl == null || on) 1f else 0.22f))
                        .then(if (on) Modifier.border(2.dp, Color.White, RoundedCornerShape(50)) else Modifier)
                )
            }
        }
        if (!compact) Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
            for ((i, t) in Referee.LEVELS.withIndex()) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.replace("Muito ", "Muito\n"), fontSize = 9.sp, color = if (lvl == i) Color.White else C.MUTED, textAlign = TextAlign.Center, fontWeight = if (lvl == i) FontWeight.Bold else FontWeight.Normal, lineHeight = 10.sp)
                    Text(Referee.TACKLES[i], fontSize = 8.sp, color = if (lvl == i) REF_COLORS[i] else C.MUTED.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Duelo de um número meu contra o do rival: barra dividida azul x vermelho. */
@Composable
private fun Duel(label: String, mine: String?, rival: String?) {
    val a = mine?.let { digits(it).toIntOrNull() }
    val b = rival?.let { digits(it).toIntOrNull() }
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(a?.toString() ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = if (a != null && b != null && a >= b) C.PRIMARY else Color.White, modifier = Modifier.width(34.dp), textAlign = TextAlign.Start)
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 10.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            val fa = if (a != null && b != null && a + b > 0) a.toFloat() / (a + b) else 0.5f
            Row(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(50))) {
                Box(Modifier.weight(fa.coerceIn(0.05f, 0.95f)).fillMaxSize().background(if (a == null) C.SURFACE2 else C.PRIMARY))
                Box(Modifier.width(2.dp).fillMaxSize().background(C.BG))
                Box(Modifier.weight((1f - fa).coerceIn(0.05f, 0.95f)).fillMaxSize().background(if (b == null) C.SURFACE2 else C.LOSS))
            }
        }
        Text(b?.toString() ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = if (a != null && b != null && b > a) C.LOSS else Color.White, modifier = Modifier.width(34.dp), textAlign = TextAlign.End)
    }
}

/** Cabeçalho do próximo jogo: escudos frente a frente, quando, onde, quem e as forças. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MatchupHero(slot: Int, d: SlotData) {
    val rival = known(d.fields, K.RIVAL_TEAM)
    val at = known(d.fields, K.MATCH_AT)?.toLongOrNull()
    val home = known(d.fields, K.HOME)
    val human = known(d.fields, K.RIVAL_HUMAN)
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E2A5A), Color(0xFF16213D), Color(0xFF0B1C3E))))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Crest(slot, 60.dp)
                    Text(known(d.fields, K.TEAM) ?: "Meu time", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "VS", fontWeight = FontWeight.ExtraBold, fontSize = 30.sp, color = OsmColors.YELLOW,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                    if (at != null) Text(countdown(at).removePrefix("em "), fontSize = 10.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp), maxLines = 1)
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    RivalShield(rival, 60.dp)
                    Text(rival ?: "rival não lido", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp), color = if (rival == null) C.WARN else Color.White)
                }
            }
            FlowRow(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                if (at != null) Pill("📅 " + fmtTime(at))
                if (home != null) Pill(if (home == "Casa") "🏠 em casa" else "✈ fora")
                if (human != null) Pill(if (human == "Sim") "👤 humano" else "🖥 CPU", human == "Sim")
            }
            Spacer(Modifier.height(6.dp))
            Duel("FORÇA", known(d.fields, K.MY_STRENGTH), known(d.fields, K.RIVAL_STRENGTH))
            val myB = known(d.fields, K.MY_BONUS)
            val rvB = known(d.fields, K.RIVAL_LOGIN_BONUS)
            if (myB != null || rvB != null) Duel("BÔNUS %", myB, rvB)
            Spacer(Modifier.height(6.dp))
            RefereeMeter(known(d.fields, K.REFEREE), compact = true)
        }
    }
}

/** Força por setor: meus números contra os do rival. */
@Composable
private fun SectorDuels(d: SlotData) {
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⚔ Setores", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            LegendDot(C.PRIMARY, "eu")
            LegendDot(C.LOSS, "rival")
        }
        Duel("GOL", known(d.fields, K.MY_GOL), known(d.fields, K.RIVAL_GOL))
        Duel("DEF", known(d.fields, K.MY_DEF), known(d.fields, K.RIVAL_DEF))
        Duel("MEI", known(d.fields, K.MY_MID), known(d.fields, K.RIVAL_MID))
        Duel("ATA", known(d.fields, K.MY_ATK), known(d.fields, K.RIVAL_ATK))
    }
}

/** Os 5 treinadores do OSM: um por posição + o universal, com quem está em cada um. */
@Composable
private fun TrainingBoardView(seats: List<TrainBoard.Seat>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (s in seats) {
            val col = if (s.role == "UNI") Color(0xFF9B6BFF) else catColor(s.role)
            val (icon, stateText, stateCol) = when (s.state) {
                "active" -> Triple("⏱", "treinando", C.OK)
                "done" -> Triple("✅", "pegar", C.GOLD)
                "empty" -> Triple("＋", "livre", C.WARN)
                else -> Triple("?", "não lido", C.MUTED)
            }
            Column(
                Modifier.weight(1f).height(118.dp).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2)
                    .border(1.dp, if (s.state == "empty") C.WARN.copy(alpha = 0.6f) else Color(0x14FFFFFF), RoundedCornerShape(14.dp))
                    .graphicsLayer { alpha = if (s.fresh || s.state == "unknown") 1f else 0.55f },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(Modifier.fillMaxWidth().background(col.copy(alpha = 0.85f)).padding(vertical = 4.dp), contentAlignment = Alignment.Center) {
                    Text(if (s.role == "UNI") "★ UNI" else s.role, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF0B1220))
                }
                Text(icon, fontSize = 20.sp, color = stateCol, modifier = Modifier.padding(top = 6.dp))
                Text(
                    s.name ?: "—", fontSize = 9.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 3.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(stateText, fontSize = 9.sp, color = stateCol, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 5.dp))
            }
        }
    }
}

/** Medalha desenhada com a posição final. */
@Composable
private fun Medal(rank: Int?, size: Dp) {
    val col = when (rank) {
        1 -> Color(0xFFFFC83D)
        2 -> Color(0xFFC0C8D4)
        3 -> Color(0xFFCD7F32)
        else -> Color(0xFF3D8BFF)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            // fitas
            quad(Offset(w * 0.30f, 0f), Offset(w * 0.45f, 0f), Offset(w * 0.58f, h * 0.40f), Offset(w * 0.43f, h * 0.40f), Color(0xFFE5484D))
            quad(Offset(w * 0.55f, 0f), Offset(w * 0.70f, 0f), Offset(w * 0.57f, h * 0.40f), Offset(w * 0.42f, h * 0.40f), Color(0xFF3D8BFF))
            val c = Offset(w / 2f, h * 0.62f)
            val r = w * 0.36f
            drawCircle(col.copy(red = col.red * 0.6f, green = col.green * 0.6f, blue = col.blue * 0.6f), radius = r, center = c)
            drawCircle(col, radius = r * 0.84f, center = c)
            drawCircle(Color(0x55FFFFFF), radius = r * 0.84f, center = c, style = Stroke(1.5f * density))
        }
        Text(
            if (rank != null) "${rank}º" else "?", fontSize = (size.value * 0.2f).sp, fontWeight = FontWeight.ExtraBold,
            color = Color(0xFF0B1220), modifier = Modifier.padding(top = size * 0.24f)
        )
    }
}

/**
 * Fim da competição: posição final, campanha (V/E/D, pontos de quantos possíveis, gols), meta da temporada, valor do
 * elenco, campeão e a fala mais importante da direção.
 */
@Composable
private fun SeasonEndCard(d: SlotData) {
    val info = SeasonCard.from(d.season, d.board, d.fields)
    val rec = SeasonCard.record(d.matches, known(d.fields, K.ROUND_TOTAL)?.toIntOrNull())
    val goal = known(d.fields, K.MY_OBJECTIVE)?.toIntOrNull()
    val rank = info?.rank
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF3A2A06), Color(0xFF1A1F33), Color(0xFF0E2A5A))))
            .border(1.dp, Color(0x55FFC83D), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Medal(rank, 76.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("🏁 COMPETIÇÃO ENCERRADA", fontSize = 11.sp, color = C.GOLD, fontWeight = FontWeight.Bold)
                    Text(if (rank != null) "Terminou em ${rank}º lugar" else "Aguardando a próxima", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    if (info?.champion != null) Text("🏆 Campeão: ${info.champion}", fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // campanha
            if (rec != null || info?.points != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (rec != null) {
                        Column(
                            Modifier.weight(1.3f).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFFFFF)).padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("CAMPANHA", fontSize = 9.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                            Row(Modifier.padding(top = 4.dp)) {
                                CountPill("${rec.v}V", C.WIN)
                                CountPill("${rec.e}E", C.DRAW)
                                CountPill("${rec.d}D", C.LOSS)
                            }
                            Text("${rec.games} jogos", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 3.dp))
                        }
                    }
                    val pts = info?.points ?: rec?.let { it.v * 3 + it.e }
                    if (pts != null) {
                        StatTile("⭐", if (rec != null) "$pts/${rec.maxPoints}" else "$pts", "pontos feitos", C.GOLD, Modifier.weight(1f))
                    }
                    val gf = rec?.gf
                    val ga = rec?.ga
                    if (gf != null && ga != null) StatTile("⚽", "$gf×$ga", "gols pró×contra", if (gf >= ga) C.OK else C.LOSS, Modifier.weight(1f))
                    else if (info?.goals != null) StatTile("⚽", info.goals, "gols", C.OK, Modifier.weight(1f))
                }
            }
            // meta e valor, em frases curtas
            if (goal != null && rank != null) {
                val ok = rank <= goal
                Text(
                    if (ok) "🎯 Meta top $goal: cumprida ✔" else "🎯 Meta top $goal: ficou ${rank - goal} posição(ões) abaixo",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = if (ok) C.OK else C.WARN, modifier = Modifier.padding(top = 10.dp)
                )
            }
            if (info?.value != null) {
                Text(
                    "💰 Elenco vale ${info.value}" + (if (info.valueRank != null) " — ${info.valueRank}º mais valioso da liga" else ""),
                    fontSize = 12.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp)
                )
                if (info.valueRank != null && rank != null && rank > info.valueRank + 1) {
                    Text("Ficou abaixo do que o elenco vale: dá para render mais com tática e treino.", fontSize = 11.sp, color = C.MUTED)
                }
            }
            val said = info?.board
            if (said != null) {
                val mood = SeasonCard.mood(said)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Top) {
                    Text(if (mood < 0) "😠" else if (mood > 0) "😀" else "😐", fontSize = 28.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                            .background(if (mood < 0) Color(0x33E5484D) else Color(0x22FFFFFF)).padding(10.dp)
                    ) {
                        Text("Direção", fontSize = 10.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                        Text("“$said”", fontSize = 12.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (info.fired) Box(Modifier.padding(top = 8.dp)) { Pill("⚠ risco de demissão: o slot pode mudar de clube", false) }
            }
            Text(
                "Sem próximo jogo. Até a nova competição: use o Diretor deste slot para treinos, vendas e compras.",
                fontSize = 11.sp, color = Color(0xB3FFFFFF), modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/**
 * Etapas do jogo no slot como uma barra de 4 trechos: verde = feito, cor forte = o que fazer agora,
 * cinza = depois. Dados incompletos ficam em amarelo com a % (não travam a tática). Embaixo, uma frase só.
 */
@Composable
private fun Steps(s: SlotSummary) {
    val dataOk = s.missing == 0
    val done = listOf(dataOk, s.tacticReady, s.needsResult, false)
    // o "agora" é a primeira etapa de ação pendente (dados não travam: dá para gerar a tática com o que foi lido)
    val now = when {
        s.needsResult -> 3
        !s.tacticReady -> 1
        else -> 2
    }
    val labels = listOf("📋 dados", "⚡ tática", "⚽ jogo", "📊 resultado")
    val nowCol = when (now) { 1 -> C.WARN; 3 -> C.GOLD; else -> C.PRIMARY }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        for (i in 0..3) {
            val col = when {
                i == 0 && !dataOk -> C.WARN.copy(alpha = 0.55f)
                done[i] -> C.OK
                i == now -> nowCol
                else -> C.SURFACE2
            }
            Column(Modifier.weight(1f).padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().height(if (i == now) 8.dp else 6.dp).clip(RoundedCornerShape(50)).background(col))
                val text = when {
                    i == 0 && !dataOk -> "📋 ${s.pct}%"
                    done[i] -> "✓ " + labels[i].substringAfter(' ')
                    else -> labels[i]
                }
                Text(
                    text, fontSize = 10.sp, maxLines = 1,
                    fontWeight = if (i == now) FontWeight.Bold else FontWeight.Normal,
                    color = when { i == now -> nowCol; done[i] -> C.OK; i == 0 -> C.WARN; else -> C.MUTED },
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
    if (s.rivalChanged.isNotEmpty() && now != 3) {
        Text(
            "⚠ O rival mudou (" + s.rivalChanged.joinToString(", ") { it.label.removeSuffix(" do rival").lowercase() } + "): gere uma nova tática",
            fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFB020), modifier = Modifier.padding(top = 6.dp)
        )
        return
    }
    val msg = when (now) {
        3 -> "👉 Agora: registrar o resultado do jogo"
        1 -> "👉 Agora: gerar a tática" + if (!dataOk) " · faltam ${s.missing} dado${if (s.missing > 1) "s" else ""}" else ""
        else -> "✓ Tática pronta: aplique no OSM antes do jogo"
    }
    Text(msg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = nowCol, modifier = Modifier.padding(top = 6.dp))
}

/** Sequência de resultados (bolinhas V/E/D). */
@Composable
private fun FormGuide(results: List<String>) {
    Row(Modifier.padding(top = 6.dp)) {
        for (r in results) Box(Modifier.padding(end = 4.dp)) { ResultBadge(r) }
    }
}

/** Tela vazia com desenho grande em vez de parágrafo. */
@Composable
private fun EmptyState(icon: String, title: String, hint: String? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2), contentAlignment = Alignment.Center) {
            Text(icon, fontSize = 38.sp)
        }
        Text(title, fontWeight = FontWeight.Bold, fontSize = 15.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        if (hint != null) Hint(hint, "o que fazer")
    }
}

/** Linha de status com luz verde/amarela. */
@Composable
private fun StatusLight(ok: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(if (ok) C.OK else C.WARN))
        Text("  $text", fontSize = 13.sp, color = if (ok) Color.White else C.WARN)
    }
}

/** Alerta colorido pelo ícone do começo da frase. */
@Composable
private fun AlertCard(text: String, onClick: (() -> Unit)? = null) {
    val col = when {
        text.startsWith("🔴") -> C.LOSS
        text.startsWith("🟢") -> C.OK
        text.startsWith("🔵") -> C.PRIMARY
        text.startsWith("🏁") -> C.GOLD
        text.startsWith("📊") -> C.GOLD
        text.startsWith("⚠") -> C.WARN
        else -> C.MUTED
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.12f))
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .drawBehind { drawRect(col, size = Size(4.dp.toPx(), size.height)) }.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) { Text(text, fontSize = 12.sp, modifier = Modifier.weight(1f)) }
}

/** Passo a passo desenhado de como o app funciona. */
@Composable
private fun HowItWorks() {
    val steps = listOf("▶" to "Abra o OSM\npelo app", "👆" to "Navegue\nnas telas", "🧠" to "O app lê\ne calcula", "⚡" to "Use a\ntática")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        for ((i, s) in steps.withIndex()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(46.dp).clip(RoundedCornerShape(50)).background(Brush.linearGradient(listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8)))),
                    contentAlignment = Alignment.Center
                ) { Text(s.first, fontSize = 20.sp, color = Color.White) }
                Text(s.second, fontSize = 10.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            if (i < steps.size - 1) Text("›", fontSize = 22.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

// ------------------------------------------------------------------ dados de tela

/** Cartão de alerta: o rival mudou depois da tática gerada — o que mudou, por quê e o botão para gerar de novo. */
@Composable
private fun RivalChangedCard(changes: List<RivalWatch.Change>, onGenerate: () -> Unit) {
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(
        initialValue = 0.35f, targetValue = 0.9f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "glow"
    )
    Column(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF4A2A06), Color(0xFF2A1A08))))
            .border(1.5.dp, Color(0xFFFFB020).copy(alpha = glow), RoundedCornerShape(16.dp)).padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color(0xFFFFB020)), contentAlignment = Alignment.Center) {
                Text("!", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2A1A08))
            }
            Column(Modifier.padding(start = 12.dp)) {
                Text("O rival mudou a tática dele", fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFFFD27A))
                Text("Sua tática foi montada antes disso. Gere uma nova.", fontSize = 12.sp, color = Color(0xCCFFFFFF))
            }
        }
        for (c in changes) {
            Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(12.dp)).background(Color(0x33000000)).padding(10.dp)) {
                Text(c.label, fontSize = 11.sp, color = Color(0xFFFFB020), fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(c.from, fontSize = 14.sp, color = Color(0x99FFFFFF), textDecoration = androidx.compose.ui.text.style.TextDecoration.LineThrough)
                    Text("  →  ", fontSize = 14.sp, color = Color(0xFFFFB020), fontWeight = FontWeight.Bold)
                    Text(c.to, fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("Por quê: " + c.why, fontSize = 12.sp, color = Color(0xDDFFFFFF), modifier = Modifier.padding(top = 4.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(12.dp))
                .background(Brush.horizontalGradient(listOf(Color(0xFFFFC21A), Color(0xFFFF9500)))).clickable { onGenerate() }.padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) { Text("⚡ Gerar nova tática", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF2A1A08)) }
    }
}

/** Mudanças do rival depois da tática atual (só vale para a tática deste jogo). */
suspend fun rivalChangesOf(repo: Repo, slot: Int): List<RivalWatch.Change> {
    val t = repo.dao.plan(slot, "tactic") ?: return emptyList()
    val c = repo.dao.plan(slot, "rivalchange") ?: return emptyList()
    if (c.at < t.at) return emptyList()
    val tj = runCatching { JSONObject(t.json) }.getOrNull() ?: return emptyList()
    if (!Director.tacticValid(tj, t.at, repo.fieldMap(slot), System.currentTimeMillis())) return emptyList()
    return RivalWatch.fromJson(runCatching { JSONObject(c.json) }.getOrNull())
}

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
    val missing: Int = 0,
    /** O card do calendário (data e hora) já passou e o jogo ainda não tem placar: pede o resultado. */
    val needsResult: Boolean = false,
    val win: WinModel.Prob? = null,
    /** Campos do próximo jogo ainda não lidos (mesmo critério para todos os slots). */
    val missingNames: List<String> = emptyList(),
    /** Todas as rodadas da competição jogadas e nenhum jogo marcado: aguardando a próxima competição. */
    val ended: Boolean = false,
    /** Central do OSM: "vazio" (+ começar nova liga) ou "procurando" (batalha procurando adversário). */
    val state: String? = null,
    /** O rival mudou algo (formação, estilo, árbitro…) depois que a tática foi gerada. */
    val rivalChanged: List<RivalWatch.Change> = emptyList()
)

data class TodayData(val active: Boolean = false, val slots: List<SlotSummary> = emptyList(), val work: List<SlotWork> = emptyList(), val needs: List<Fresh.Need> = emptyList())

object TodayCache {
    @Volatile var last = TodayData()
    @Volatile var maintainedAt = 0L
}

data class SlotData(
    /** false enquanto o slot ainda está carregando (evita piscar "ainda não lido"). */
    val ready: Boolean = false,
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
    val rivalProfile: PlanEntity? = null,
    val win: WinModel.Prob? = null,
    val evidence: JSONObject? = null,
    /** Leituras que não batem entre telas (para conferir). */
    val conflicts: List<String> = emptyList(),
    /** Jogadores marcados fora do próximo jogo. */
    val unavailable: Set<String> = emptySet(),
    /** Fim da competição e mensagens da direção (mais nova primeiro). */
    val board: List<String> = emptyList(),
    /** Última leitura de cada treinador (tela de Treinamento). */
    val trainScreen: JSONObject? = null,
    /** Treino corrigido à mão (nameKey -> treinando?). */
    val trainOv: Map<String, Boolean> = emptyMap(),
    /** Evolução: placar da tática, livro de caixa, treino e semana. */
    val progress: Repo.ProgressData? = null,
    /** O rival mudou depois da tática gerada. */
    val rivalChanged: List<RivalWatch.Change> = emptyList(),
    /** Resumo do fim da competição (posição, pontos, campeão, valor). */
    val season: JSONObject? = null,
    /** O que os gols reais dos 4 slots ensinaram ao modelo. */
    val learned: Learned.Model? = null,
    /** Táticas mais prováveis do rival humano (pelo histórico do apelido). */
    val rivalPred: RivalPredict.View? = null
)

data class SessionRow(val s: SessionEntity, val counts: String, val unassigned: Int)

private suspend fun loadSlot(ctx: Context, slot: Int): SlotData {
    val repo = Repo(ctx)
    val dao = repo.dao
    try {
        repo.relinkResults(slot)
        repo.validateHuman(slot)
        repo.syncRound(slot)
        repo.syncTraining(slot)
    } catch (e: kotlinx.coroutines.CancellationException) {
        // saiu da tela antes de terminar: não é erro
        throw e
    } catch (e: Exception) {
        Diag.lastError = "Resultados: " + (e.message ?: e.javaClass.simpleName)
    }
    val f = repo.fieldMap(slot)
    val players = repo.playersOf(slot)
    val matches = dao.matchesOf(slot)
    val market = dao.snapshots("TRANSFER", slot).isNotEmpty()
    val comp = Completeness.compute(
        f, players.count { it.owner == "MY" }, matches.count { it.round != null }, market,
        f[K.ROUND_TOTAL]?.value?.toIntOrNull()
    )
    Director.resolveTacticLogs(repo, slot)
    val tplan = dao.plan(slot, "tactic")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } }
    return SlotData(
        ready = true,
        learned = runCatching { Learned.fit(Director.allLogs(repo)) }.getOrNull(),
        rivalPred = runCatching { RivalPredict.of(repo.rivalProfile(slot)?.json?.let { JSONObject(it) }) }.getOrNull(),
        fields = f, players = players, matches = dao.matchesOf(slot),
        tactic = dao.plan(slot, "tactic"), market = dao.plan(slot, "market"),
        learning = dao.learningOf(slot),
        sections = dao.lastBySection(slot).associate { it.type to it.c },
        completeness = comp,
        marketPlan = Director.marketPlan(repo, slot),
        marketNote = dao.plan(slot, "market")?.let { mp ->
            // comentário de outro elenco (jogadores já vendidos ou que nem existem) não aparece: peça de novo
            try {
                val j = JSONObject(mp.json)
                val then = j.optJSONArray("squad")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
                val now = repo.playersOf(slot).filter { it.owner == "MY" }.map { it.nameKey }.toSet()
                // nota antiga sem a lista do elenco não dá para conferir (citava jogadores que já saíram): não aparece
                val fresh = if (then == null) false
                else (then - now).size <= 2 && (now - then).size <= 2 && System.currentTimeMillis() - mp.at < 3L * 86400000L
                if (fresh) j.optString("aiNote").ifBlank { null } else null
            } catch (e: Exception) { null }
        },
        logs = dao.tacticLogs(slot).filter { it.kind.startsWith("tlog_R") },
        reports = dao.matchReports(slot),
        fitness = repo.fitness(slot),
        rivalProfile = repo.rivalProfile(slot),
        board = repo.boardMessages(slot),
        trainScreen = dao.plan(slot, "trainscreen")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } },
        trainOv = repo.trainingOverrides(slot),
        progress = runCatching { repo.progress(slot) }.getOrNull(),
        rivalChanged = runCatching { rivalChangesOf(repo, slot) }.getOrDefault(emptyList()),
        season = dao.plan(slot, "season")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } },
        win = tplan?.takeIf { tacticCurrent(it, f) }?.let { Director.winProb(repo, slot, it) },
        evidence = repo.evidence(slot),
        conflicts = repo.conflicts(slot),
        unavailable = repo.unavailable(slot)
    )
}

/** Rodadas que já têm placar lido da análise do jogo. */
private fun scoredRounds(reports: List<PlanEntity>): Set<Int> = reports.mapNotNull { p ->
    val r = p.kind.removePrefix("mr_R").toIntOrNull() ?: return@mapNotNull null
    val j = try { JSONObject(p.json) } catch (e: Exception) { return@mapNotNull null }
    if (j.has("sh") && j.has("sa")) r else null
}.toSet()

/** A tática guardada é da próxima rodada (senão a previsão usa só as forças). */
private fun tacticCurrent(j: JSONObject, f: Map<String, StoredField>): Boolean =
    Director.tacticValid(j, System.currentTimeMillis(), f, System.currentTimeMillis())

private fun known(f: Map<String, StoredField>, key: String): String? =
    f[key]?.value?.takeIf { FieldMerge.known(it) }

private suspend fun tacticFor(repo: Repo, slot: Int, @Suppress("UNUSED_PARAMETER") round: Int?): Boolean = Director.tacticReady(repo, slot)

private fun countdown(ms: Long): String {
    val d = ms - System.currentTimeMillis()
    if (d > 0L) {
        val m = d / 60000L
        return if (m >= 60) "em ${m / 60}h ${m % 60}min" else "em $m min"
    }
    if (d > -3L * 3600000L) return "já começou — registre o resultado"
    return "horário antigo — releia o calendário"
}

private suspend fun loadToday(ctx: Context): TodayData {
    val repo = Repo(ctx)
    val list = ArrayList<SlotSummary>()
    val now = System.currentTimeMillis()
    // A arrumação dos dados é pesada; basta a cada 20 s (ou logo após dados novos).
    val maintain = now - TodayCache.maintainedAt > 20000L || Diag.lastUpdateAt > TodayCache.maintainedAt
    if (maintain) TodayCache.maintainedAt = now
    for (slot in 1..4) try {
        if (maintain) try {
            repo.relinkResults(slot)
            repo.validateHuman(slot)
            repo.purgeOverlayNames(slot)
        } catch (e: Exception) { }
        val clk = try { repo.syncRound(slot, now) } catch (e: Exception) { null }
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).count { it.owner == "MY" }
        val all = repo.dao.matchesOf(slot)
        val matches = all.count { it.round != null }
        val awaiting = Fixtures.resultDue(
            all, scoredRounds(repo.dao.matchReports(slot)), now, clk?.round ?: f[K.ROUND]?.value?.toIntOrNull(), clk?.at
        )
        val gameMissing = Completeness.gameMissing(f)
        val tplan = repo.dao.plan(slot, "tactic")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } }
        val market = repo.dao.snapshots("TRANSFER", slot).isNotEmpty()
        val c = Completeness.compute(f, players, matches, market, f[K.ROUND_TOTAL]?.value?.toIntOrNull())
        val state = known(f, K.SLOT_STATE)?.takeIf { it == "vazio" || it == "procurando" || it == "preepoca" }
        val name = when (state) {
            "vazio" -> "Slot livre"
            "procurando" -> known(f, K.COMPETITION) ?: "Batalha"
            "preepoca" -> known(f, K.HUB_TITLE) ?: known(f, K.TEAM)
            else -> known(f, K.TEAM) ?: known(f, K.HUB_TITLE)
        }
        list.add(
            SlotSummary(
                slot = slot, title = name ?: "Slot $slot ainda não lido", known = name != null, state = state,
                competition = known(f, K.COMPETITION), compType = known(f, K.COMP_TYPE),
                roundDone = known(f, K.ROUND_DONE), roundTotal = known(f, K.ROUND_TOTAL),
                rival = known(f, K.RIVAL_TEAM), human = known(f, K.RIVAL_HUMAN),
                pct = c.percent, updated = f.values.maxOfOrNull { it.updatedAt } ?: 0L,
                ended = Fixtures.seasonEnded(f, all, now),
                missing = gameMissing.size,
                missingNames = gameMissing.map { it.label },
                nextAt = known(f, K.MATCH_AT)?.toLongOrNull(),
                tacticReady = tacticFor(repo, slot, known(f, K.ROUND)?.toIntOrNull()),
                needsResult = awaiting != null,
                win = tplan?.takeIf { tacticCurrent(it, f) && !Fixtures.seasonEnded(f, all, now) }?.let { Director.winProb(repo, slot, it) },
                rivalChanged = rivalChangesOf(repo, slot)
            )
        )
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // um slot com problema não some com os outros
        list.add(TodayCache.last.slots.firstOrNull { it.slot == slot } ?: SlotSummary(slot, "Slot $slot ainda não lido", false, null, null, null, null, null, null, 0, 0L))
        Diag.lastError = "Hoje S$slot: " + (e.message ?: e.javaClass.simpleName)
    }
    val work = (1..4).mapNotNull { s ->
        runCatching {
            val f = repo.fieldMap(s)
            val team = known(f, K.TEAM) ?: known(f, K.HUB_TITLE) ?: "Slot $s"
            val screen = repo.dao.plan(s, "trainscreen")?.json?.let { JSONObject(it) }
            Work.of(s, team, screen, repo.timers(s), f).takeIf { it.hasData }
        }.getOrNull()
    }
    val needs = ArrayList<Fresh.Need>()
    for (s in list) {
        if (s.ended || s.state != null && s.state != "ativo" || !s.known) continue
        runCatching { needs.addAll(Hints.needsFor(repo, s.slot, s.nextAt, true, now)) }
    }
    runCatching { needs.addAll(Fresh.global(repo.timers(0), repo.seenMap(0), now)) }
    return TodayData(Control.activeSession(ctx) != null, list, work, needs.sortedWith(compareByDescending<Fresh.Need> { it.urgency }.thenBy { it.slot })).also { TodayCache.last = it }
}

// ------------------------------------------------------------------ app

@Composable
private fun ChipButton(text: String, colors: List<Color>, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(50))
            .background(Brush.horizontalGradient(colors))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) { Text(text, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }
}

/**
 * Botões de captura em qualquer aba (mesma função da tela Hoje): sem captura, abre o OSM e já inicia a captura;
 * capturando, volta ao OSM ou encerra e processa.
 */
@Composable
private fun OsmButton(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val tick = rememberTick(1000L)
    val active = tick >= 0 && Control.activeSession(ctx) != null
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            ChipButton("■ Encerrar", listOf(Color(0xFFD32F2F), Color(0xFFFF5C5C))) {
                AppScope.scope.launch { Control.end(ctx.applicationContext) }
                Toast.makeText(ctx, "Captura encerrada. Processando em segundo plano…", Toast.LENGTH_SHORT).show()
            }
            Spacer(Modifier.width(6.dp))
            ChipButton("⚽ OSM ↗", listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8))) { openOsm(ctx) }
        } else {
            ChipButton("▶ OSM + captura", listOf(Color(0xFF1F6BFF), Color(0xFF00B4D8))) {
                if (serviceEnabled(ctx) && Diag.serviceConnected) {
                    scope.launch {
                        Control.start(ctx)
                        if (!openOsm(ctx)) Toast.makeText(ctx, "OSM não encontrado neste aparelho.", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    openOsm(ctx)
                    Toast.makeText(ctx, "Leitura automática desligada: ative em Ajustes para capturar.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val ctx = LocalContext.current
    // Volta exatamente para a tela em que estava (ao alternar com o OSM ou se o Android recriar o app).
    val ui = remember { ctx.getSharedPreferences("ui", Context.MODE_PRIVATE) }
    var tab by rememberSaveable { mutableIntStateOf(ui.getInt("tab", 0)) }
    var openSlot by rememberSaveable { mutableIntStateOf(ui.getInt("slot", 0)) }
    var openTab by rememberSaveable { mutableIntStateOf(ui.getInt("slotTab", 0)) }
    LaunchedEffect(tab, openSlot, openTab) {
        ui.edit().putInt("tab", tab).putInt("slot", openSlot).putInt("slotTab", openTab).apply()
    }
    val items = listOf("🏠" to "Hoje", "🕘" to "Sessões", "⚽" to "Slots", "🧠" to "Diretor", "⚙️" to "Ajustes")
    // Botão Voltar do Android: fecha o slot aberto, depois volta para Hoje (em vez de fechar o app).
    BackHandler(enabled = openSlot > 0) { openSlot = 0 }
    BackHandler(enabled = openSlot == 0 && tab != 0) { tab = 0 }
    // Toque numa notificação: abre o slot na aba certa.
    LaunchedEffect(Route.nonce) {
        if (Route.slot > 0) {
            tab = 2
            openSlot = Route.slot
            openTab = Route.tab
            Route.slot = 0
        }
    }
    Scaffold(
        containerColor = Color.Transparent,
        floatingActionButton = { if (openSlot == 0) OsmButton() },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF071330)) {
                items.forEachIndexed { i, (icon, name) ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; openSlot = 0 },
                        icon = { Text(icon, fontSize = 20.sp) },
                        label = { Text(name, fontSize = 12.sp, fontWeight = if (tab == i) FontWeight.Bold else FontWeight.Normal) },
                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                            indicatorColor = C.PRIMARY.copy(alpha = 0.28f), selectedTextColor = Color.White, unselectedTextColor = C.MUTED
                        )
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
                    key(openSlot) { SlotScreen(openSlot, openTab, { openTab = it }) { openSlot = 0 } }
                } else SlotsTab { slot, t -> openSlot = slot; openTab = t }
                3 -> if (openSlot > 0) {
                    key(openSlot) { SlotScreen(openSlot, openTab, { openTab = it }) { openSlot = 0 } }
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

/**
 * O Android fechou o app à força (a leitura estava ativa e agora está desligada, ou o app travou): explica
 * o motivo e dá os atalhos para não acontecer de novo. Some quando tudo volta ao normal.
 */
@Composable
private fun KilledBanner(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean) {
    val alive = Health.lastAlive(ctx)
    val crash = Health.lastCrash(ctx)
    // o serviço bate a cada minuto: sem batida há 3+ min e sem conexão = foi fechado (não é só o início do app)
    val killed = alive > 0L && System.currentTimeMillis() - alive > 180000L && !(enabled && connected)
    if (!killed && crash == null) return
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).border(1.dp, C.BAD, RoundedCornerShape(14.dp)),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF2A1218))
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (killed) "⚠ O Android fechou o app e desligou a leitura" else "⚠ O app travou",
                fontWeight = FontWeight.Bold, color = C.BAD, fontSize = 14.sp
            )
            Text(
                (if (killed) "A leitura funcionou até ${ago(alive)}. Quando o sistema fecha o app à força (economia de bateria " +
                    "do fabricante), ele desliga a leitura e cancela os lembretes — por isso não chegou aviso. "
                else "") +
                    (if (crash != null) "Último travamento: ${fmtTime(crash.first)} (${crash.second.take(90)}). " else "") +
                    Health.brandSteps(),
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
            if (killed && !enabled) PendingRow("1. Reativar a leitura (Acessibilidade → OSM AI Coach)", "Abrir") {
                openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            }
            if (!batteryOk) PendingRow("2. Bateria sem restrição", "Bateria") { openBatterySettings(ctx) }
            PendingRow("3. Inicialização automática / não suspender", "Abrir") { Health.openAutostart(ctx) }
            if (crash != null) {
                TextButton(onClick = { Health.clearCrash(ctx) }) { Text("Já vi, ocultar aviso do travamento", fontSize = 11.sp) }
            }
        }
    }
}

/** Faixa compacta: só mostra o que ainda falta configurar e fica recolhida quando a leitura já está ativa. */
@Composable
private fun SetupBanner(ctx: Context, enabled: Boolean, connected: Boolean, batteryOk: Boolean, hasKey: Boolean, onGoSettings: () -> Unit) {
    val ready = enabled && connected
    var open by remember { mutableStateOf(!ready) }
    val pending = (if (ready) 0 else 1) + (if (batteryOk) 0 else 1) + (if (hasKey) 0 else 1)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().clickable { open = !open },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
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
                        openBatterySettings(ctx)
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

/** Pílula de ação (toque): tática pendente em vermelho, resultado pendente em dourado. */
@Composable
private fun ActionPill(s: SlotSummary, onTactic: () -> Unit, onResult: () -> Unit) {
    when {
        s.needsResult -> Box(Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(50)).background(Color(0xFF7A5A00)).clickable { onResult() }.padding(horizontal = 10.dp, vertical = 3.dp)) {
            Text("📊 Registrar resultado →", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        s.tacticReady -> Box(Modifier.clickable { onTactic() }) { Pill("Tática ✔", true) }
        // botão de ação amarelo, como os do OSM
        else -> Box(
            Modifier.padding(end = 6.dp, top = 2.dp, bottom = 2.dp).clip(RoundedCornerShape(8.dp)).background(OsmColors.YELLOW)
                .clickable { onTactic() }.padding(horizontal = 14.dp, vertical = 6.dp)
        ) { Text("⚡ Gerar tática", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1C3E)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotCard(s: SlotSummary, onClick: () -> Unit, onTactic: () -> Unit, onResult: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).osmPanel().clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        val accent = if (s.ended) C.GOLD else if (s.needsResult) C.GOLD else if (s.tacticReady && s.missing == 0) C.OK else if (s.tacticReady) C.PRIMARY else C.WARN
        Column(Modifier.drawBehind { drawRect(accent, size = Size(5.dp.toPx(), size.height)) }.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Crest(s.slot, 54.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("S${s.slot}", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(
                            s.title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = if (s.known) Color.White else C.MUTED,
                            maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    val kindIcon = when (s.compType) { "Batalha" -> "⚔ Batalha"; "Copa" -> "🏆 Copa"; "Liga" -> "🏟 Liga"; else -> null }
                    val sub = listOfNotNull(
                        kindIcon,
                        s.competition,
                        if (s.roundDone != null && s.roundTotal != null) "${s.roundDone}/${s.roundTotal}" else null
                    ).joinToString(" • ")
                    if (sub.isNotBlank()) Text(sub, color = C.MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // quanto já foi lido (não é previsão): ícone de prancheta para não confundir com a chance de vitória
                val pc = if (s.pct >= 80) C.OK else if (s.pct >= 50) C.GOLD else C.WARN
                Column(
                    Modifier.clip(RoundedCornerShape(12.dp)).background(pc.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📋 ${s.pct}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = pc)
                    Text("dados lidos", fontSize = 9.sp, color = C.MUTED)
                }
            }
            if (s.state != null) {
                val empty = s.state == "vazio"
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp))
                        .background((if (empty) C.PRIMARY else C.GOLD).copy(alpha = 0.14f)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val pre = s.state == "preepoca"
                    val kind = if (s.compType == "Batalha") "batalha" else if (s.compType == "Copa") "copa" else "liga"
                    Text(if (empty) "➕" else if (pre) "🗓" else "🔎", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (empty) "Sem liga ou batalha" else if (pre) "Pré-época: a $kind nova começa em breve" else "Procurando adversário",
                            fontSize = 13.sp, fontWeight = FontWeight.Bold
                        )
                        Text(
                            if (empty) "Comece uma nova liga ou batalha no OSM; o app acompanha sozinho."
                            else if (pre) "Monte o elenco e treine pelo Diretor. Quando aparecer a 1ª jornada, abra o pré-jogo para a tática."
                            else "A batalha ainda não começou. Quando aparecer a jornada, o slot volta ao normal.",
                            fontSize = 11.sp, color = C.MUTED
                        )
                    }
                }
            } else if (s.ended) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(Color(0x22FFC83D)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🏁", fontSize = 22.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Competição encerrada • aguardando a próxima", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("ver ›", fontSize = 12.sp, color = C.GOLD, fontWeight = FontWeight.Bold)
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RivalShield(s.rival, 36.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (s.rival != null) "vs ${s.rival}" else "próximo rival não lido", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (s.rival != null) Color.White else C.WARN
                        )
                        val meta = listOfNotNull(
                            s.nextAt?.let { "⏱ " + countdown(it).removePrefix("em ") },
                            s.human?.let { if (it == "Sim") "👤 humano" else "🖥 CPU" }
                        ).joinToString("   ")
                        if (meta.isNotBlank()) Text(meta, fontSize = 11.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val w = s.win
                    if (w != null) WinMini(w)
                }
                Steps(s)
                FlowRow(Modifier.padding(top = 6.dp)) {
                    if (s.nextAt != null || s.needsResult) ActionPill(s, onTactic, onResult)
                }
            }
            Text(
                "atualizado " + ago(s.updated), fontSize = 10.sp, color = C.MUTED, textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun TodayTab(onGoSettings: () -> Unit, onOpenSlot: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(1000L)
    val scope = rememberCoroutineScope()
    // dados a cada 5 s (ou na hora, com dado novo); o relógio da tela continua a cada 1 s
    val data = rememberLoaded(TodayCache.last, tick / 5, UiBus.version) { loadToday(ctx) }
    var msg by remember { mutableStateOf("") }
    val enabled = serviceEnabled(ctx)
    val connected = Diag.serviceConnected
    val ready = enabled && connected
    val batteryOk = batteryUnrestricted(ctx)
    val hasKey = Settings.get(ctx, Settings.GEMINI_KEY, "").isNotBlank()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
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

        KilledBanner(ctx, enabled, connected, batteryOk)
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
                Text("🔴 Capturando • " + typeLabel(Diag.currentType), color = C.OK, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("🎯", if (Diag.currentSlot > 0) "S${Diag.currentSlot}" else "?", "slot", C.GOLD, Modifier.weight(1f))
                    StatTile("📸", Diag.valid.get().toString(), "telas lidas", C.PRIMARY, Modifier.weight(1f))
                    StatTile("✨", Diag.extracted.get().toString(), "dados novos", C.OK, Modifier.weight(1f))
                }
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
            ) { Text("▶  Abrir OSM e capturar", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
            if (!ready) Text("⚙ Ative a leitura (acima) para liberar o botão.", color = C.WARN, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            if (data.slots.none { it.known }) Panel { HowItWorks() }
        }
        if (msg.isNotBlank()) Text(msg, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
        ProcessCard()

        val upcoming = data.slots.filter { it.nextAt != null && it.nextAt > System.currentTimeMillis() - 7200000L }.sortedBy { it.nextAt }
        if (upcoming.isNotEmpty()) {
            Title("Próximos jogos")
            Panel {
                for (u in upcoming) {
                    Row(Modifier.fillMaxWidth().clickable { onOpenSlot(u.slot, if (u.needsResult) 5 else 4) }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "S${u.slot} • ${u.title}" + (if (u.rival != null) " vs ${u.rival}" else ""), fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            Text(countdown(u.nextAt!!), fontSize = 11.sp, color = C.MUTED)
                        }
                        ActionPill(u, { onOpenSlot(u.slot, 4) }, { onOpenSlot(u.slot, 5) })
                    }
                }
            }
        }

        if (data.needs.any { it.urgency >= 1 }) NeedsCard(data.needs)
        Fold("🗺", "Guia: o que cada tela do OSM alimenta") {
            Text("Passe por estas telas com a captura ligada. Vale para todos os slots (repita em cada um).", fontSize = 12.sp, color = C.MUTED)
            for ((t, gives, every) in Fresh.GUIDE) {
                Column(Modifier.padding(top = 8.dp)) {
                    Text(Fresh.NAME[t] ?: t.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(gives, fontSize = 11.sp, color = C.MUTED)
                    Text("Quando: $every", fontSize = 11.sp, color = C.GOLD)
                    Text(Fresh.PATH[t] ?: "", fontSize = 11.sp, color = C.PRIMARY)
                }
            }
        }
        if (data.work.isNotEmpty()) {
            Title("⏱ Treinos e estádio")
            for (w in data.work) WorkFold(w) { onOpenSlot(w.slot, 5) }
        }

        Title("Seus slots")
        for (s in data.slots) SlotCard(s, { onOpenSlot(s.slot, 0) }, { onOpenSlot(s.slot, 4) }, { onOpenSlot(s.slot, 5) })

        Spacer(Modifier.height(8.dp))
        val ev = Diag.recentEvents()
        Fold("🕘", "Últimos eventos", if (ev.isEmpty()) null else ev.size.toString()) {
            if (ev.isEmpty()) Text("Nenhum evento ainda. Inicie a captura e navegue no OSM.", color = C.MUTED, fontSize = 12.sp)
            for (e in ev) Text(e, fontSize = 12.sp, modifier = Modifier.padding(vertical = 1.dp))
        }
        Fold("🩺", "Diagnóstico técnico", if (ready) "ok" else "⚠") {
            Column {
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
                KV("Último travamento", Health.lastCrash(ctx)?.let { fmtTime(it.first) + " — " + it.second } ?: "nenhum")
                KV("Leitura viva pela última vez", ago(Health.lastAlive(ctx)))
                KV("Aparelho", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " • Android " + android.os.Build.VERSION.RELEASE)
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
    val ai = rememberLoaded(emptyList<ScreenEntity>(), tick, UiBus.version) { Repo(ctx).dao.aiScreens(30) }
    val archive = rememberLoaded(emptyList<ScreenEntity>(), tick, UiBus.version) { Repo(ctx).dao.savedScreens(12) }
    val hasKey = AiClient.hasAnyKey(ctx)
    val waiting = ai.count { it.aiState == "pending" }
    val done = ai.count { it.aiState == "done" }
    val failed = ai.count { it.aiState == "failed" }
    Title("🤖 Leitura com IA")
    if (!hasKey) AlertCard("⚠ Sem chave de IA: coloque a chave do Gemini (grátis) em Ajustes para a IA ler essas telas.")
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("⏳", waiting.toString(), "na fila", C.GOLD, Modifier.weight(1f))
        StatTile("✅", done.toString(), "lidas", C.OK, Modifier.weight(1f))
        StatTile("⚠", failed.toString(), "com erro", C.LOSS, Modifier.weight(1f))
    }
    Button(
        onClick = {
            if (!ProcessState.running) {
                val app = ctx.applicationContext
                AppScope.scope.launch(Dispatchers.IO) {
                    try {
                        // as que deram erro voltam para a fila
                        val dao = Repo(app).dao
                        for (s in dao.aiScreens(30)) if (s.aiState == "failed" || (s.aiState == "skipped" && s.note.contains("chave"))) dao.updateScreenAi(s.id, "pending", s.extracted, s.note)
                        Processor.run(app, "manual")
                    } catch (e: Exception) {
                        ProcessState.finish("Erro: " + (e.message ?: "?"))
                    }
                    withContext(Dispatchers.Main) { UiBus.version++ }
                }
            }
        },
        enabled = !ProcessState.running && hasKey && (waiting + failed) > 0,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    ) { Text("🤖 Ler com IA agora (${waiting + failed})") }
    Hint(
        "O OCR do celular lê quase tudo sozinho. A IA só entra no que ele não conseguiu: pré-jogo sem força/bônus/rival, " +
            "relatório do rival sem formação ou estilo e plantel com nomes ilegíveis. As mais novas são lidas primeiro; telas que " +
            "já não precisam (o OCR completou depois, jogo que já passou) são dispensadas sem gastar a cota.",
        "para que serve"
    )
    if (ai.isEmpty()) Text("Nenhuma tela na fila da IA.", color = C.MUTED, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    for (s in ai.take(12)) {
        val (icon, label, col) = when (s.aiState) {
            "done" -> Triple("✅", if (s.extracted > 0) "lida pela IA" else "lida (sem dado novo)", C.OK)
            "pending" -> Triple("⏳", if (s.slotId == 0) "espera o slot" else "na fila", C.GOLD)
            "skipped" -> Triple("⏭", if (s.note.contains("chave")) "não lida (sem chave)" else "dispensada", C.MUTED)
            else -> Triple("⚠", "erro na IA", C.LOSS)
        }
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.imagePath)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(s.type) + " • " + (if (s.slotId > 0) "S${s.slotId}" else "sem slot"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                            Text("$icon $label", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = col)
                        }
                        Text("  " + fmtTime(s.at), fontSize = 11.sp, color = C.MUTED)
                    }
                    if (s.note.isNotBlank() && s.aiState != "pending") Text(s.note, fontSize = 11.sp, color = C.MUTED, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
                }
            }
            if (s.slotId > 0 && s.aiState != "pending") {
                TextButton(onClick = { startReadOne(ctx, s.id) }, enabled = !ProcessState.running && hasKey) { Text("🔁 Ler de novo com IA", fontSize = 12.sp) }
            }
        }
    }
    Fold("🗂", "Telas guardadas (diagnóstico)", archive.size.toString()) {
        Text("Imagens guardadas para conferir a leitura e ir no diagnóstico. A IA lê sozinha as que precisam (acima).", fontSize = 11.sp, color = C.MUTED)
        for (s in archive) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Thumb(s.imagePath)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(typeLabel(s.type) + " • " + (if (s.slotId > 0) "S${s.slotId}" else "sem slot"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(fmtTime(s.at), fontSize = 11.sp, color = C.MUTED)
                }
                if (s.slotId > 0) TextButton(onClick = { startReadOne(ctx, s.id) }, enabled = !ProcessState.running && hasKey) { Text("IA", fontSize = 12.sp) }
            }
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
        ) { Text("Reprocessar telas sem slot") }
        OutlinedButton(
            onClick = {
                if (!ProcessState.running) {
                    val app = ctx.applicationContext
                    AppScope.scope.launch(Dispatchers.IO) {
                        try {
                            val repo = Repo(app)
                            val last = repo.dao.sessions().firstOrNull { it.endedAt != null } ?: repo.dao.sessions().firstOrNull()
                            if (last == null) {
                                ProcessState.begin("Reprocessando a última captura…", 0)
                                ProcessState.finish("Nenhuma captura guardada ainda.")
                                return@launch
                            }
                            ProcessState.begin("Reprocessando a última captura (${fmtTime(last.startedAt)})…", 1)
                            val r = FramePipeline.get(app).replaySession(last.id) { i, n ->
                                ProcessState.total = n
                                ProcessState.done = i
                                ProcessState.label("Relendo tela ${minOf(i + 1, n)} de $n da captura de ${fmtTime(last.startedAt)}…")
                            }
                            withContext(Dispatchers.Main) { UiBus.version++ }
                            ProcessState.finish(
                                "Captura de ${fmtTime(last.startedAt)} relida: ${r.read} de ${r.screens} telas guardadas, ${r.fields} campo(s) atualizados" +
                                    (if (r.bySlot.isNotEmpty()) " (" + r.bySlot.toSortedMap().entries.joinToString { "S${it.key}: ${it.value}" } + ")" else "") +
                                    ". Só as telas com imagem guardada podem ser relidas."
                            )
                        } catch (e: Exception) {
                            ProcessState.finish("Erro ao reprocessar: " + (e.message ?: e.javaClass.simpleName))
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        ) { Text("🔁 Reprocessar a última captura inteira") }
        Hint(
            "Relê com a versão atual do app todas as telas guardadas da última captura (as mesmas que vão no diagnóstico) e " +
                "aplica nos slots. Dado mais novo nunca é trocado por leitura antiga."
        )
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
    val tick = rememberTick(5000L)
    val data = rememberLoaded(TodayCache.last, tick, UiBus.version) { loadToday(ctx) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Slots", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        for (s in data.slots) SlotCard(s, { onOpen(s.slot, 0) }, { onOpen(s.slot, 4) }, { onOpen(s.slot, 5) })
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
    val goal: String? = null,
    /** A primeira coisa a fazer neste slot para o time evoluir. */
    val next: String? = null,
    /** Semana em uma linha: valor, caixa e treino. */
    val week: String? = null,
    /** Dicas primeiro: o que fazer agora para evoluir rápido (dados do slot + o que a comunidade confirma). */
    val tips: List<Playbook.Tip> = emptyList()
)

private suspend fun loadDirector(ctx: Context): List<DirSlot> {
    val repo = Repo(ctx)
    val today = loadToday(ctx)
    val out = ArrayList<DirSlot>()
    // ganho de força por dia de treino, por idade, medido nos 4 slots
    val trainRates = Hints.trainRates(repo)
    for (s in today.slots) {
        val f = repo.fieldMap(s.slot)
        val inp = Director.tacticInput(repo, s.slot)
        val ready = inp.players.count { it.strength != null } >= 8
        val res = if (ready) TacticEngine.recommend(inp) else null
        val w = s.win
        val forecast = if (s.ended) "competição encerrada — sem próximo jogo" else if (res == null) "faltam dados do elenco (leia o Plantel do seu time)" else {
            res.tactic.formation + " • " + res.tactic.playStyle +
                (if (w != null) " • V ${w.win}% / E ${w.draw}% / D ${w.loss}%" else "")
        }
        val plan = Director.marketPlan(repo, s.slot)
        val alerts = ArrayList<String>()
        if (s.ended) alerts.add("🏁 S${s.slot}: competição encerrada. Quando a nova começar, abra o pré-jogo: o app arquiva a antiga e recomeça o calendário.")
        val nextIn = s.nextAt?.let { it - System.currentTimeMillis() }
        if (!s.tacticReady && nextIn != null && nextIn in 0L..10800000L) alerts.add("⚠ S${s.slot}: jogo em ${countdown(s.nextAt ?: 0L)} e a tática ainda não foi gerada.")
        if (plan != null) {
            val free = 5 - plan.trainingActive
            if (free > 0 && plan.train.isNotEmpty()) alerts.add("🔵 S${s.slot}: $free treino(s) livre(s) — ${plan.train.joinToString(", ") { it.name }}.")
            if (plan.sell.isNotEmpty()) alerts.add("🔴 S${s.slot}: ${plan.sell.size} venda(s) sugerida(s) para liberar caixa.")
        }
        if (s.missing > 0 && !s.ended) alerts.add("📋 S${s.slot}: faltam ${s.missing} campos de leitura (toque no slot para preencher).")
        if (s.needsResult) alerts.add("📊 S${s.slot}: o jogo do calendário já aconteceu — registre o resultado (ou abra a análise do jogo no OSM).")
        if (w != null && w.win < 35 && !s.needsResult) alerts.add("⚠ S${s.slot}: jogo difícil (vitória ${w.win}%) — confira a tática e os treinos antes.")
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
        val seats = TrainBoard.seats(
            repo.dao.plan(s.slot, "trainscreen")?.json?.let { runCatching { JSONObject(it) }.getOrNull() },
            repo.playersOf(s.slot), System.currentTimeMillis(), repo.trainingOverrides(s.slot)
        )
        val freeSeat = seats.firstOrNull { it.state == "empty" || it.state == "done" }
        val next = when {
            s.needsResult -> "registrar o resultado do último jogo"
            !s.ended && !s.tacticReady && s.nextAt != null -> "gerar a tática do próximo jogo"
            freeSeat != null -> (if (freeSeat.state == "done") "pegar o treino concluído e " else "") +
                "ocupar o treinador " + (if (freeSeat.role == "UNI") "universal" else freeSeat.role) +
                (plan?.train?.firstOrNull { (TRAINER_ROLE[it.trainer] ?: "UNI") == freeSeat.role }?.let { " com ${it.name}" } ?: "")
            plan != null && plan.sell.isNotEmpty() -> "vender ${plan.sell.first().name} (${plan.sell.size} venda(s) sugerida(s))"
            plan != null && plan.buy.isNotEmpty() -> "comprar ${plan.buy.first().name}"
            seats.any { it.state == "unknown" } -> "abrir a tela de Treinamento no jogo"
            else -> null
        }
        val tips = Hints.slotTips(repo, s.slot, res?.rows?.flatten()?.mapNotNull { it?.nameKey }?.toSet() ?: emptySet(), s.nextAt, s.title, trainRates, Settings.get(ctx, "spend_bc", "0") == "1")
        out.add(
            DirSlot(
                s = s,
                tips = tips,
                forecast = forecast,
                sells = plan?.sell?.map { it.name + " (" + it.cat + " " + (it.strength ?: "?") + ")" } ?: emptyList(),
                buys = plan?.buy?.map { it.name + " (+" + it.gain + ")" } ?: emptyList(),
                trains = plan?.train?.map { it.name + " → " + (if (it.trainer == "universal") "universal" else it.trainer) } ?: emptyList(),
                radar = top?.let { it.cat + ": " + it.name + " (" + it.strength + ")" + (if (it.affordable) " ✔ cabe no caixa" else " — falta caixa") },
                cash = f[K.CASH]?.value ?: NI,
                sellSlots = MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value),
                alerts = alerts,
                stadium = stadiumLine,
                goal = goalLine,
                next = next,
                week = runCatching { repo.progress(s.slot) }.getOrNull()?.let { pg ->
                    listOfNotNull(
                        pg.week?.value?.let { "valor ${moneyText(it, true)}" },
                        pg.week?.cash?.let { "caixa ${moneyText(it, true)}" },
                        pg.train?.takeIf { it.trained > 0 }?.let { "treino ${if (it.edge >= 0) "+" else ""}${"%.1f".format(it.edge).replace('.', ',')}/jogador" },
                        pg.ledger.takeIf { it.buys + it.sells > 0 }?.let { "saldo ${moneyText(it.balance, true)}" }
                    ).takeIf { it.isNotEmpty() }?.joinToString(" • ")?.let { "📈 Semana: $it" }
                }
            )
        )
    }
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DirectorTab(onOpen: (Int, Int) -> Unit) {
    val ctx = LocalContext.current
    // recarga pesada (4 slots): a cada 20 s ou na hora em que chega dado novo (UiBus)
    val tick = rememberTick(20000L)
    val list = rememberLoaded(emptyList<DirSlot>(), tick, UiBus.version) { loadDirector(ctx) }
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Diretor", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatTile("🤖", AiClient.usedToday(ctx).toString(), "IA hoje", C.PRIMARY, Modifier.weight(1f))
            StatTile("🔔", list.sumOf { it.alerts.size }.toString(), "alertas", C.WARN, Modifier.weight(1f))
            StatTile("⚽", list.count { !it.s.ended && it.s.nextAt != null }.toString(), "jogos", C.OK, Modifier.weight(1f))
        }
        val eventTips = list.flatMap { it.tips }.filter { it.icon == "🎉" }.distinctBy { it.title }
        if (eventTips.isNotEmpty()) {
            Title("🎉 Eventos do OSM agora")
            for (t in eventTips) TipCard(t) {}
        }
        Title("📋 Dicas primeiro")
        Text("O que fazer agora para evoluir rápido, com os seus dados e o que a comunidade do OSM confirma que funciona.", fontSize = 12.sp, color = C.MUTED)
        for (d in list.filter { it.tips.isNotEmpty() }) {
            Row(Modifier.padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Crest(d.s.slot, 26.dp)
                Spacer(Modifier.width(8.dp))
                Text("S${d.s.slot} • ${d.s.title}", fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val mineTips = d.tips.filter { it.icon != "🎉" }
            for (t in mineTips.take(4)) TipCard(t) { onOpen(d.s.slot, t.tab) }
            if (mineTips.size > 4) Fold("➕", "Mais ${mineTips.size - 4} dica(s) do S${d.s.slot}") { for (t in mineTips.drop(4)) TipCard(t) { onOpen(d.s.slot, t.tab) } }
        }
        Fold("📚", "O que a comunidade diz que funciona") {
            for ((rule, src) in Playbook.GUIDE) {
                Text(rule, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Fonte: $src", fontSize = 10.sp, color = C.MUTED)
            }
        }
        if (Diag.lastError != null) Hint(Diag.lastError ?: "", "último erro")

        val alertLines = list.flatMap { it.alerts }
        if (alertLines.isNotEmpty()) {
            Fold("🔔", "Alertas", alertLines.size.toString()) { for (a in alertLines) AlertCard(a) }
        }
        val upcoming = list.filter { !it.s.ended && it.s.nextAt != null && it.s.nextAt > now - 7200000L }.sortedBy { it.s.nextAt }
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

        if (list.isNotEmpty()) Title("Plano de cada slot")
        for (d in list) {
            Fold("S${d.s.slot}", d.s.title, if (d.alerts.isNotEmpty()) "${d.alerts.size} alerta(s)" else null) {
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
                Spacer(Modifier.height(8.dp))
                WinCard(d.s.win, compact = true)
                if (d.next != null) {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(C.PRIMARY.copy(alpha = 0.18f))
                            .clickable { onOpen(d.s.slot, 6) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("👉", fontSize = 18.sp)
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text("PRÓXIMO PASSO", fontSize = 9.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
                            Text(d.next.replaceFirstChar { it.uppercase() }, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                        Text("›", fontSize = 20.sp, color = C.MUTED)
                    }
                }
                Text("⚽ ${d.forecast}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatTile("💰", d.cash, "caixa", C.OK, Modifier.weight(1f))
                    StatTile("🔴", d.sells.size.toString(), "vender", C.LOSS, Modifier.weight(1f))
                    StatTile("🟢", d.buys.size.toString(), "comprar", C.WIN, Modifier.weight(1f))
                    StatTile("🏋", d.trains.size.toString(), "treinar", C.PRIMARY, Modifier.weight(1f))
                }
                FlowRow(Modifier.padding(top = 6.dp)) {
                    for (x in d.sells.take(3)) Pill("🔴 $x", false)
                    for (x in d.buys.take(3)) Pill("🟢 $x", true)
                    for (x in d.trains.take(3)) Pill("🏋 $x")
                }
                if (d.radar != null) Text("📡 ${d.radar}", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
                if (d.goal != null) Text(d.goal, fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
                if (d.week != null) Text(d.week, fontSize = 12.sp, color = OsmColors.YELLOW, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
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
private fun SlotScreen(slot: Int, tab: Int, onTab: (Int) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val tick = rememberTick(6000L)
    val data = rememberLoaded(SlotData(), tick, Pair(slot, UiBus.version)) { loadSlot(ctx, slot) }
    val tabs = listOf("Resumo", "Pré-jogo", "Elenco", "Calendário", "Tática", "Resultado", "Diretor", "Aprendizado")
    val title = known(data.fields, K.TEAM) ?: known(data.fields, K.HUB_TITLE) ?: if (data.ready) "Slot $slot ainda não lido" else "carregando…"
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onBack) { Text("←") }
            Spacer(Modifier.width(10.dp))
            Crest(slot, 48.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("S$slot • $title", fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(known(data.fields, K.COMPETITION) ?: if (data.ready) "competição não lida" else "", color = C.MUTED, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            OsmButton()
        }
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 4.dp, containerColor = C.BG) {
            tabs.forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { onTab(i) }, text = { Text(t, fontSize = 13.sp) }) }
        }
        if (!data.ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = C.PRIMARY) }
            return@Column
        }
        // troca de aba fluida: a nova desliza um pouco e aparece enquanto a anterior some
        androidx.compose.animation.AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260)) +
                    androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(300)) { it / 10 * dir }) togetherWith
                    androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160))
            },
            label = "tabs"
        ) { t ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                when (t) {
                    0 -> SlotSummaryTab(slot, data)
                    1 -> SlotPregame(slot, data)
                    2 -> SlotSquad(slot, data)
                    3 -> SlotCalendar(slot, data)
                    4 -> SlotTactic(slot, data)
                    5 -> SlotResult(slot, data) { onTab(4) }
                    6 -> SlotDirector(slot, data)
                    else -> SlotLearning(slot, data)
                }
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
    val ended = Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())
    if (ended) {
        SeasonEndCard(d)
        if (d.board.size > 1) Fold("💬", "Mensagens da direção", d.board.size.toString()) { BoardLines(d.board) }
    } else {
        MatchupHero(slot, d)
        Panel { WinCard(d.win) }
    }
    // números da temporada em blocos
    val goalPos = fv(d, K.MY_OBJECTIVE).toIntOrNull()
    val nowPos = fv(d, K.LEAGUE_POS).toIntOrNull()
    val pts = known(d.fields, K.POINTS)
    val cash = known(d.fields, K.CASH)
    // no fim da competição a posição e os pontos já estão no cartão de cima
    val showPos = !ended && (nowPos != null || pts != null)
    if (showPos || (goalPos != null && !ended) || cash != null) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showPos) StatTile("📈", nowPos?.let { "${it}º" } ?: "?", "na liga", C.PRIMARY, Modifier.weight(1f))
        if (showPos) StatTile("⭐", pts ?: "?", "pontos", C.GOLD, Modifier.weight(1f))
        if (goalPos != null && !ended) StatTile(
            "🎯", "top $goalPos",
            if (nowPos != null) (if (nowPos <= goalPos) "meta ✔" else "fora da meta") else "meta",
            if (nowPos != null && nowPos > goalPos) C.LOSS else C.OK, Modifier.weight(1f)
        )
        if (cash != null) StatTile("💰", cash, "caixa", C.OK, Modifier.weight(1f))
    }
    if (c != null && !ended) {
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Ring(c.percent, 64.dp, if (c.percent >= 80) C.OK else if (c.percent >= 50) C.GOLD else C.WARN, "${c.percent}%")
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (c.missing.isEmpty()) "Tudo lido ✔" else "Faltam ${c.missing.size} dado(s)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = if (c.missing.isEmpty()) C.OK else Color.White)
                    Text("${c.known.size} de ${c.known.size + c.missing.size} lidos", fontSize = 11.sp, color = C.MUTED)
                }
            }
            var all by remember { mutableStateOf(false) }
            for (mi in if (all) c.missingItems else c.missingItems.take(4)) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE2)
                        .clickable { missingEdit = mi }.padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("📋", fontSize = 14.sp)
                    Text("  " + mi.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("＋", color = C.PRIMARY, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (c.missingItems.size > 4) {
                Text(
                    if (all) "▲ mostrar menos" else "▼ ver mais ${c.missingItems.size - 4}", fontSize = 12.sp, color = C.PRIMARY, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { all = !all }.padding(top = 8.dp)
                )
            }
            if (all) for (m in c.missing) {
                if (c.missingItems.none { x -> x.label == m }) {
                    Text("• $m", fontSize = 12.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (c.missing.isNotEmpty()) Hint("Toque em ＋ para preencher à mão, ou abra a tela certa no OSM e role até o fim: o app lê sozinho.")
        }
    }
    val me = missingEdit
    if (me != null) ManualDialog(slot, ManualFields.spec(me.key, me.label), fv(d, me.key)) { missingEdit = null }
    if (!ended) EvidencePanel(d.evidence, fv(d, K.RIVAL_HUMAN) == "Não")
    val stadiumPlan = Director.stadiumPlan(d.fields)
    if (stadiumPlan != null) {
        Fold("🏟", "Plano do estádio") { Text(stadiumPlan, fontSize = 12.sp) }
    }
    EditSection(
        slot, d, "Dados da competição",
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
        ),
        icon = "🏆"
    )
    Fold("🕘", "Última leitura de cada tela") {
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
    if (Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())) {
        // sem próximo jogo: nada de rival, árbitro ou relatório (eram do último jogo)
        Panel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🏁", fontSize = 30.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Sem próximo jogo", fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                    Text("A competição acabou. O pré-jogo volta quando a próxima começar.", fontSize = 12.sp, color = C.MUTED)
                }
            }
        }
        Panel {
            Text("💪 Força do seu time hoje", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((lab, k) in listOf("Geral" to K.MY_STRENGTH, "GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK)) {
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2).padding(vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(known(d.fields, k) ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
                        Text(lab, fontSize = 10.sp, color = C.MUTED)
                    }
                }
            }
            val weakest = listOf("GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK)
                .mapNotNull { (l, k) -> known(d.fields, k)?.toIntOrNull()?.let { l to it } }.minByOrNull { it.second }
            if (weakest != null) {
                Text("Setor mais fraco: ${weakest.first} (${weakest.second}). Reforce antes da próxima competição: veja o Diretor.", fontSize = 12.sp, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            }
        }
        return
    }
    val at = fv(d, K.MATCH_AT).toLongOrNull()
    // IA sempre no topo
    OutlinedButton(
        onClick = { startReadLatest(ctx, slot) },
        enabled = !ProcessState.running,
        modifier = Modifier.fillMaxWidth()
    ) { Text("🤖 Ler a análise do rival com IA agora") }
    ProcessCard()
    Panel {
        RefereeMeter(known(d.fields, K.REFEREE))
        Spacer(Modifier.height(10.dp))
        Duel("FORÇA GERAL", known(d.fields, K.MY_STRENGTH), known(d.fields, K.RIVAL_STRENGTH))
        Duel("MEU BÔNUS x BÔNUS DO RIVAL (%)", known(d.fields, K.MY_BONUS), known(d.fields, K.RIVAL_LOGIN_BONUS))
    }
    SectorDuels(d)
    Panel {
        Text("🕵 Como o rival joga", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChip("Formação", known(d.fields, K.RIVAL_FORMATION) ?: "?", Modifier.weight(1f))
            SettingChip("Estilo", known(d.fields, K.RIVAL_PLAN) ?: "?", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SettingChip("Marcação", known(d.fields, K.RIVAL_MARKING) ?: "?", Modifier.weight(1f))
            SettingChip("Fora-de-jogo", known(d.fields, K.RIVAL_OFFSIDE) ?: "?", Modifier.weight(1f))
            SettingChip("Entradas", known(d.fields, K.RIVAL_TACKLE) ?: "?", Modifier.weight(1f))
        }
    }
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
        ),
        icon = "📅"
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
        ),
        icon = "💪"
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
    EditSection(slot, d, "Relatório do rival", reportRows, icon = "🕵")
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
            Fold("👤", "Perfil de ${pj.optString("nick")}", "${arr.length()} jogo(s)") {
                KV("Formação mais usada", mode("formation"))
                KV("Estilo mais usado", mode("plan"))
                KV("Marcação", mode("marking"))
                KV("Fora-de-jogo", mode("offside"))
                KV("Entradas", mode("tackle"))
            }
        }
    }
    Hint(
        "? = ainda não lido. Formação, força, valor e setores do rival vêm do Plantel dele. Estilo, marcação, fora-de-jogo, entradas, estágio e nível do estádio vêm do Relatório do analista (a tela com a nota à esquerda): alterne os botões Tática e Equipa inicial. Apelido e bônus só existem se o rival for humano. O árbitro vem da cor do termômetro no pré-jogo.",
        "de onde vem cada dado"
    )
}

private fun catColor(cat: String): Color = when (cat) {
    "ATA" -> Color(0xFFE5484D)
    "MEI" -> Color(0xFF3D8BFF)
    "DEF" -> Color(0xFF2EA043)
    else -> Color(0xFFF2C94C)
}

@Composable
private fun TrainingDialog(slot: Int, p: PlayerEntity, out: Boolean, onDone: () -> Unit) {
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
                Text("⛔ Próximo jogo", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).setUnavailable(slot, p.nameKey, !out)
                            UiBus.version++
                        }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = if (out) C.OK else C.WARN)
                ) { Text(if (out) "✓ Volta a jogar (tirar o ⛔)" else "⛔ Fora do próximo jogo (lesão/suspensão)") }
                Text("🏋 Treino", fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                Hint("O app lê os treinos da tela de Treinamento do OSM. Cada slot tem até 5: 1 por treinador de posição (GOL, DEF, MEI, ATA) + 1 universal. Corrija aqui só se a leitura errou.")
                OutlinedButton(onClick = { set(true) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("🏋 Está treinando") }
                OutlinedButton(onClick = { set(false) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Não está treinando") }
                OutlinedButton(onClick = { set(null) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text("Voltar ao automático") }
                Text("Não existe no seu elenco (erro de leitura) ou já saiu?", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 12.dp))
                OutlinedButton(
                    onClick = {
                        AppScope.scope.launch(Dispatchers.IO) {
                            Repo(ctx).removePlayer(slot, p.nameKey)
                            UiBus.version++
                        }
                        onDone()
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = C.BAD)
                ) { Text("🗑 Remover do elenco (não volta mais)") }
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
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((lab, k) in listOf("Geral" to K.MY_STRENGTH, "GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK)) {
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(known(d.fields, k) ?: "?", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
                    Text(lab, fontSize = 9.sp, color = C.MUTED)
                }
            }
        }
    }
    val seats = TrainBoard.seats(d.trainScreen, d.players, System.currentTimeMillis(), d.trainOv)
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🏋 Treinamento", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Pill("${seats.count { it.state == "active" && it.fresh }} de 5 treinando", if (seats.count { it.state == "active" && it.fresh } >= 4) true else null)
        }
        Spacer(Modifier.height(8.dp))
        TrainingBoardView(seats)
        if (seats.any { it.state == "unknown" }) {
            Text("? = abra a tela de Treinamento no OSM para o app ler", fontSize = 11.sp, color = C.WARN, modifier = Modifier.padding(top = 6.dp))
        }
        if (seats.any { it.state == "done" }) {
            Text("✅ treino concluído: pegue a evolução no OSM e comece outro", fontSize = 11.sp, color = C.GOLD, modifier = Modifier.padding(top = 4.dp))
        }
        Hint(
            "O OSM tem 5 treinadores: Goleiros, Defesas, Médios e Avançados (um jogador da posição cada) e o Universal (qualquer posição). " +
                "O app lê quem está em cada um na tela de Treinamento — a camisa no plantel não conta. Lugar livre (＋) é treino desperdiçado. " +
                "Toque num jogador abaixo para marcar ⛔ fora do próximo jogo (lesão/suspensão, só nesta rodada), corrigir o treino ou remover quem não existe no elenco."
        )
    }
    if (mine.isEmpty()) {
        EmptyState("👕", "Nenhum jogador seu lido ainda", "No jogo: Plantel do SEU time, depois role a lista devagar até o fim.")
    }
    for (cat in listOf("ATA", "MEI", "DEF", "GOL", "?")) {
        // "?": lidos sem posição — antes ficavam invisíveis (mas contavam), sem como remover.
        val list = mine.filter { (it.cat ?: "?") == cat }.sortedByDescending { it.strength ?: 0 }
        if (list.isEmpty()) continue
        Title(if (cat == "?") "Sem posição (confira)" else cat)
        Panel {
            for (p in list) {
                Row(Modifier.fillMaxWidth().clickable { trainEdit = p }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(catColor(cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                        Text(p.strength?.toString() ?: "?", fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, fontWeight = FontWeight.SemiBold)
                        Text("${p.posCode ?: "?"} • ${p.age ?: "?"}a • ${p.valueText ?: "?"}", fontSize = 12.sp, color = C.MUTED)
                        val ft = d.fitness[p.nameKey]
                        if (ft != null) {
                            Row(Modifier.fillMaxWidth().padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                for ((ic, v) in listOf("⚡" to ft.first, "🙂" to ft.second)) {
                                    Text(ic, fontSize = 10.sp)
                                    LinearProgressIndicator(
                                        progress = { v.coerceIn(0, 100) / 100f },
                                        modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(50)),
                                        color = if (v < 50) C.LOSS else if (v < 70) C.WARN else C.OK, trackColor = C.SURFACE2
                                    )
                                }
                            }
                        }
                    }
                    val seat = seats.firstOrNull { it.key == p.nameKey && it.state != "unknown" }
                    if (seat != null && seat.state == "active" && seat.fresh) Pill("🏋 " + (if (seat.role == "UNI") "UNI" else seat.role))
                    else if (seat != null && seat.state == "done") Pill("✅ pegar")
                    else if (d.trainScreen == null && p.training == true) Pill("🏋")
                    // só aparece quando marcado (marca e desmarca no toque do jogador, para não marcar sem querer)
                    if (p.nameKey in d.unavailable) {
                        Box(Modifier.padding(start = 6.dp).clip(RoundedCornerShape(50)).background(C.LOSS).padding(horizontal = 8.dp, vertical = 4.dp)) {
                            Text("⛔ fora", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
    val te = trainEdit
    if (te != null) TrainingDialog(slot, te, te.nameKey in d.unavailable) { trainEdit = null }
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
private fun MatchCard(m: MatchEntity, modifier: Modifier, highlight: Boolean, void: Boolean = false, live: Boolean = false, onClick: () -> Unit) {
    val cup = Fixtures.isCup(m)
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
    val frame = if (live) modifier.border(2.dp, C.OK, shape) else if (highlight) modifier.border(2.dp, C.GOLD, shape) else if (cup && !void) modifier.border(1.dp, Color(0x669B6BFF), shape) else modifier
    Column(
        frame.clickable { onClick() }.height(140.dp).clip(shape).background(tint).graphicsLayer { alpha = if (void) 0.4f else 1f }.padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text((if (cup) "🏆" else place) + " " + (if (m.round != null) "J${m.round}" else m.label.take(8)), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.MUTED)
            // selo no topo (antes ficava embaixo e era cortado pelo card)
            val tag = when {
                m.result != null -> null
                void -> Pair("ELIMINADO", C.BAD)
                live -> Pair("AGORA", C.OK)
                highlight -> Pair("PRÓXIMO", C.GOLD)
                cup -> Pair("COPA", Color(0xFFB79CFF))
                else -> null
            }
            if (tag != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(tag.second.copy(alpha = 0.2f))
                        .border(1.dp, tag.second.copy(alpha = 0.7f), RoundedCornerShape(50)).padding(horizontal = 6.dp, vertical = 2.dp)
                ) { Text(tag.first, fontSize = 9.sp, color = tag.second, fontWeight = FontWeight.ExtraBold, maxLines = 1) }
            } else ResultBadge(m.result)
        }
        Text(score, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        val opp = Fixtures.opponent(m)
        val nick = m.opponentNick?.takeIf { Evidence.plausibleNick(it) }
        Text(
            opp ?: Fixtures.stage(m) ?: (if (m.opponent != null) "a definir" else NI),
            fontSize = 11.sp, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold
        )
        // Humano (apelido sob o time no card) ou CPU (card lido sem apelido).
        if (opp != null) {
            Text(
                if (nick != null) "👤 $nick" else "🖥 CPU",
                fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (nick != null) Color(0xFF7FD8A0) else C.MUTED
            )
        }
        Text(m.date ?: m.time ?: "", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
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
        EmptyState("📅", "Calendário ainda não lido", "No jogo: menu → Calendário do SEU time (no topo da lista) e role até o fim. Ou informe uma rodada à mão no botão abaixo.")
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
                Text("faltam J${unread.first()}–J${unread.last()} • role o calendário no OSM", fontSize = 11.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
    val form = sorted.filter { it.result != null && it.round != null }.takeLast(10).mapNotNull { it.result }
    if (form.isNotEmpty()) FormGuide(form)
    Spacer(Modifier.height(6.dp))
    Row(Modifier.padding(bottom = 8.dp)) {
        CountPill("V ${sorted.count { it.result == "V" }}", C.WIN)
        CountPill("E ${sorted.count { it.result == "E" }}", C.DRAW)
        CountPill("D ${sorted.count { it.result == "D" }}", C.LOSS)
        CountPill("${sorted.count { it.result == null && !Fixtures.void(it, sorted) }} a jogar", C.SURFACE2)
    }
    val outRound = Fixtures.cupOutRound(sorted)
    if (outRound != null) {
        Text("🏆 Eliminado da copa na J$outRound — os jogos de copa seguintes não contam.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(bottom = 6.dp))
    }
    Row(Modifier.padding(bottom = 8.dp)) {
        FilterPill("Todos", mode == 0) { mode = 0 }
        FilterPill("Resultados", mode == 1) { mode = 1 }
        FilterPill("Próximos", mode == 2) { mode = 2 }
    }
    // Próximo de verdade: ignora copa após eliminação e cards cujo horário já passou.
    val nowC = System.currentTimeMillis()
    val curRound = fv(d, K.ROUND).toIntOrNull()
    val curAt = fv(d, K.MATCH_AT).toLongOrNull()
    val nextRound = Fixtures.next(sorted, nowC, curRound, curAt)?.round
    // jogo da rodada atual que começou há pouco e ainda não tem placar: "AGORA" (o próximo continua marcado)
    val liveRound = curRound?.takeIf { r ->
        curAt != null && curAt <= nowC && nowC - curAt < 3 * 3600000L && sorted.any { it.round == r && it.result == null && it.scoreMine == null }
    }
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
            2 -> m == null || (m.result == null && !Fixtures.void(m, sorted))
            else -> true
        }
    }
    for (row in shown.chunked(3)) {
        Row(Modifier.fillMaxWidth()) {
            for ((r, m) in row) {
                val mod = Modifier.weight(1f).padding(3.dp)
                if (m != null) MatchCard(m, mod, m.round != null && m.round == nextRound, Fixtures.void(m, sorted), live = m.round != null && m.round == liveRound) { if (m.round != null) editRound = m.round }
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
        n.contains("ajudar meio") || n.contains("apoiar meio") -> listOf(Triple(0f, 1f, OR), Triple(0f, -1f, CY))
        n.contains("atacantes") || n.contains("laterais") -> listOf(Triple(-1f, -0.6f, CY), Triple(1f, -0.6f, CY))
        else -> emptyList()
    }
}

@Composable
private fun SectorIcon(option: String) {
    Canvas(Modifier.size(72.dp, 88.dp).clip(RoundedCornerShape(12.dp))) {
        // mini campo em perspectiva, com as setas no chão (mesma câmera do campo grande)
        val pv = PitchView(size.width, size.height)
        drawRect(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0B1A33))))
        for (i in 0 until 6) {
            val v0 = i / 6f
            val v1 = (i + 1) / 6f
            quad(pv.p(0f, v0), pv.p(1f, v0), pv.p(1f, v1), pv.p(0f, v1), if (i % 2 == 0) Color(0xFF2E8B3E) else Color(0xFF267A35))
        }
        val lw = 1.4f * density
        pitchLine(pv, listOf(0.04f to 0.02f, 0.96f to 0.02f, 0.96f to 0.98f, 0.04f to 0.98f), Color(0xCCFFFFFF), lw, closed = true)
        val ring = (0..32).map { i ->
            val a = i / 32f * 2f * Math.PI.toFloat()
            (0.5f + 0.2f * kotlin.math.cos(a)) to (0.5f + 0.14f * kotlin.math.sin(a))
        }
        pitchLine(pv, ring, Color(0xCCFFFFFF), lw)
        for ((dx, dy, col) in arrowSet(option)) {
            arrow(pv.p(0.5f + dx * 0.08f, 0.5f - dy * 0.06f), pv.p(0.5f + dx * 0.4f, 0.5f - dy * 0.36f), col, 3.2f * density)
        }
        val c = pv.p(0.5f, 0.5f)
        drawOval(Color(0x66000000), topLeft = Offset(c.x - 7f * density, c.y - 1f * density), size = Size(14f * density, 5f * density))
        drawCircle(
            Brush.radialGradient(listOf(Color.White, Color(0xFFB0BEC5)), center = Offset(c.x - 2f * density, c.y - 6f * density), radius = 7f * density),
            radius = 5.5f * density, center = Offset(c.x, c.y - 4f * density)
        )
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
    drawPath(path, Brush.verticalGradient(listOf(Color(0xFF2E8B3E), Color(0xFF1F6B30)), startY = top, endY = bottom))
    // faixas do gramado (cortadas pelo trapézio) e luz no centro
    clipPath(path) {
        for (i in 0 until 10 step 2) drawRect(Color(0x14FFFFFF), topLeft = Offset(w * i / 10f, 0f), size = Size(w / 10f, h))
        drawRect(Brush.radialGradient(listOf(Color(0x30FFFFFF), Color(0x00FFFFFF)), center = Offset(w * 0.5f, h * 0.5f), radius = w * 0.6f))
    }
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

/**
 * Campo em perspectiva (estilo OSM): u = 0..1 de lado a lado, v = 0 (meu gol, embaixo) .. 1 (gol rival, no fundo).
 * A largura encolhe com a distância e as faixas do fundo ficam mais finas, como uma câmera atrás do gol.
 */
private class PitchView(val w: Float, val h: Float) {
    val top = h * 0.08f
    val bottom = h * 0.97f
    private val halfNear = w * 0.47f
    private val halfFar = w * 0.30f

    fun y(v: Float): Float {
        val f = (1f - v) / (1f + 0.55f * v)
        return top + (bottom - top) * f
    }

    fun half(yy: Float): Float = halfFar + (halfNear - halfFar) * ((yy - top) / (bottom - top))

    fun p(u: Float, v: Float): Offset {
        val yy = y(v)
        return Offset(w / 2f + (u - 0.5f) * 2f * half(yy), yy)
    }

    /** Escala do objeto na profundidade v (1 = perto). */
    fun scale(v: Float): Float = half(y(v)) / halfNear
}

private fun DrawScope.quad(a: Offset, b: Offset, c: Offset, d: Offset, color: Color) {
    val path = androidx.compose.ui.graphics.Path()
    path.moveTo(a.x, a.y)
    path.lineTo(b.x, b.y)
    path.lineTo(c.x, c.y)
    path.lineTo(d.x, d.y)
    path.close()
    drawPath(path, color)
}

private fun DrawScope.pitchLine(pv: PitchView, pts: List<Pair<Float, Float>>, color: Color, width: Float, closed: Boolean = false) {
    val all = if (closed) pts + pts.first() else pts
    for (i in 0 until all.size - 1) {
        drawLine(color, pv.p(all[i].first, all[i].second), pv.p(all[i + 1].first, all[i + 1].second), width, cap = StrokeCap.Round)
    }
}

private fun DrawScope.pitch3d(pv: PitchView) {
    val w = size.width
    val h = size.height
    // estádio: céu noturno e arquibancada escura
    drawRect(Brush.verticalGradient(listOf(Color(0xFF050B18), Color(0xFF0B1A33), Color(0xFF07101F))))
    drawRect(Color(0x33FFFFFF), topLeft = Offset(0f, pv.top - h * 0.035f), size = Size(w, h * 0.012f))
    // gramado em faixas
    val bands = 12
    for (i in 0 until bands) {
        val v0 = i / bands.toFloat()
        val v1 = (i + 1) / bands.toFloat()
        val c = if (i % 2 == 0) Color(0xFF2E8B3E) else Color(0xFF267A35)
        quad(pv.p(0f, v0), pv.p(1f, v0), pv.p(1f, v1), pv.p(0f, v1), c)
    }
    // luz dos refletores (centro claro, bordas escuras)
    drawRect(
        Brush.radialGradient(listOf(Color(0x33FFFFFF), Color(0x00FFFFFF)), center = Offset(w / 2f, h * 0.45f), radius = w * 0.75f)
    )
    drawRect(Brush.verticalGradient(listOf(Color(0x55000000), Color(0x00000000)), startY = pv.top, endY = pv.top + h * 0.25f))
    val line = Color(0xE6FFFFFF)
    val lw = 2.2f * density
    val m = 0.03f
    pitchLine(pv, listOf(m to 0.015f, 1f - m to 0.015f, 1f - m to 0.985f, m to 0.985f), line, lw, closed = true)
    pitchLine(pv, listOf(m to 0.5f, 1f - m to 0.5f), line, lw)
    // círculo central (projetado ponto a ponto, vira elipse)
    val circle = (0..48).map { i ->
        val a = i / 48f * 2f * Math.PI.toFloat()
        (0.5f + 0.13f * kotlin.math.cos(a)) to (0.5f + 0.085f * kotlin.math.sin(a))
    }
    pitchLine(pv, circle, line, lw)
    drawCircle(line, radius = 3f * density * pv.scale(0.5f), center = pv.p(0.5f, 0.5f))
    // grandes áreas e pequenas áreas dos dois lados
    for ((base, dir) in listOf(0.015f to 1f, 0.985f to -1f)) {
        pitchLine(pv, listOf(0.22f to base, 0.22f to base + dir * 0.15f, 0.78f to base + dir * 0.15f, 0.78f to base), line, lw)
        pitchLine(pv, listOf(0.38f to base, 0.38f to base + dir * 0.055f, 0.62f to base + dir * 0.055f, 0.62f to base), line, lw)
        drawCircle(line, radius = 2.5f * density * pv.scale(base), center = pv.p(0.5f, base + dir * 0.105f))
    }
    // gols com traves em pé (altura proporcional à profundidade)
    for (v in listOf(0.015f, 0.985f)) {
        val l = pv.p(0.44f, v)
        val r = pv.p(0.56f, v)
        val gh = 26f * density * pv.scale(v)
        drawRect(Color(0x33FFFFFF), topLeft = Offset(l.x, l.y - gh), size = Size(r.x - l.x, gh))
        drawLine(Color.White, l, Offset(l.x, l.y - gh), 3f * density * pv.scale(v))
        drawLine(Color.White, r, Offset(r.x, r.y - gh), 3f * density * pv.scale(v))
        drawLine(Color.White, Offset(l.x, l.y - gh), Offset(r.x, r.y - gh), 3f * density * pv.scale(v))
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
    Canvas(Modifier.fillMaxWidth().height(440.dp).clip(RoundedCornerShape(20.dp))) {
        val dp = density
        val pv = PitchView(size.width, size.height)
        pitch3d(pv)
        if (rows.isEmpty()) return@Canvas
        val namePaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        val numPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
            setShadowLayer(3f, 0f, 1.5f, android.graphics.Color.BLACK)
        }
        val k = rows.size - 1
        // Desenha do fundo para a frente: quem está perto da câmera fica por cima.
        val order = rows.indices.sortedByDescending { ri -> if (ri == 0) 0.06f else if (k <= 1) 0.5f else 0.24f + (ri - 1) * (0.52f / (k - 1)) }
        for (ri in order) {
            val row = rows[ri]
            val v = if (ri == 0) 0.06f else if (k <= 1) 0.5f else 0.24f + (ri - 1) * (0.52f / (k - 1))
            val arrows: List<Triple<Float, Float, Color>> = when {
                ri == 0 -> emptyList()
                ri == 1 -> advD
                ri == rows.size - 1 -> advA
                else -> advM
            }
            val markerColor = when {
                ri == 0 -> Color(0xFFFFC83D)
                ri == 1 -> Color(0xFF3D8BFF)
                ri == rows.size - 1 -> Color(0xFFFF5C5C)
                else -> Color(0xFF9B6BFF)
            }
            val sc = pv.scale(v)
            for ((j, pl) in row.withIndex()) {
                val u = 0.06f + 0.88f * (j + 1f) / (row.size + 1f)
                val foot = pv.p(u, v)
                // setas no chão, em perspectiva (dy negativo no ícone = para o ataque = v maior)
                for ((dx, dy, col) in arrows) {
                    val a = pv.p(u + dx * 0.045f, v - dy * 0.03f)
                    val b = pv.p(u + dx * 0.11f, v - dy * 0.085f)
                    arrow(a, b, col.copy(alpha = 0.95f), 3f * dp * sc)
                }
                val r = 17f * dp * sc
                // ficha do jogador (mesmo visual das ilustrações da tática), número da força no centro
                val tok = r * 1.05f
                val cy = foot.y - tok
                token(Offset(foot.x, cy), tok, markerColor, jersey = false)
                drawIntoCanvas { cv ->
                    if (pl.second > 0) {
                        numPaint.textSize = 12.5f * dp * sc
                        cv.nativeCanvas.drawText(pl.second.toString(), foot.x, cy + numPaint.textSize * 0.36f, numPaint)
                    }
                }
                if (pl.first.isNotBlank()) {
                    namePaint.textSize = 10f * dp * (0.75f + 0.25f * sc)
                    val label = if (pl.first.length > 12) pl.first.take(11) + "…" else pl.first
                    val tw = namePaint.measureText(label)
                    val top = foot.y + r * 0.1f
                    drawRoundRect(
                        Color(0xD90B1220), topLeft = Offset(foot.x - tw / 2f - 6f * dp, top),
                        size = Size(tw + 12f * dp, namePaint.textSize + 6f * dp), cornerRadius = CornerRadius(7f * dp, 7f * dp)
                    )
                    drawRoundRect(
                        markerColor, topLeft = Offset(foot.x - tw / 2f - 6f * dp, top),
                        size = Size(3f * dp, namePaint.textSize + 6f * dp), cornerRadius = CornerRadius(2f * dp, 2f * dp)
                    )
                    drawIntoCanvas { cv -> cv.nativeCanvas.drawText(label, foot.x, top + namePaint.textSize + 1.5f * dp, namePaint) }
                }
            }
        }
    }
}

/** Medidor circular (0..100) para os controles deslizantes do jogo. */
@Composable
private fun Gauge(label: String, value: Int, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(84.dp)) {
                val sw = 9.dp.toPx()
                val pad = sw / 2f
                val arc = Size(size.width - sw, size.height - sw)
                drawArc(C.SURFACE2, 135f, 270f, false, topLeft = Offset(pad, pad), size = arc, style = Stroke(sw, cap = StrokeCap.Round))
                drawArc(
                    Brush.sweepGradient(listOf(color.copy(alpha = 0.6f), color, color.copy(alpha = 0.6f))),
                    135f, 270f * value.coerceIn(0, 100) / 100f, false, topLeft = Offset(pad, pad), size = arc,
                    style = Stroke(sw, cap = StrokeCap.Round)
                )
            }
            Text(value.toString(), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Text(label, fontSize = 11.sp, color = C.MUTED, textAlign = TextAlign.Center, maxLines = 3)
    }
}

/** Chip de configuração (rótulo pequeno em cima, valor em destaque). */
@Composable
private fun SettingChip(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(C.SURFACE2).padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 10.sp, color = C.MUTED, maxLines = 1)
        Text(value.ifBlank { "—" }, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
        shape = RoundedCornerShape(10.dp)
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

/** Cor do selo da previsão: verde favorito, azul equilibrado, âmbar azarão (o número fica sempre branco). */
private fun winColor(win: Int): Color = when {
    win >= 55 -> C.OK
    win >= 38 -> C.PRIMARY
    else -> C.GOLD
}

private fun winIcon(win: Int): String = when {
    win >= 55 -> "💪"
    win >= 38 -> "⚖"
    else -> "🛡"
}

/** Selo com o veredito da previsão. */
@Composable
private fun VerdictChip(p: WinModel.Prob, big: Boolean = false) {
    val c = winColor(p.win)
    Box(Modifier.clip(RoundedCornerShape(50)).background(c.copy(alpha = 0.2f)).border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = if (big) 4.dp else 2.dp)) {
        Text(winIcon(p.win) + " " + p.verdict, fontSize = if (big) 13.sp else 10.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
    }
}

/** Barra V/E/D em três cores com o rótulo dentro de cada parte (quando cabe). */
@Composable
private fun WinBar(p: WinModel.Prob, height: Dp) {
    Row(Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))) {
        for ((v, col, lab) in listOf(Triple(p.win, C.WIN, "V"), Triple(p.draw, C.DRAW, "E"), Triple(p.loss, C.LOSS, "D"))) {
            Box(Modifier.weight(v.toFloat().coerceAtLeast(1f)).fillMaxSize().background(col), contentAlignment = Alignment.Center) {
                if (height >= 16.dp && v >= 12) Text("$lab $v%", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0B1220), maxLines = 1)
            }
        }
    }
}

/** Previsão do jogo: chance de vencer em destaque, veredito, placar provável e o que pesa a favor/contra. */
@Composable
private fun WinCard(p: WinModel.Prob?, compact: Boolean = false) {
    if (p == null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📈", fontSize = 22.sp)
            Text("  Previsão aparece ao gerar a tática", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.weight(1f))
        }
        return
    }
    if (compact) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${p.win}%", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(" de vencer ", fontSize = 12.sp, color = Color(0xCCFFFFFF), maxLines = 1)
            Box(Modifier.weight(1f)) { VerdictChip(p) }
            Text("${p.likely.first}x${p.likely.second}", fontSize = 12.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(4.dp))
        WinBar(p, 8.dp)
        return
    }
    Text("📈 PREVISÃO DO JOGO", fontSize = 11.sp, color = Color(0xB3FFFFFF), fontWeight = FontWeight.Bold)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("${p.win}%", fontSize = 40.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("chance de vencer", fontSize = 12.sp, color = Color(0xCCFFFFFF))
            Box(Modifier.padding(top = 3.dp)) { VerdictChip(p, big = true) }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text("placar provável", fontSize = 10.sp, color = Color(0xB3FFFFFF))
            Text("${p.likely.first} x ${p.likely.second}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
    Spacer(Modifier.height(8.dp))
    WinBar(p, 18.dp)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Vitória ${p.win}%", fontSize = 11.sp, color = C.WIN, fontWeight = FontWeight.SemiBold)
        Text("Empate ${p.draw}%", fontSize = 11.sp, color = C.DRAW, fontWeight = FontWeight.SemiBold)
        Text("Derrota ${p.loss}%", fontSize = 11.sp, color = C.LOSS, fontWeight = FontWeight.SemiBold)
    }
    val pros = p.factors.filter { it.pts >= 1.0 }.sortedByDescending { it.pts }.take(3)
    val cons = p.factors.filter { it.pts <= -1.0 }.sortedBy { it.pts }.take(3)
    if (pros.isNotEmpty() || cons.isNotEmpty()) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("A favor", fontSize = 11.sp, color = C.OK, fontWeight = FontWeight.Bold)
                if (pros.isEmpty()) Text("—", fontSize = 12.sp, color = C.MUTED)
                for (f in pros) Text("✓ ${f.label}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Contra", fontSize = 11.sp, color = C.BAD, fontWeight = FontWeight.Bold)
                if (cons.isEmpty()) Text("—", fontSize = 12.sp, color = C.MUTED)
                for (f in cons) Text("! ${f.label}", fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    Text("Confiança ${p.confidence}: quanto mais telas do rival lidas, mais precisa.", fontSize = 10.sp, color = Color(0x99FFFFFF), modifier = Modifier.padding(top = 8.dp))
}

/** Conferência entre telas: o mesmo dado lido no pré-jogo, calendário, análise e plantel do rival. */
@Composable
private fun EvidencePanel(ev: JSONObject?, cpu: Boolean = false) {
    if (ev == null || ev.length() == 0) return
    // rival CPU não tem apelido: a linha do apelido não se aplica
    val items = listOfNotNull(
        K.RIVAL_TEAM to "Próximo rival", if (cpu) null else K.RIVAL_NICK to "Apelido (rival humano)", K.HOME to "Casa/fora",
        K.RIVAL_STRENGTH to "Força do rival", K.RIVAL_FORMATION to "Formação do rival"
    )
    Panel {
        Text("🔎 Conferência entre telas", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text("Um dado só é dado como certo quando aparece igual em telas diferentes.", fontSize = 11.sp, color = C.MUTED)
        for ((k, label) in items) {
            val vals = Evidence.values(ev, k)
            if (vals.isEmpty()) continue
            val top = vals.first()
            val srcs = top.sources.joinToString(" + ") { Evidence.sourceLabel(it) }
            val (icon, color, text) = when {
                vals.size > 1 -> Triple("⚠", C.WARN, vals.joinToString("  ×  ") { b -> b.value + " (" + b.sources.joinToString("+") { Evidence.sourceLabel(it) } + ")" })
                top.weight >= 2 -> Triple("✔", C.OK, "${top.value} — confirmado: $srcs")
                else -> Triple("•", C.MUTED, "${top.value} — só em $srcs (aguardando outra tela)")
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Text(icon, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontSize = 11.sp, color = C.MUTED)
                    Text(text, fontSize = 12.sp, color = if (icon == "•") Color.White else color)
                }
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 10.dp)) {
        Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
        Text(" $label", fontSize = 11.sp, color = C.MUTED)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlotTactic(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    if (Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())) {
        SeasonEndCard(d)
        Hint(Director.ENDED_MSG)
        return
    }
    if (d.rivalChanged.isNotEmpty()) RivalChangedCard(d.rivalChanged) { startGeneration(ctx, "tactic", slot) }
    d.rivalPred?.takeIf { it.guesses.isNotEmpty() }?.let { RivalPredictCard(it, known(d.fields, K.RIVAL_NICK)) }
    // Checklist: o que ainda falta ler e em qual tela do OSM (a tática sai melhor com tudo lido).
    val secret = SecretTraining.on(d.fields)
    val human = secret || fv(d, K.RIVAL_HUMAN) == "Sim"
    // Treino secreto (cadeado): força e relatório do analista não existem neste jogo, não "faltam".
    val checks = listOfNotNull(
        Pair("Força do rival", "Pré-jogo (círculo do rival)").takeIf { !secret && known(d.fields, K.RIVAL_STRENGTH) == null },
        Pair("Setores do rival (GOL/DEF/MEI/ATA)", "Plantel do rival").takeIf { known(d.fields, K.RIVAL_DEF) == null || known(d.fields, K.RIVAL_ATK) == null },
        Pair("Formação do rival", "Análise do rival").takeIf { known(d.fields, K.RIVAL_FORMATION) == null },
        Pair("Estilo do rival", "Análise do rival").takeIf { !secret && known(d.fields, K.RIVAL_PLAN) == null },
        Pair("Árbitro", "Pré-jogo (termômetro)").takeIf { known(d.fields, K.REFEREE) == null },
        Pair("Nome do usuário rival", "Pré-jogo ou plantel do rival").takeIf { human && known(d.fields, K.RIVAL_NICK) == null },
        Pair("Seu elenco", "Plantel do seu time (role até o fim)").takeIf { d.players.count { it.owner == "MY" && it.strength != null } < 14 }
    )
    if (d.conflicts.isNotEmpty()) {
        Panel {
            Text("⚠ Leituras que não batem — confira no jogo", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            for (c in d.conflicts) Text("• $c", fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            Text("Abra a tela de novo (a leitura repetida confirma) ou corrija o valor no Pré-jogo.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (d.unavailable.isNotEmpty()) {
        Panel {
            Text("⛔ ${d.unavailable.size} fora deste jogo (não entram na escalação)", fontSize = 13.sp, color = C.WARN, fontWeight = FontWeight.Bold)
            FlowRow(Modifier.padding(top = 6.dp)) {
                for (k in d.unavailable) Pill(d.players.firstOrNull { it.nameKey == k }?.name ?: k, false)
            }
            OutlinedButton(
                onClick = {
                    AppScope.scope.launch(Dispatchers.IO) {
                        val r = Repo(ctx)
                        for (k in d.unavailable) r.setUnavailable(slot, k, false)
                        UiBus.version++
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("✓ Liberar todos") }
            Hint("Marque quem está lesionado ou suspenso tocando no jogador na aba Elenco. Vale só para esta rodada.", "como marcar")
        }
    }
    if (secret) {
        Panel {
            Text("🔒 Rival fez TREINO SECRETO", color = OsmColors.YELLOW, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(
                "Rival humano. O jogo esconde a força, o bônus e o relatório do analista dele, então isso não falta ler. " +
                    (SecretTraining.estimate(d.fields)?.let { "Força estimada pelos setores do plantel: $it (com margem). " } ?: "Leia o Plantel do rival para eu estimar a força. ") +
                    "A tática vem mais segura: sem linha de impedimento e meio-campo protegido.",
                fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    if (checks.isNotEmpty()) {
        Panel {
            Text("📋 Falta ler ${checks.size} — a tática fica menos precisa", color = C.WARN, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            FlowRow(Modifier.padding(top = 6.dp)) {
                for ((what, _) in checks) Pill("✗ $what", false)
            }
            Hint(checks.joinToString("\n") { (what, where) -> "• $what → abra: $where" }, "onde ler")
        }
    } else {
        Text("✔ Tudo lido para este jogo", color = C.OK, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    GenPanel("tactic", slot, "⚡ Gerar tática para o próximo jogo") { startGeneration(ctx, "tactic", slot) }
    val plan = d.tactic
    if (plan == null) {
        EmptyState("⚡", "Nenhuma tática gerada ainda", "A tática é calculada na hora com o seu elenco, a força do rival, o árbitro e o que já deu certo ou errado antes. Toque no botão acima.")
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
    if (!Director.tacticValid(j, plan.at, d.fields, System.currentTimeMillis())) {
        Text(
            "⚠ A tática guardada foi gerada para o jogo anterior (ou o jogo já começou). Toque em “Gerar tática” para criar a do próximo jogo.",
            color = C.WARN, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)
        )
        return
    }

    // A tela segue a do próprio OSM, na mesma ordem: Próximo jogo → Equipa inicial (formação) → Estilo de jogo e
    // Desarme → Táticas por sector → Pressão / Estilo / Temporização → Marcação e Fora-de-jogo.
    val style = Osm.style(j.optString("playStyle")) ?: j.optString("playStyle")
    val tackle = Osm.tackle(j.optString("tackle")) ?: j.optString("tackle")
    val att = Osm.attack(j.optString("advAttack")) ?: j.optString("advAttack")
    val mid = Osm.midfield(j.optString("advMid")) ?: j.optString("advMid")
    val def = Osm.defense(j.optString("advDef")) ?: j.optString("advDef")
    val marking = Osm.marking(j.optString("marking")) ?: j.optString("marking")
    val offside = j.optString("offside")
    val pressure = j.optInt("pressure")
    val mentality = j.optInt("mentality")
    val tempo = j.optInt("tempo")

    // ---- Próximo jogo
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).osmPanel().padding(12.dp)) {
        Text("Próximo jogo", fontSize = 11.sp, color = OsmColors.CAPTION, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Crest(slot, 46.dp)
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(known(d.fields, K.TEAM) ?: "Meu time", fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(known(d.fields, K.HOME)?.let { if (it == "Casa") "em casa" else "fora" } ?: "", fontSize = 11.sp, color = OsmColors.CAPTION)
            }
            StrengthBubble(known(d.fields, K.MY_STRENGTH))
            Text(
                " VS ", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = OsmColors.YELLOW,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
            )
            StrengthBubble(known(d.fields, K.RIVAL_STRENGTH) ?: if (secret) "🔒" else null)
            Column(Modifier.weight(1f).padding(end = 6.dp), horizontalAlignment = Alignment.End) {
                Text(j.optString("rival"), fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                Text(known(d.fields, K.RIVAL_NICK) ?: if (fv(d, K.RIVAL_HUMAN) == "Não") "CPU" else "", fontSize = 11.sp, color = OsmColors.CAPTION, maxLines = 1)
            }
            RivalShield(j.optString("rival"), 46.dp)
        }
        Text(
            (if (forRound > 0) "Jornada $forRound • " else "") + "tática gerada ${ago(plan.at)}",
            fontSize = 11.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
        )
        AiDecision(j)
        Spacer(Modifier.height(8.dp))
        WinCard(d.win)
    }

    // ---- Equipa inicial
    OsmTitle("Equipa inicial")
    Column(Modifier.fillMaxWidth().osmPanel().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        OsmCaption("Formação", j.optString("formation"), valueSize = 22)
        Spacer(Modifier.height(6.dp))
        LineupPitch(j)
        FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
            LegendDot(Color(0xFFFFC83D), "GOL")
            LegendDot(Color(0xFF3D8BFF), "DEF")
            LegendDot(Color(0xFF9B6BFF), "MEI")
            LegendDot(Color(0xFFFF5C5C), "ATA")
        }
        Text("O número na camisa é a força do titular.", fontSize = 11.sp, color = C.MUTED)
    }

    // ---- Tática
    OsmTitle("Define a tua tática para o próximo jogo")
    OsmSplitCard(
        left = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Estilo de jogo", style)
                StyleIllustration(style, Modifier.fillMaxWidth().height(118.dp).padding(top = 6.dp))
                OsmDots(Osm.STYLES.size, Osm.STYLES.indexOf(style), Modifier.padding(top = 4.dp))
            }
        },
        right = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Desarme", tackle)
                TackleIllustration(tackle, Modifier.size(width = 130.dp, height = 112.dp).padding(top = 6.dp))
                OsmDots(Osm.TACKLES.size, Osm.TACKLES.indexOf(tackle), Modifier.padding(top = 4.dp))
            }
        }
    )
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).osmPanel().padding(10.dp)) {
        Text("Táticas por sector", fontSize = 11.sp, color = OsmColors.CAPTION, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.CenterHorizontally))
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SectorIllustration(att, mid, def, Modifier.size(width = 128.dp, height = 172.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SectorChoice("Avançados", att, Osm.ATTACK)
                SectorChoice("Médios", mid, Osm.MIDFIELD)
                SectorChoice("Defesas", def, Osm.DEFENSE)
            }
        }
    }
    OsmSlider("Pressão", Osm.pressureLabel(pressure), pressure, Modifier.padding(vertical = 4.dp))
    OsmSlider("Estilo", Osm.mentalityLabel(mentality), mentality, Modifier.padding(vertical = 4.dp))
    OsmSlider("Temporização", Osm.tempoLabel(tempo), tempo, Modifier.padding(vertical = 4.dp))
    OsmSplitCard(
        left = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Marcação", marking)
                MarkingIllustration(marking, Modifier.size(width = 130.dp, height = 150.dp).padding(top = 6.dp))
                OsmDots(2, Osm.MARKING.indexOf(marking), Modifier.padding(top = 4.dp))
            }
        },
        right = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                OsmCaption("Fazer fora-de-jogo", offside)
                OffsideIllustration(offside, Modifier.size(width = 130.dp, height = 150.dp).padding(top = 6.dp))
                OsmDots(2, if (offside == "Sim") 1 else 0, Modifier.padding(top = 4.dp))
            }
        }
    )
    Spacer(Modifier.height(8.dp))
    GenPanel("tactic_ai", slot, "🤖 Consultar a IA de novo") { startGeneration(ctx, "tactic_ai", slot) }

    val lineupArr = j.optJSONArray("lineup")
    if (lineupArr != null && lineupArr.length() > 1) {
        Fold("📋", "Escalação por setor", "${lineupArr.length()} linhas") {
            for (i in 0 until lineupArr.length()) {
                val row = lineupArr.optJSONArray(i) ?: continue
                val label = if (i == 0) "GOL" else if (i == 1) "DEF" else if (i == lineupArr.length() - 1) "ATA" else "MEI"
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(catColor(label).copy(alpha = 0.25f)).padding(vertical = 3.dp), contentAlignment = Alignment.Center) {
                        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    FlowRow(Modifier.weight(1f)) {
                        for (k in 0 until row.length()) {
                            val o = row.optJSONObject(k) ?: continue
                            Pill(o.optString("n") + " " + o.optInt("s"))
                        }
                    }
                }
            }
        }
    }
    val ra = j.optJSONArray("rationale")
    if (ra != null && ra.length() > 0) {
        Fold("💡", "Por que esta tática", ra.length().toString()) {
            for (i in 0 until ra.length()) Text("• " + ra.optString(i), fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
    val rk = j.optJSONArray("ranking")
    if (rk != null && rk.length() > 1) {
        Fold("📊", "Outras formações calculadas", (rk.length() - 1).toString()) {
            val best = (0 until rk.length()).mapNotNull { rk.optJSONArray(it)?.optLong(1) }.maxOrNull()?.coerceAtLeast(1L) ?: 1L
            for (i in 0 until rk.length()) {
                val r = rk.optJSONArray(i) ?: continue
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. ${r.optString(0)}", fontSize = 12.sp, modifier = Modifier.width(96.dp))
                    Box(Modifier.weight(1f)) { Bar(((r.optLong(1) * 100) / best).toInt()) }
                    Text("  ${r.optLong(1)}", fontSize = 11.sp, color = C.MUTED)
                }
            }
        }
    }
}

/** Círculo da força do time, como ao lado do "VS" no jogo. */
@Composable
private fun StrengthBubble(v: String?) {
    Box(
        Modifier.size(34.dp).clip(RoundedCornerShape(50)).background(Color(0xFF0B1C3E)).border(2.dp, OsmColors.BLUE, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center
    ) { Text(v ?: "?", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White) }
}

/** Uma linha das táticas por sector: rótulo laranja, opção em branco e os pontinhos da posição na lista. */
@Composable
private fun SectorChoice(label: String, value: String, options: List<String>) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = OsmColors.ORANGE, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, textAlign = TextAlign.Center)
        OsmDots(options.size, options.indexOf(value), Modifier.padding(top = 2.dp))
    }
}

/** Titulares por setor (para achar o titular mais fraco de cada um). */
private val CORE_SLOTS = mapOf("GOL" to 1, "DEF" to 4, "MEI" to 4, "ATA" to 3)
private val TRAINER_ROLE = mapOf("avançados" to "ATA", "médios" to "MEI", "defesas" to "DEF", "guarda-redes" to "GOL", "universal" to "UNI")

private fun fmtM(m: Double?): String = if (m == null) "?" else if (m >= 1.0) "%.1fM".format(m).replace('.', ',') else "%.0fK".format(m * 1000)

/** Número grande de passo (1, 2, 3…) com título. */
@Composable
private fun StepHeader(n: Int, icon: String, title: String, badge: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(RoundedCornerShape(50)).background(C.PRIMARY), contentAlignment = Alignment.Center) {
            Text("$n", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 14.sp)
        }
        Text("  $icon $title", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (badge != null) Pill(badge)
    }
}

/**
 * Diretor do slot: um plano para o time evoluir rápido, em passos (treinadores, vendas, compra), começando pelo setor
 * mais fraco. Em batalha (poucas rodadas) a prioridade é força já.
 */
@Composable
private fun SlotDirector(slot: Int, d: SlotData) {
    val ctx = LocalContext.current
    d.progress?.let { EvolutionPanel(it) }
    val plan = d.marketPlan
    if (plan == null) {
        EmptyState("👕", "Leia o seu elenco primeiro", "No jogo: Plantel do SEU time, role a lista até o fim. Aí o diretor monta treinos, vendas e compras.")
        return
    }
    val mine = d.players.filter { it.owner == "MY" && it.strength != null }
    val battle = (known(d.fields, K.COMP_TYPE) ?: "").contains("Batalha", true) || (known(d.fields, K.COMPETITION) ?: "").contains("Batalha", true)
    // força dos setores: a lida no pré-jogo/plantel ou a média dos titulares
    val sectors = listOf("GOL" to K.MY_GOL, "DEF" to K.MY_DEF, "MEI" to K.MY_MID, "ATA" to K.MY_ATK).map { (c, k) ->
        val starters = mine.filter { it.cat == c }.sortedByDescending { it.strength }.take(CORE_SLOTS[c] ?: 3)
        val v = known(d.fields, k)?.toIntOrNull() ?: starters.mapNotNull { it.strength }.takeIf { it.isNotEmpty() }?.average()?.toInt()
        Triple(c, v, starters.lastOrNull())
    }
    val weakest = sectors.filter { it.second != null }.minByOrNull { it.second!! }

    // ---- onde o time está
    Box(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(Color(0xFF0E2A5A), Color(0xFF16213D))))
            .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🚀 Plano para evoluir o time", fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.weight(1f))
                if (battle) Pill("⚔ Batalha", true)
            }
            if (battle) {
                Text(
                    "Batalha tem poucas rodadas: vale força JÁ. Treine quem joga, venda quem não entra e compre jogador pronto no setor mais fraco.",
                    fontSize = 12.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 4.dp)
                )
            }
            val lo = (sectors.mapNotNull { it.second }.minOrNull() ?: 50) - 8
            val hi = (sectors.mapNotNull { it.second }.maxOrNull() ?: 90) + 4
            for ((c, v, _) in sectors) {
                val isWeak = weakest?.first == c
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(44.dp).clip(RoundedCornerShape(8.dp)).background(catColor(c).copy(alpha = 0.3f)).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
                        Text(c, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f).height(12.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2)) {
                        if (v != null) Box(
                            Modifier.fillMaxWidth(((v - lo).toFloat() / (hi - lo).coerceAtLeast(1)).coerceIn(0.05f, 1f)).height(12.dp)
                                .clip(RoundedCornerShape(50)).background(if (isWeak) C.WARN else catColor(c))
                        )
                    }
                    Text("  " + (v?.toString() ?: "?"), fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, modifier = Modifier.width(40.dp))
                }
            }
            if (weakest != null) {
                Text("Foco: ${weakest.first} é o setor mais fraco (${weakest.second}).", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.WARN, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }

    // ---- 1. treinadores
    val seats = TrainBoard.seats(d.trainScreen, d.players, System.currentTimeMillis(), d.trainOv)
    val busy = seats.count { it.state == "active" && it.fresh }
    StepHeader(1, "🏋", "Treinadores", "$busy de 5")
    TrainingBoardView(seats)
    val freeRoles = seats.filter { it.state == "empty" || it.state == "done" || it.state == "unknown" }.map { it.role }
    val sugg = plan.train.filter { (TRAINER_ROLE[it.trainer] ?: "UNI") in freeRoles }
    if (sugg.isNotEmpty()) {
        for (t in sugg) {
            val role = TRAINER_ROLE[t.trainer] ?: "UNI"
            AlertCard("🔵 Treinador ${if (role == "UNI") "universal" else role}: coloque ${t.name} — ${t.reason}")
        }
    } else if (busy >= 5) {
        Text("✔ Os 5 treinadores estão trabalhando.", fontSize = 12.sp, color = C.OK, modifier = Modifier.padding(top = 4.dp))
    }
    if (seats.any { it.state == "unknown" }) Text("? = abra a tela de Treinamento no jogo para eu ver quem está em cada treinador.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))

    // ---- 2. vendas
    val proceeds = plan.sell.sumOf { it.valueM ?: 0.0 }
    StepHeader(2, "💸", "Vender", if (plan.sell.isEmpty()) "nada" else "≈ ${fmtM(proceeds)}")
    if (plan.sell.isEmpty()) Text("Ninguém sobrando no elenco agora.", fontSize = 12.sp, color = C.MUTED)
    for (sItem in plan.sell) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(catColor(sItem.cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                Text(sItem.strength?.toString() ?: "?", fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(sItem.name, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(sItem.reason, fontSize = 11.sp, color = C.MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text("≈ " + fmtM(sItem.valueM), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.OK)
        }
    }

    // ---- 3. compra
    StepHeader(3, "🛒", "Reforçar", "verba ${fmtM((plan.cashM ?: 0.0) + proceeds)}")
    if (plan.buy.isNotEmpty()) {
        for (b in plan.buy) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp)).background(C.SURFACE).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(catColor(b.cat).copy(alpha = 0.3f)), contentAlignment = Alignment.Center) {
                    Text(b.strength.toString(), fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(b.name + "  +" + b.gain, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text(b.reason, fontSize = 11.sp, color = C.MUTED, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(fmtM(b.priceM), fontWeight = FontWeight.Bold, fontSize = 13.sp, color = C.GOLD)
            }
        }
    } else if (weakest != null) {
        val ref = weakest.third?.strength
        Panel {
            Text(
                "Procure na Lista de transferências: ${weakest.first} com força ${if (ref != null) "${ref + 3} ou mais" else "acima dos seus"}" +
                    " até ${fmtM((plan.cashM ?: 0.0) + proceeds)}" + (if (battle) ", pronto para jogar (25–30 anos)." else ", de preferência até 26 anos (ainda cresce)."),
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold
            )
            if (ref != null && weakest.third != null) Text("Hoje o titular mais fraco de ${weakest.first} é ${weakest.third!!.name} (${ref}).", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Text("Role a lista no jogo com a captura ligada: eu leio os jogadores e digo qual comprar.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (plan.radar.isNotEmpty()) {
        Fold("📡", "Melhores alvos já lidos no mercado", plan.radar.size.toString()) {
            for (r in plan.radar) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${r.cat}: ${r.name} (${r.strength})", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(fmtM(r.priceM) + " • +${r.gain} no setor", fontSize = 11.sp, color = C.MUTED)
                    }
                    Pill(if (r.affordable) "cabe na verba" else "falta verba", r.affordable)
                }
            }
        }
    }
    val stadium = Director.stadiumPlan(d.fields)
    if (stadium != null) {
        StepHeader(4, "🏟", "Estádio")
        Text(stadium, fontSize = 12.sp)
    }
    Spacer(Modifier.height(10.dp))
    GenPanel("market_ai", slot, "🤖 Pedir a opinião da IA sobre este plano") { startGeneration(ctx, "market_ai", slot) }
    if (d.marketNote != null) {
        Panel {
            Text("🤖 Opinião da IA", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Text(d.marketNote, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
    Hint("Preços de venda são estimados pelo valor do jogador; confirme no jogo. Metas do elenco: 2 GOL, 6 DEF, 6 MEI e 4 ATA.", "como calculo")
}

/** Um jogo que já aconteceu (ou já começou): junta tática usada, card do calendário e análise lida do jogo. */
private data class PlayedRow(
    val round: Int,
    val formation: String?,
    val style: String?,
    val rival: String,
    val result: String?,
    val home: Boolean?,
    val report: JSONObject?,
    val match: MatchEntity?
)

private fun playedRows(d: SlotData): List<PlayedRow> {
    val logs = HashMap<Int, JSONObject>()
    for (p in d.logs) {
        val j = try { JSONObject(p.json) } catch (e: Exception) { continue }
        val r = j.optInt("round", -1)
        if (r > 0) logs[r] = j
    }
    val reports = HashMap<Int, JSONObject>()
    for (p in d.reports) {
        val r = p.kind.removePrefix("mr_R").toIntOrNull() ?: continue
        try { reports[r] = JSONObject(p.json) } catch (e: Exception) { }
    }
    val league = d.matches.filter { it.mkey.startsWith("L") && it.round != null }.associateBy { it.round!! }
    val pendingOk: (MatchEntity) -> Boolean = { it.result != null || it.scoreMine != null }
    val rounds = java.util.TreeSet<Int>()
    rounds.addAll(logs.keys)
    rounds.addAll(reports.keys)
    rounds.addAll(league.values.filter(pendingOk).mapNotNull { it.round })
    // Jogo cujo card (data e hora) já passou e ainda não tem placar: entra para registrar o resultado.
    Fixtures.awaitingResult(
        d.matches, reports.filter { it.value.has("sh") && it.value.has("sa") }.keys, System.currentTimeMillis(),
        known(d.fields, K.ROUND)?.toIntOrNull(), known(d.fields, K.MATCH_AT)?.toLongOrNull()
    )
        ?.round?.let { rounds.add(it) }
    // Copa da qual já fui eliminado: esses cards não são jogos meus.
    rounds.removeAll { r -> league[r]?.let { Fixtures.void(it, d.matches) } == true }
    // Só libera jogo que já aconteceu: com placar lido, com o horário do card já passado ou de data antiga.
    // Tática gerada para o próximo jogo NÃO é motivo para pedir resultado.
    val now = System.currentTimeMillis()
    val nextRound = known(d.fields, K.ROUND)?.toIntOrNull()
    rounds.removeAll { r ->
        val rep = reports[r]
        val m = league[r]
        val scored = (rep != null && rep.has("sh") && rep.has("sa")) || (m != null && (m.result != null || m.scoreMine != null))
        val happened = when {
            scored -> true
            m != null -> Fixtures.happened(m, now, nextRound, known(d.fields, K.MATCH_AT)?.toLongOrNull())
            nextRound != null && r == nextRound -> (known(d.fields, K.MATCH_AT)?.toLongOrNull() ?: Long.MAX_VALUE) <= now
            else -> nextRound != null && r < nextRound
        }
        !happened
    }
    return rounds.descendingSet().map { r ->
        val t = logs[r]
        val rep = reports[r]
        val m = league[r]
        val repHome = if (rep != null && rep.has("mineHome")) rep.optBoolean("mineHome") else null
        val home = m?.home ?: repHome ?: t?.let { if (it.isNull("home")) null else it.optBoolean("home") }
        val repRival = if (rep != null && repHome != null) (if (repHome) rep.optString("awayTeam") else rep.optString("homeTeam")).ifBlank { null } else null
        val rival = t?.optString("rival")?.takeIf { it.isNotBlank() && it != NI } ?: m?.let { Fixtures.opponent(it) } ?: repRival
            ?: m?.let { Fixtures.stage(it)?.let { st -> "Copa • $st" } } ?: "adversário"
        val tr = if (t == null || t.isNull("result")) null else t.optString("result")
        PlayedRow(
            r, t?.optString("formation")?.ifBlank { null }, t?.optString("playStyle")?.ifBlank { null },
            rival, m?.result ?: tr, home, rep, m
        )
    }
}

/** Valor do meu lado / do rival numa estatística do relatório ("posse de bola" -> ["55%", "45%"]). */
private fun repStat(rep: JSONObject?, label: String, mineHome: Boolean?, mine: Boolean): String? {
    val a = rep?.optJSONObject("stats")?.optJSONArray(label) ?: return null
    if (mineHome == null) return null
    val home = mineHome == mine
    return (if (home) a.optString(0) else a.optString(1)).ifBlank { null }
}

private fun digits(s: String?): String = s?.let { Regex("(\\d{1,3})").find(it)?.groupValues?.get(1) } ?: ""

@Composable
private fun SlotResult(slot: Int, d: SlotData, goTactic: () -> Unit) {
    val ctx = LocalContext.current
    val rows = playedRows(d)
    if (rows.isEmpty()) {
        Panel {
            EmptyState("📊", "Nenhum jogo disputado ainda", "Depois da partida, abra no OSM o calendário ou a análise do jogo: o app lê o placar sozinho e ele aparece aqui. Também dá para registrar à mão.")
            OutlinedButton(onClick = goTactic, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Ver a tática do próximo jogo") }
        }
        return
    }
    var sel by remember { mutableIntStateOf(rows.firstOrNull { it.result == null }?.round ?: rows.first().round) }
    val cur = rows.firstOrNull { it.round == sel } ?: rows.first()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
        for (r in rows.take(16)) {
            FilterPill("J${r.round}" + (if (r.result == null) " • pendente" else " " + r.result), r.round == cur.round) { sel = r.round }
        }
    }
    val rep = cur.report
    val repHome = if (rep != null && rep.has("mineHome")) rep.optBoolean("mineHome") else null
    val repScore = rep != null && rep.has("sh") && rep.has("sa") && repHome != null
    val calMine = cur.match?.scoreMine
    val calOpp = cur.match?.scoreOpp
    val initMine = when {
        rep != null && repScore -> (if (repHome == true) rep.optInt("sh") else rep.optInt("sa")).toString()
        calMine != null && calOpp != null -> calMine.toString()
        else -> ""
    }
    val initOpp = when {
        rep != null && repScore -> (if (repHome == true) rep.optInt("sa") else rep.optInt("sh")).toString()
        calMine != null && calOpp != null -> calOpp.toString()
        else -> ""
    }
    val stamp = listOf(cur.round, initMine, initOpp, cur.home, rep?.toString()?.hashCode()).joinToString("|")
    // Recria o formulário quando chega leitura nova (análise ou calendário), em vez de ficar com o valor vazio.
    key(stamp) {
        val home0 = repHome ?: cur.home
        var a by remember { mutableStateOf(initMine) }
        var b by remember { mutableStateOf(initOpp) }
        var home by remember { mutableStateOf(home0) }
        var poss by remember { mutableStateOf(digits(repStat(rep, "posse de bola", home0, true))) }
        var mShots by remember { mutableStateOf(digits(repStat(rep, "remates", home0, true))) }
        var oShots by remember { mutableStateOf(digits(repStat(rep, "remates", home0, false))) }
        var mFouls by remember { mutableStateOf(digits(repStat(rep, "faltas", home0, true))) }
        var oFouls by remember { mutableStateOf(digits(repStat(rep, "faltas", home0, false))) }
        var oForm by remember { mutableStateOf(repStat(rep, "formacao", home0, false)?.takeIf { it != NI } ?: "") }
        var mom by remember { mutableStateOf(rep?.optString("mom")?.ifBlank { null }?.takeIf { !Overlay.isChip(it) } ?: "") }
        var msg by remember { mutableStateOf<String?>(null) }

        // Placar grande, como no fim de jogo do OSM.
        val sa = a.toIntOrNull()
        val sb = b.toIntOrNull()
        val resColor = when {
            sa == null || sb == null -> C.SURFACE2
            sa > sb -> C.WIN
            sa == sb -> C.DRAW
            else -> C.LOSS
        }
        Box(
            Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(20.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF13284F), C.SURFACE)))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Jornada ${cur.round}" + (if (home == true) " • em casa" else if (home == false) " • fora" else ""), color = C.MUTED, fontSize = 12.sp)
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(known(d.fields, K.TEAM) ?: "Meu time", fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 2)
                    Box(Modifier.clip(RoundedCornerShape(14.dp)).background(resColor).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text((sa?.toString() ?: "–") + "  :  " + (sb?.toString() ?: "–"), fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    Text(cur.rival, fontWeight = FontWeight.Bold, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 2)
                }
                val origin = when {
                    repScore -> "✔ Lido da análise do jogo"
                    calMine != null -> "✔ Lido do calendário"
                    else -> "Ainda sem placar lido — preencha abaixo"
                }
                Text(origin, fontSize = 11.sp, color = if (initMine.isNotBlank()) C.OK else C.WARN, modifier = Modifier.padding(top = 8.dp))
                if (cur.formation != null) {
                    Text("Tática usada: ${cur.formation} • ${cur.style ?: "?"}", fontSize = 12.sp, color = C.GOLD, modifier = Modifier.padding(top = 2.dp))
                } else {
                    Text("Sem tática gerada neste jogo (o resultado ainda ensina a IA sobre o rival).", fontSize = 11.sp, color = C.MUTED, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }

        Panel {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = a, onValueChange = { a = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Meus gols") }, modifier = Modifier.weight(1f))
                OutlinedTextField(value = b, onValueChange = { b = it.filter { c -> c.isDigit() }.take(2) }, singleLine = true, label = { Text("Gols do rival") }, modifier = Modifier.weight(1f))
            }
            Row(Modifier.padding(top = 10.dp)) {
                FilterPill("🏠 Casa", home == true) { home = true }
                FilterPill("✈ Fora", home == false) { home = false }
            }
            Text("Estatísticas (ajudam a IA a aprender)", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 14.dp))
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
                        msg = "✔ Resultado salvo. A IA já usa este jogo para aprender."
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) { Text(if (cur.result == null) "Salvar resultado" else "Corrigir resultado", fontWeight = FontWeight.Bold) }
            if (!canSave) Text("Informe os gols dos dois times e se foi em casa ou fora.", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            val m = msg
            if (m != null) Text(m, color = C.OK, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Fim da competição (posição, pontos, campeão, valor) e o que a direção disse — entra no aprendizado e na IA. */
@Composable
private fun BoardPanel(items: List<String>) {
    if (items.isEmpty()) return
    Fold("🏁", "Fim da competição e direção", items.size.toString()) { BoardLines(items) }
}

@Composable
private fun BoardLines(items: List<String>) {
    for (t in items.take(8)) {
        val fired = Txt.norm(t).contains("demiss")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.Top) {
            Text(
                when {
                    t.startsWith("Direção") -> if (SeasonCard.mood(t) < 0) "😠" else if (SeasonCard.mood(t) > 0) "😀" else "💬"
                    t.startsWith("Campeão") -> "🏆"
                    t.startsWith("Valor") -> "💰"
                    t.startsWith("Fim") -> "🏁"
                    else -> "•"
                },
                fontSize = 14.sp, modifier = Modifier.width(24.dp)
            )
            Text(
                t, fontSize = 12.sp, color = if (fired) C.WARN else Color.White,
                fontWeight = if (fired) FontWeight.Bold else FontWeight.Normal, modifier = Modifier.weight(1f)
            )
        }
    }
    if (items.any { Txt.norm(it).contains("demiss") }) {
        Hint("A direção falou em demissão: na próxima época este slot pode ser de outro clube. O app recomeça o slot quando ler o novo time.", "demissão?")
    }
}

/** Um jogo analisado: placar, tática usada, o que deu errado/certo e os números (abre ao tocar). */
@Composable
private fun GameCard(g: Coach.Game, rv: Coach.Review?) {
    var open by remember { mutableStateOf(false) }
    val col = when (g.result) { "V" -> C.WIN; "E" -> C.DRAW; "D" -> C.LOSS; else -> C.SURFACE2 }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp)).background(C.SURFACE)
            .drawBehind { drawRect(col, size = Size(5.dp.toPx(), size.height)) }.clickable { open = !open }
            .padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ResultBadge(g.result)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "J${g.round} • " + (if (g.gf != null && g.ga != null) "${g.gf}-${g.ga} " else "") + "vs ${g.rival}",
                    fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (g.formation != null) "Sua tática: ${g.formation}" + (g.style?.let { " • $it" } ?: "") else "Tática não registrada no app",
                    fontSize = 11.sp, color = if (g.formation != null) C.GOLD else C.MUTED
                )
            }
            Text(if (open) "▲" else "▼", color = C.MUTED, fontSize = 12.sp)
        }
        if (rv != null) {
            for (w in rv.wrong.take(if (open) 9 else 2)) Text("✘ $w", fontSize = 12.sp, color = C.LOSS, modifier = Modifier.padding(top = 3.dp))
            for (r in rv.right.take(if (open) 9 else 1)) Text("✔ $r", fontSize = 12.sp, color = C.OK, modifier = Modifier.padding(top = 3.dp))
        }
        if (open) {
            if (g.poss != null) Duel("POSSE %", g.poss.toString(), (100 - g.poss).toString())
            if (g.shots != null || g.oppShots != null) Duel("REMATES", g.shots?.toString(), g.oppShots?.toString())
            val extra = listOfNotNull(g.fouls?.let { "suas faltas $it" }, g.oppFormation?.let { "formação do rival $it" }).joinToString(" • ")
            if (extra.isNotBlank()) Text(extra, fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            g.advice?.takeIf { it.isNotBlank() }?.let { Text("Analista: “$it”", fontSize = 11.sp, color = C.GOLD, modifier = Modifier.padding(top = 4.dp)) }
        }
    }
}

@Composable
private fun SlotLearning(slot: Int, d: SlotData) {
    d.progress?.let { ScoreboardCard(it.score) }
    LearnedCard(d.learned)
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
    val games = Coach.games(
        d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() },
        d.reports.mapNotNull { p ->
            runCatching { JSONObject(p.json).also { j -> if (!j.has("round")) p.kind.removePrefix("mr_R").toIntOrNull()?.let { j.put("round", it) } } }.getOrNull()
        }
    )
    val ended = Fixtures.seasonEnded(d.fields, d.matches, System.currentTimeMillis())
    val nextRival = if (ended) null else known(d.fields, K.RIVAL_TEAM)
    val lessons = Coach.lessons(games, nextRival)
    val logsJ = d.logs.mapNotNull { runCatching { JSONObject(it.json) }.getOrNull() }
    val (hit, graded) = Calib.accuracy(logsJ)
    val calib = Calib.offset(logsJ)

    if (ended) SeasonEndCard(d)
    BoardPanel(d.board)

    // ---- 1. o que muda no próximo jogo
    Panel {
        Text(if (ended) "🧠 Lições para a próxima competição" else "🧠 O que muda no próximo jogo", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        if (games.isEmpty()) {
            Text("Ainda sem jogos analisados", fontSize = 13.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
            Hint("Depois de cada jogo, abra a análise do jogo no OSM: eu leio posse, remates e faltas e digo onde errou.")
        } else {
            if (nextRival != null) Text("vs $nextRival", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            if (lessons.plan.isEmpty()) Text("Nada a mudar: manter o que vem funcionando.", fontSize = 13.sp, color = C.OK, modifier = Modifier.padding(top = 6.dp))
            for (line in lessons.plan) AlertCard("🔵 $line")
            Text("Isso já entra quando você toca em “Gerar tática”.", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
        }
    }

    // ---- 2. como o time vem jogando
    if (games.isNotEmpty()) {
        Panel {
            Text("📈 Como o time vem jogando", color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            val res = games.mapNotNull { it.result }
            FormGuide(res.take(10).reversed())
            fun avg(f: (Coach.Game) -> Int?): Int? = games.mapNotNull(f).takeIf { it.isNotEmpty() }?.average()?.toInt()
            val gf = games.mapNotNull { it.gf }.sum()
            val ga = games.mapNotNull { it.ga }.sum()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("⚽", "$gf×$ga", "gols pró×contra", if (gf >= ga) C.OK else C.LOSS, Modifier.weight(1f))
                avg { it.poss }?.let { StatTile("🔄", "$it%", "posse média", if (it >= 50) C.OK else C.WARN, Modifier.weight(1f)) }
                val sh = avg { it.shots }
                val osh = avg { it.oppShots }
                if (sh != null && osh != null) StatTile("🎯", "$sh×$osh", "remates/jogo", if (sh >= osh) C.OK else C.WARN, Modifier.weight(1f))
            }
            if (graded > 0) {
                Text(
                    "🔮 Acertei o resultado mais provável em $hit de $graded jogo(s)." +
                        (if (calib > 0.01) " O time rende acima do previsto: já ajustei para cima." else if (calib < -0.01) " O time rende abaixo do previsto: já ajustei para baixo." else ""),
                    fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        // ---- 3. jogo a jogo (um cartão por jogo; toque para ver os números)
        Title("Jogo a jogo")
        for (g in games.take(8)) GameCard(g, Coach.review(g))
    }

    // ---- 4. o que funciona por formação, estilo e contexto
    val groups = listOf(
        "Por formação" to Learning.stats(hist),
        "Por estilo de jogo" to Learning.byStyle(hist),
        "Por contexto" to Learning.byContext(hist)
    ).filter { it.second.isNotEmpty() }
    if (groups.isNotEmpty()) {
        Fold("📊", "O que funciona com as suas táticas", rows.count { it.result != null }.toString() + " jogo(s)") {
            for ((title, list) in groups) {
                Text(title, fontSize = 12.sp, color = C.MUTED, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                for (st in list) {
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(st.formation, fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.width(120.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        CountPill("${st.v}V", C.WIN)
                        CountPill("${st.e}E", C.DRAW)
                        CountPill("${st.d}D", C.LOSS)
                    }
                }
            }
        }
    }
    // ---- táticas geradas que ainda não têm resultado
    val noResult = rows.filter { it.result == null && it.score.isBlank() }
    if (noResult.isNotEmpty()) {
        Fold("📝", "Táticas sem resultado", noResult.size.toString()) {
            Text("Toque para informar o placar: assim o app aprende com ele.", fontSize = 11.sp, color = C.MUTED)
            for (r in noResult.take(8)) {
                Row(Modifier.fillMaxWidth().clickable { resultFor = r }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    ResultBadge(null)
                    Text("  J${r.round} • ${r.formation} vs ${r.rival}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("＋", color = C.PRIMARY, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
    val rf = resultFor
    if (rf != null) ResultDialog(slot, rf.kind, "J${rf.round} • ${rf.formation} vs ${rf.rival}") { resultFor = null }
    if (d.learning.isNotEmpty()) {
        Fold("🗂", "Registro do que o app leu", d.learning.size.toString()) {
            for (l in d.learning) {
                Text("${fmtTime(l.at)} • ${l.kind}: ${l.text}", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 2.dp))
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NotificationsPanel() {
    val ctx = LocalContext.current
    var pre by remember { mutableStateOf(Notifier.enabled(ctx, "pre")) }
    var tac by remember { mutableStateOf(Notifier.enabled(ctx, "tactic")) }
    var res by remember { mutableStateOf(Notifier.enabled(ctx, "result")) }
    var dir by remember { mutableStateOf(Notifier.enabled(ctx, "director")) }
    var tim by remember { mutableStateOf(Notifier.enabled(ctx, "timers")) }
    fun toggle(key: String, v: Boolean) {
        Settings.put(ctx, "notif_$key", if (v) "1" else "0")
        AppScope.scope.launch(Dispatchers.IO) { Notifier.reschedule(ctx.applicationContext) }
    }
    Fold("🔔", "Avisos no celular", if (Notifier.canPost(ctx)) "ligados" else "⚠") {
        Text(
            if (Notifier.canPost(ctx)) "Permissão concedida ✔" else "⚠ O Android ainda não liberou as notificações deste app.",
            fontSize = 12.sp, color = if (Notifier.canPost(ctx)) C.OK else C.WARN, modifier = Modifier.padding(top = 2.dp)
        )
        SwitchRow("20 min antes de cada jogo: reler o pré-jogo e a análise do rival", pre) { pre = it; toggle("pre", it) }
        SwitchRow("1 h antes, se a tática da rodada ainda não foi gerada", tac) { tac = it; toggle("tactic", it) }
        SwitchRow("30 min depois do jogo: registrar ou ler o resultado", res) { res = it; toggle("result", it) }
        SwitchRow("Alertas do diretor (ao encerrar a leitura e a cada hora, com o app fechado)", dir) { dir = it; toggle("director", it) }
        SwitchRow("Fim de cada treino e de cada obra do estádio (lidos nas telas do OSM)", tim) { tim = it; toggle("timers", it) }
        // Com o app fechado: alarme exato liberado e bateria sem restrição (senão o Android atrasa ou corta os avisos).
        val exact = Notifier.canExact(ctx)
        val battery = batteryUnrestricted(ctx)
        Text(
            (if (exact) "✔ Alarmes no horário exato" else "⚠ Alarmes exatos bloqueados: os avisos podem atrasar") + "\n" +
                (if (battery) "✔ Bateria sem restrição (avisos com o app fechado)" else "⚠ Bateria com restrição: o Android pode bloquear avisos com o app fechado"),
            fontSize = 12.sp, color = if (exact && battery) C.OK else C.WARN, modifier = Modifier.padding(top = 6.dp)
        )
        if (!exact && Build.VERSION.SDK_INT >= 31) {
            PendingRow("Permitir \"Alarmes e lembretes\"", "Liberar") {
                openSettings(ctx, android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, true)
            }
        }
        if (!battery) {
            PendingRow("Bateria sem restrição para este app", "Bateria") {
                openBatterySettings(ctx)
            }
        }
        Hint("Em celulares Xiaomi/Samsung/Motorola, ative também \"Inicialização automática\" e não deixe o app em \"suspensão\".", "avisos atrasando?")
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Notifier.test(ctx.applicationContext) }, modifier = Modifier.weight(1f)) { Text("Testar", fontSize = 12.sp) }
            OutlinedButton(
                onClick = {
                    Notifier.testLater(ctx.applicationContext)
                    Toast.makeText(ctx, "Feche o app: o aviso chega em 1 minuto.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.weight(1f)
            ) { Text("Testar fechado", fontSize = 12.sp) }
            if (!Notifier.canPost(ctx)) {
                OutlinedButton(
                    onClick = {
                        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(i)
                    },
                    modifier = Modifier.weight(1f)
                ) { Text("Liberar", fontSize = 12.sp) }
            }
        }
    }
}

/** Regras da comunidade em cartões: situação -> tática, de onde veio e como foi a última pesquisa. */
@Composable
private fun CommunityView(v: MetaRefresh.View?) {
    if (v == null) return
    val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StatTile("📚", v.rules.size.toString(), "situações", C.PRIMARY, Modifier.weight(1f))
        StatTile(if (v.active) "✅" else "⏸", if (v.active) "em uso" else "parado", "na tática", if (v.active) C.OK else C.WARN, Modifier.weight(1f))
        StatTile("🔎", if (v.searched == true) "sim" else if (v.searched == false) "não" else "?", "buscou na web", if (v.searched == true) C.OK else C.WARN, Modifier.weight(1f))
    }
    if (v.at != null) Text("Regras de ${fmt.format(Date(v.at))}" + (v.lastAt?.let { " • última pesquisa ${fmt.format(Date(it))}" } ?: ""), fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    if (v.running) AlertCard("🔵 Pesquisando agora na internet… " + AiStatus.stage.ifBlank { "aguarde" })
    v.problem?.let { AlertCard("⚠ $it") }
    val missing = listOf("U" to "mais fraco", "E" to "parelho", "F" to "mais forte").filter { (k, _) -> v.rules.none { it.rel == k } }
    if (v.rules.isNotEmpty() && missing.isNotEmpty() && !v.running) {
        AlertCard("⚠ Falta a situação: " + missing.joinToString(", ") { it.second } + ". Toque em Atualizar para a IA pesquisar só ela.")
    }
    if (v.rules.isEmpty()) {
        Text("Nenhuma regra da comunidade ainda: a tática usa o modelo do app e os seus resultados.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(top = 6.dp))
    }
    for (r in v.rules) {
        val (icon, title, col) = when (r.rel) {
            "U" -> Triple("🛡", "Quando você é MAIS FRACO", C.GOLD)
            "F" -> Triple("💪", "Quando você é MAIS FORTE", C.OK)
            else -> Triple("⚖", "Quando o jogo é PARELHO", C.PRIMARY)
        }
        Column(
            Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).background(C.SURFACE2)
                .drawBehind { drawRect(col, size = Size(5.dp.toPx(), size.height)) }.padding(start = 14.dp, end = 12.dp, top = 10.dp, bottom = 10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 18.sp)
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = col)
                    Text("contra " + r.vs, fontSize = 11.sp, color = C.MUTED)
                }
            }
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(r.formation, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0x33FFC83D)).padding(horizontal = 10.dp, vertical = 3.dp)) {
                    Text(r.style, color = C.GOLD, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((lab, v2) in listOf("Pressão" to r.pressure, "Estilo" to r.mentality, "Ritmo" to r.tempo)) {
                    if (v2 == null) continue
                    Column(Modifier.weight(1f)) {
                        Text("$lab $v2", fontSize = 10.sp, color = C.MUTED)
                        LinearProgressIndicator(progress = { v2 / 100f }, modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)), color = col, trackColor = C.BG)
                    }
                }
            }
            if (r.roles.isNotBlank()) Text(r.roles, fontSize = 11.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 6.dp))
            Text("Fonte: ${r.source}" + listOfNotNull(r.marking?.let { " • marcação $it" }, r.offside?.let { " • fora-de-jogo $it" }).joinToString(""), fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Para que serve a IA no app (bloquinho com desenho). */
/** Quem decidiu a tática: a IA (com o motivo) ou só o cálculo (com o porquê de a IA não ter entrado). */
@Composable
private fun AiDecision(j: JSONObject) {
    val ai = j.optJSONObject("ai")
    val used = ai?.optBoolean("used") == true
    val col = if (used) C.OK else C.WARN
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(col.copy(alpha = 0.14f)).padding(10.dp)
    ) {
        val head = when {
            used -> "🤖 Decidida pela IA" + (ai?.optString("by")?.takeIf { it.isNotBlank() }?.let { " ($it, ${ai.optDouble("secs", 0.0)}s)" } ?: "")
            ai == null -> "📐 Só pelo cálculo: gere de novo com uma IA configurada em Ajustes"
            else -> "📐 Só pelo cálculo"
        }
        Text(head, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = col)
        val why = ai?.optString("why")?.takeIf { it.isNotBlank() }
        if (used && why != null) Text(why, fontSize = 11.sp, color = Color(0xDDFFFFFF), modifier = Modifier.padding(top = 3.dp))
        val problem = ai?.optString("problem")?.takeIf { it.isNotBlank() }
        if (!used && problem != null) Text("Motivo: $problem", fontSize = 11.sp, color = Color(0xCCFFFFFF), modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun AiUseTile(icon: String, title: String, sub: String, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).background(C.SURFACE2).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(icon, fontSize = 20.sp)
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(sub, fontSize = 9.sp, color = C.MUTED, textAlign = TextAlign.Center)
    }
}

/** Um menu só para a IA principal: automático (pelo teste) ou uma escolhida. */
@Composable
private fun AiPrimaryPicker(pref: String, tick: Int, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    val opts = listOf("auto" to "🤖 Automático (pelo teste)") + AiClient.ALL.map { it to AiClient.label(it) }
    val cur = opts.firstOrNull { it.first == pref }?.second ?: opts.first().second
    Column(Modifier.fillMaxWidth()) {
        Text("IA principal", fontSize = 12.sp, color = C.MUTED)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
                Text(cur, modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                Text("▾")
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                for ((id, name) in opts) {
                    val t = if (id == "auto") null else AiClient.lastTest(ctx, id)
                    val st = when {
                        id == "auto" -> ""
                        !AiClient.hasKey(ctx, id) -> "  • sem chave"
                        t == null -> "  • não testada"
                        t.image -> "  ✔"
                        t.text -> "  ◐ só texto"
                        else -> "  ✘"
                    }
                    DropdownMenuItem(text = { Text(name + st) }, onClick = { open = false; onPick(id) })
                }
            }
        }
        val order = if (tick >= 0) AiClient.providerOrder(ctx, true) else emptyList()
        Text(
            if (order.isEmpty()) "⚠ Nenhuma IA com chave: cole pelo menos uma chave grátis abaixo."
            else "Ordem para ler telas agora: " + order.joinToString(" → ") { AiClient.label(it) } + ". Se uma falhar, passa para a próxima.",
            fontSize = 11.sp, color = if (order.isEmpty()) C.WARN else C.MUTED, modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Cartão de uma IA: luz de status do último teste; a chave e o resto aparecem ao tocar. */
@Composable
private fun AiProviderCard(id: String, icon: String, title: String, tag: String, where: String, key: String, tick: Int, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var open by rememberSaveable("ai_$id") { mutableStateOf(false) }
    val t = if (tick >= 0) AiClient.lastTest(ctx, id) else null
    val saved = AiClient.hasKey(ctx, id)
    val (col, status) = when {
        key.isBlank() -> C.MUTED to "sem chave"
        !saved -> C.WARN to "toque em Salvar"
        t == null -> C.MUTED to "não testada"
        t.image -> C.OK to "✔ lê imagem • ${t.secs}s"
        t.text -> C.GOLD to "◐ só texto"
        else -> C.LOSS to "✘ não respondeu"
    }
    Column(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(C.SURFACE2)
            .border(1.dp, col.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(tag, fontSize = 10.sp, color = C.MUTED)
            }
            Box(Modifier.clip(RoundedCornerShape(50)).background(col.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text(status, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = col, maxLines = 1)
            }
            Text(if (open) " ▲" else " ▼", fontSize = 11.sp, color = C.MUTED)
        }
        if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
            Text("🔑 Chave em: $where", fontSize = 11.sp, color = C.PRIMARY)
            if (t != null && !t.image && t.error != null && key.isNotBlank()) {
                Text("Último teste: ${t.error}", fontSize = 11.sp, color = C.WARN, modifier = Modifier.padding(top = 4.dp))
            }
            if (t?.model != null) Text("Modelo usado: ${t.model}", fontSize = 10.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(4.dp))
            content()
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
    var mKey by remember { mutableStateOf(Settings.get(ctx, Settings.MISTRAL_KEY, "")) }
    var oKey by remember { mutableStateOf(Settings.get(ctx, Settings.OPENROUTER_KEY, "")) }
    var cap by remember { mutableStateOf(Settings.get(ctx, Settings.DAILY_CAP, "80")) }
    var pref by remember { mutableStateOf(Settings.get(ctx, Settings.PREFERRED, "auto")) }
    var saved by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Ajustes", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
        NotificationsPanel()
        var spendBc by remember { mutableStateOf(Settings.get(ctx, "spend_bc", "0") == "1") }
        Fold("💎", "Boss Coins", if (spendBc) "gastar" else "preservar") {
            SwitchRow("Aceitar dicas que gastam Boss Coins (amistoso, estágio, treino secreto, olheiro imediato)", spendBc) {
                spendBc = it
                Settings.put(ctx, "spend_bc", if (it) "1" else "0")
            }
            Text("Desligado (padrão): o app nunca sugere gastar Boss Coins; só dicas que usam dinheiro comum ($).", fontSize = 12.sp, color = C.MUTED)
        }
        val exportScope = rememberCoroutineScope()
        var exportMsg by remember { mutableStateOf("") }
        var metaMsg by remember { mutableStateOf("") }
        Fold("🌐", "Táticas da comunidade (internet)", startOpen = true) {
            val metaTick = rememberTick(5000L)
            val mv = rememberLoaded<MetaRefresh.View?>(null, metaTick, metaMsg) { MetaRefresh.view(Repo(ctx)) }
            CommunityView(mv)
            OutlinedButton(
                onClick = {
                    metaMsg = ""
                    exportScope.launch { metaMsg = withContext(Dispatchers.IO) { MetaRefresh.refresh(ctx) } }
                },
                enabled = !MetaRefresh.running,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
            ) { Text(if (MetaRefresh.running) "🔎 Pesquisando…" else "🌐 Atualizar agora pela internet") }
            if (metaMsg.isNotBlank()) Text(metaMsg, fontSize = 12.sp, color = C.WARN)
            Hint(
                "A IA pesquisa (fórum do OSM, osmnextlevel, osmcoach, YouTube…) qual tática a comunidade usa em cada situação: quando você é mais fraco, " +
                    "parelho ou mais forte que o rival. O app usa isso como PONTO DE PARTIDA da tática; depois os resultados dos seus 4 slots ajustam por cima. " +
                    "Regras com mais de 30 dias deixam de valer. Atualiza sozinho uma vez por dia.",
                "como o app usa"
            )
        }
        Fold("📦", "Exportar diagnóstico") {
            Hint(
                "Gera um arquivo com TUDO o que o app tem (campos, elenco, calendário, análises do fim do jogo, táticas, telas lidas, " +
                    "registro de erros e as imagens guardadas; das chaves de IA vão só os 4 últimos caracteres). " +
                    "Fica salvo no celular (em Download/OSM Coach ou na pasta que você escolher) e você pode compartilhar depois.",
                "o que vai no arquivo"
            )
            var exported by remember { mutableStateOf<DiagExport.Result?>(null) }
            // pasta escolhida uma vez e guardada (o seletor do Android pede a permissão de gravar nela)
            var folderName by remember { mutableStateOf(DiagExport.folderLabel(ctx)) }
            val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
                if (uri != null) {
                    try {
                        DiagExport.setFolder(ctx, uri)
                        exportMsg = "Pasta escolhida: os próximos diagnósticos vão para ela."
                    } catch (e: Exception) {
                        exportMsg = "Essa pasta não deixou o app gravar: escolha outra. (" + (e.message ?: "") + ")"
                    }
                    folderName = DiagExport.folderLabel(ctx)
                }
            }
            KV("📁 Salvar em", folderName)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { pickFolder.launch(DiagExport.folder(ctx)) }, modifier = Modifier.weight(1f)) {
                    Text("📁 Escolher pasta", fontSize = 12.sp)
                }
                if (DiagExport.folder(ctx) != null) {
                    OutlinedButton(
                        onClick = {
                            DiagExport.setFolder(ctx, null)
                            folderName = DiagExport.folderLabel(ctx)
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Voltar ao padrão", fontSize = 12.sp) }
                }
            }
            OutlinedButton(
                onClick = {
                    exportMsg = "Gerando (pode levar alguns segundos com as imagens)…"
                    exportScope.launch {
                        try {
                            val r = withContext(Dispatchers.IO) { DiagExport.export(ctx) }
                            exported = r
                            exportMsg = DiagExport.summary(r)
                        } catch (e: Exception) {
                            exportMsg = "Não consegui gerar: " + (e.message ?: e.javaClass.simpleName)
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("📥 Exportar diagnóstico completo") }
            if (exportMsg.isNotBlank()) Text(exportMsg, fontSize = 12.sp, color = if (exported?.zipUri != null) C.OK else C.WARN)
            // desfazer perda de dados: volta elenco, calendário, campos e planos de um diagnóstico salvo
            var restoreRoot by remember { mutableStateOf<JSONObject?>(null) }
            var restoreMsg by remember { mutableStateOf("") }
            val pickDiag = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) {
                    exportScope.launch {
                        val r = withContext(Dispatchers.IO) { runCatching { DiagRestore.read(ctx, uri) }.getOrNull() }
                        if (r == null || DiagRestore.slotsIn(r).isEmpty()) restoreMsg = "Esse arquivo não é um diagnóstico do OSM Coach."
                        else restoreRoot = r
                    }
                }
            }
            OutlinedButton(
                onClick = { pickDiag.launch(arrayOf("application/json", "application/zip", "*/*")) },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
            ) { Text("♻ Restaurar dados de um diagnóstico") }
            if (restoreMsg.isNotBlank()) Text(restoreMsg, fontSize = 12.sp, color = if (restoreMsg.startsWith("Restaurado")) C.OK else C.WARN)
            restoreRoot?.let { root ->
                val infos = remember(root) { DiagRestore.slotsIn(root) }
                val chosen = remember(root) { mutableStateMapOf<Int, Boolean>().apply { for (i in infos) put(i.slot, i.players > 0 || i.matches > 0) } }
                val fmtD = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
                AlertDialog(
                    onDismissRequest = { restoreRoot = null },
                    title = { Text("Restaurar do diagnóstico de ${fmtD.format(Date(root.optLong("at")))}") },
                    text = {
                        Column {
                            Text("Escolha os slots. O que foi lido depois desse diagnóstico continua valendo.", fontSize = 12.sp, color = C.MUTED)
                            for (i in infos) {
                                Row(
                                    Modifier.fillMaxWidth().clickable { chosen[i.slot] = !(chosen[i.slot] ?: false) }.padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Switch(checked = chosen[i.slot] ?: false, onCheckedChange = { chosen[i.slot] = it })
                                    Spacer(Modifier.width(8.dp))
                                    Text("S${i.slot} ${i.team} • ${i.players} jogadores • ${i.matches} jogos", fontSize = 13.sp)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val sel = chosen.filterValues { it }.keys
                            restoreRoot = null
                            restoreMsg = "Restaurando…"
                            exportScope.launch {
                                restoreMsg = withContext(Dispatchers.IO) {
                                    runCatching { DiagRestore.restore(ctx, root, sel) }.getOrElse { "Erro ao restaurar: " + (it.message ?: "?") }
                                }
                                UiBus.version++
                            }
                        }) { Text("Restaurar") }
                    },
                    dismissButton = { TextButton(onClick = { restoreRoot = null }) { Text("Cancelar") } }
                )
            }
            exported?.let { r ->
                OutlinedButton(onClick = { DiagExport.share(ctx, r) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text("📤 Compartilhar o arquivo")
                }
            }
        }
        val readOk = serviceEnabled(ctx) && Diag.serviceConnected && batteryUnrestricted(ctx)
        Fold("📱", "Leitura automática", if (readOk) "ok" else "⚠", startOpen = !readOk) {
            StatusLight(serviceEnabled(ctx), "Acessibilidade ativada")
            StatusLight(Diag.serviceConnected && tick >= 0, "Serviço conectado")
            StatusLight(batteryUnrestricted(ctx), "Bateria sem restrição")
            Spacer(Modifier.height(6.dp))
            Button(onClick = { openSettings(ctx, android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS) }, modifier = Modifier.fillMaxWidth()) {
                Text("Abrir configurações de acessibilidade")
            }
            OutlinedButton(
                onClick = { openSettings(ctx, android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Info do app (permitir configurações restritas)") }
            OutlinedButton(
                onClick = { openBatterySettings(ctx) },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Bateria: não otimizar este app") }
        }
        val anyKey = listOf(gKey, cKey, mKey, oKey, clKey).any { it.isNotBlank() }
        Fold("🤖", "Inteligência artificial", if (!anyKey) "sem chave" else null, startOpen = !anyKey) {
            val saveAll = {
                Settings.put(ctx, Settings.GEMINI_KEY, gKey)
                Settings.put(ctx, Settings.GEMINI_MODEL, gModel.ifBlank { Settings.DEFAULT_GEMINI_MODEL })
                Settings.put(ctx, Settings.CLAUDE_KEY, clKey)
                Settings.put(ctx, Settings.CLAUDE_MODEL, clModel.ifBlank { Settings.DEFAULT_CLAUDE_MODEL })
                Settings.put(ctx, Settings.COMPAT_KEY, cKey)
                Settings.put(ctx, Settings.COMPAT_BASE, cBase.ifBlank { Settings.DEFAULT_COMPAT_BASE })
                Settings.put(ctx, Settings.COMPAT_MODEL, cModel.ifBlank { Settings.DEFAULT_COMPAT_MODEL })
                Settings.put(ctx, Settings.MISTRAL_KEY, mKey)
                Settings.put(ctx, Settings.OPENROUTER_KEY, oKey)
                Settings.put(ctx, Settings.DAILY_CAP, cap.ifBlank { "80" })
            }
            // o que cada IA faz no app: ler as telas (imagem), pensar a tática (texto) e pesquisar na internet
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AiUseTile("📷", "Ler telas", "OCR do celular + IA com imagem", Modifier.weight(1f))
                AiUseTile("🧠", "Tática", "IA de texto", Modifier.weight(1f))
                AiUseTile("🌐", "Internet", "Gemini ou Groq", Modifier.weight(1f))
            }
            AiPrimaryPicker(pref, tick) { pref = it; Settings.put(ctx, Settings.PREFERRED, it) }
            Text("Gratuitas", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.OK, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            AiProviderCard("gemini", "✨", "Gemini (Google)", "grátis • lê imagem • busca no Google", "aistudio.google.com › Get API key", gKey, tick) {
                OutlinedTextField(gKey, { gKey = it }, label = { Text("Chave(s) do Google AI Studio") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                KV("Cota hoje", AiClient.geminiStatus(ctx))
                Hint(
                    "Limite DIÁRIO por modelo e por projeto do Google. Pode colar várias chaves separadas por vírgula (cada uma de um PROJETO " +
                        "diferente): quando a cota de uma acaba, o app usa a próxima. \"Sobrecarregado (503)\" é o Google cheio naquele momento: " +
                        "o app pula para a próxima IA.",
                    "cota e várias chaves"
                )
                Fold("⚙", "Avançado") {
                    OutlinedTextField(gModel, { gModel = it }, label = { Text("Modelo (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cap, { cap = it }, label = { Text("Teto diário de chamadas") }, modifier = Modifier.fillMaxWidth())
                    KV("Chamadas hoje", AiClient.usedToday(ctx).toString())
                }
            }
            AiProviderCard("compat", "⚡", "Groq", "grátis • rápido • busca na internet", "console.groq.com › API Keys", cKey, tick) {
                OutlinedTextField(cKey, { cKey = it }, label = { Text("Chave do Groq") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Fold("⚙", "Avançado (xAI ou outro)") {
                    OutlinedTextField(cBase, { cBase = it }, label = { Text("URL base") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(cModel, { cModel = it }, label = { Text("Modelo de texto (auto = detecta sozinho)") }, modifier = Modifier.fillMaxWidth())
                }
            }
            AiProviderCard("openrouter", "🔀", "OpenRouter", "grátis (modelos :free) • lê imagem", "openrouter.ai/settings/keys (limite de crédito 0) • em Settings › Privacy ligue \"Allow free endpoints that train on request data\"", oKey, tick) {
                OutlinedTextField(oKey, { oKey = it }, label = { Text("Chave do OpenRouter") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            Text("Pagas (opcionais)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = C.GOLD, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            AiProviderCard("mistral", "🌀", "Mistral", "pago • o plano Free não libera chave de API", "console.mistral.ai › API Keys (só com plano pago)", mKey, tick) {
                OutlinedTextField(mKey, { mKey = it }, label = { Text("Chave da Mistral") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            }
            AiProviderCard("claude", "🅲", "Claude (Anthropic)", "pago • só com créditos no Console", "console.anthropic.com (a assinatura Pro/Max não inclui a API)", clKey, tick) {
                OutlinedTextField(clKey, { clKey = it }, label = { Text("Chave do Console da Anthropic") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(clModel, { clModel = it }, label = { Text("Modelo") }, modifier = Modifier.fillMaxWidth())
            }
            Button(
                onClick = {
                    saveAll()
                    saved = "Salvo. Testando cada IA…"
                    startAiTest(ctx)
                },
                enabled = !AiTest.running,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp)
            ) { Text(if (AiTest.running) "Testando… (${AiStatus.stage.ifBlank { "aguarde" }})" else "💾 Salvar e testar as IAs") }
            if (saved.isNotBlank() && !AiTest.running) Text(saved.removeSuffix(" Testando cada IA…"), color = C.OK, fontSize = 12.sp)
            if (AiTest.result.isNotBlank() && !AiTest.running) Text(AiTest.result, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            else AiClient.lastWebTest(ctx)?.let { (ok, text) -> StatusLight(ok, text.removePrefix("✔ ").removePrefix("✘ ")) }
        }
    }
}

/** O que o app aprendeu com os gols reais: cada escolha de tática e quanto ela mexeu nos gols. */
@Composable
private fun LearnedCard(m: Learned.Model?) {
    Title("🧠 O que os seus jogos ensinaram")
    Panel {
        if (m == null) {
            Text(
                "Preciso de pelo menos ${Learned.MIN_GAMES} jogos com placar (somando os 4 slots) para aprender o que funciona para você. " +
                    "Abra a análise de cada jogo depois que ele acabar.",
                color = C.MUTED, fontSize = 13.sp
            )
            return@Panel
        }
        Text(
            "Aprendido com ${m.n} jogos reais: a simulação usa isso para escolher a tática." +
                if (m.trusted) "" else " Com menos de ${Learned.TRUSTED_GAMES} jogos, as regras de segurança ainda valem.",
            color = C.MUTED, fontSize = 12.sp
        )
        Spacer(Modifier.height(6.dp))
        val rows = Learned.LABELS.indices.map { i -> Triple(Learned.LABELS[i], m.my[i], m.opp[i]) }
            .filter { kotlin.math.abs(it.second) >= 0.08 || kotlin.math.abs(it.third) >= 0.08 }
            .sortedByDescending { kotlin.math.abs(it.second) + kotlin.math.abs(it.third) }
        if (rows.isEmpty()) Text("Nenhuma escolha mudou os gols de forma clara até agora.", fontSize = 13.sp)
        for ((label, a, b) in rows) {
            val pa = ((kotlin.math.exp(a) - 1) * 100).toInt()
            val pb = ((kotlin.math.exp(b) - 1) * 100).toInt()
            // bom = mais gols meus e menos do rival
            val good = a - b
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (good >= 0.08) "▲" else if (good <= -0.08) "▼" else "•", color = if (good >= 0.08) C.OK else if (good <= -0.08) C.BAD else C.MUTED, fontSize = 16.sp)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "seus gols ${if (pa >= 0) "+" else ""}$pa% • gols do rival ${if (pb >= 0) "+" else ""}$pb%",
                        color = C.MUTED, fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/** Treinos e estádio de um slot, recolhido numa linha (abre com toque). Os relógios contam segundo a segundo. */
@Composable
private fun WorkFold(w: SlotWork, startOpenForTest: Boolean = false, onOpen: () -> Unit) {
    val tick = rememberTick(1000L)
    val now = System.currentTimeMillis() + tick * 0L
    val need = w.needsAction(now)
    val next = w.nextEnd(now)
    val badge = when {
        need > 0 -> "⚠ $need livre" + (if (need > 1) "s" else "")
        next != null -> "⏱ " + Timers.clock(next, now)
        else -> null
    }
    Fold("S${w.slot}", w.team, badge, startOpen = startOpenForTest) {
        Text("TREINADORES", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = C.MUTED, letterSpacing = 1.sp)
        if (w.seats.isEmpty()) {
            Text("Passe pela tela de Treinamento deste slot para ver os treinadores.", fontSize = 12.sp, color = C.MUTED, modifier = Modifier.padding(vertical = 4.dp))
        }
        for (s in w.seats) {
            val ended = s.state == "done" || (s.endAt != null && s.endAt <= now)
            WorkRow(
                icon = when (s.role) { "UNI" -> "⭐"; "ATA" -> "⚽"; "MEI" -> "🎯"; "DEF" -> "🛡"; else -> "🧤" },
                title = s.name ?: "Ninguém treinando",
                sub = Timers.roleLabel(s.role).replaceFirstChar { it.uppercase() },
                status = when {
                    s.state == "empty" -> Pair("Livre", C.WARN)
                    ended -> Pair("Reclamar", C.OK)
                    s.endAt != null -> Pair("⏱ " + Timers.clock(s.endAt, now), C.PRIMARY)
                    else -> Pair("Treinando", C.PRIMARY)
                }
            )
        }
        Spacer(Modifier.height(10.dp))
        Text("ESTÁDIO", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = C.MUTED, letterSpacing = 1.sp)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in w.cards) StadiumCard(c, now, Modifier.weight(1f))
        }
        Text(
            "Abrir o slot ›", color = C.PRIMARY, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 10.dp).clickable { onOpen() }
        )
    }
}

/** Card do estádio como no jogo: desenho, estrelas, barra do nível atual → próximo e o relógio ou o custo. */
@Composable
private fun StadiumCard(c: WorkCard, now: Long, modifier: Modifier = Modifier) {
    val building = c.endAt != null && c.endAt > now
    val ended = c.endAt != null && c.endAt <= now
    val accent = when {
        building -> C.PRIMARY
        ended -> C.OK
        c.next == "máximo" -> C.GOLD
        else -> Color(0xFF5B7BB5)
    }
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).background(Color(0xFF0E2550))
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.9f), accent.copy(alpha = 0.25f))), RoundedCornerShape(14.dp))
    ) {
        Box(Modifier.fillMaxWidth().height(78.dp)) {
            StadiumArt(c.label, building, Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x99000000), Color(0x00000000), Color(0x66000000)))))
            Column(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(c.label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                val lv = c.level ?: 0
                Row {
                    for (i in 1..maxOf(3, lv)) Text("★", fontSize = 12.sp, color = if (i <= lv) Color(0xFFFFD24A) else Color(0x88FFFFFF))
                }
            }
        }
        // barra: nível atual → próximo
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(c.level?.toString() ?: "?", fontSize = 10.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
            Box(Modifier.weight(1f).padding(horizontal = 4.dp).height(5.dp).clip(RoundedCornerShape(50)).background(Color(0xFF1B3D7A))) {
                if (building || ended) Box(
                    Modifier.fillMaxHeight().fillMaxWidth(if (ended) 1f else 0.5f)
                        .background(Brush.horizontalGradient(listOf(Color(0xFF3DB2FF), Color(0xFF7FD3FF))))
                )
            }
            Text(c.level?.let { (it + 1).toString() } ?: "?", fontSize = 10.sp, color = C.MUTED, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, bottom = 6.dp).clip(RoundedCornerShape(9.dp))
                .background(accent.copy(alpha = 0.18f)).padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                when {
                    building -> Timers.clock(c.endAt!!, now)
                    ended -> "Concluir"
                    c.next == "máximo" -> "Máximo"
                    c.next != null -> c.next
                    else -> "Não lido"
                },
                fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (accent == Color(0xFF5B7BB5)) Color(0xFFCFE0FF) else accent, maxLines = 1
            )
        }
    }
}

@Composable
private fun WorkRow(icon: String, title: String, sub: String, status: Pair<String, Color>?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.SURFACE2),
            contentAlignment = Alignment.Center
        ) { Text(icon, fontSize = 14.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 11.sp, color = C.MUTED, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (status != null) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(status.second.copy(alpha = 0.16f))
                    .border(1.dp, status.second.copy(alpha = 0.45f), RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 3.dp)
            ) { Text(status.first, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = status.second, maxLines = 1) }
        }
    }
}

@Composable
private fun TipCard(t: Playbook.Tip, onClick: () -> Unit) {
    val c = when {
        t.priority >= 90 -> C.BAD
        t.priority >= 75 -> C.WARN
        else -> C.PRIMARY
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
            .background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.16f), C.SURFACE2.copy(alpha = 0.5f))))
            .border(1.dp, c.copy(alpha = 0.45f), RoundedCornerShape(14.dp)).clickable { onClick() }.padding(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            Text(t.icon, fontSize = 17.sp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(t.why, fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 2.dp))
        }
        Text("›", fontSize = 18.sp, color = C.MUTED)
    }
}

/** Táticas mais prováveis do rival humano, pelo que ele já usou contra você e contra os outros slots. */
@Composable
private fun RivalPredictCard(v: RivalPredict.View, nick: String?) {
    Panel {
        Text("🔮 O que ${nick ?: "o rival"} deve usar", fontWeight = FontWeight.Bold, fontSize = 15.sp)
        Text(
            "Pelas ${v.guesses.sumOf { it.seen }} tática(s) dele já lidas (as recentes pesam mais). A simulação testa a sua tática contra estas.",
            fontSize = 11.sp, color = C.MUTED
        )
        for (g in v.guesses.take(3)) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(g.formation + (g.style?.let { " • $it" } ?: ""), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${g.share}%", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = C.GOLD)
            }
            Box(Modifier.fillMaxWidth().padding(top = 3.dp).height(5.dp).clip(RoundedCornerShape(50)).background(C.SURFACE2)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(g.share / 100f).background(Brush.horizontalGradient(listOf(Color(0xFFFFB74D), Color(0xFFFFD24A)))))
            }
        }
        v.switchRate?.let {
            Text(
                "Trocou a tática na última hora em ${v.switches} de ${v.checked} jogos ($it%)" + if (it >= 40) ": releia a análise dele 20 min antes." else ".",
                fontSize = 12.sp, color = if (it >= 40) C.WARN else C.MUTED, modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

/** Quais telas do OSM visitar para o app voltar a decidir com dados novos (por slot, com o caminho no menu). */
@Composable
private fun NeedsCard(needs: List<Fresh.Need>) {
    val urgent = needs.count { it.urgency >= 2 }
    val shown = needs.filter { it.urgency >= 1 }
    Fold("🔄", "Telas para atualizar", if (urgent > 0) "⚠ $urgent agora" else "${shown.size}", startOpen = urgent > 0) {
        Text("O app só decide bem com o que leu há pouco. Abra estas telas no OSM (com a captura ligada):", fontSize = 12.sp, color = C.MUTED)
        for (n in shown.take(12)) {
            val c = if (n.urgency >= 2) C.BAD else C.WARN
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Box(Modifier.clip(RoundedCornerShape(9.dp)).background(c.copy(alpha = 0.2f)).border(1.dp, c.copy(alpha = 0.5f), RoundedCornerShape(9.dp)).padding(horizontal = 7.dp, vertical = 3.dp)) {
                    Text(if (n.slot > 0) "S${n.slot}" else "Geral", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = c)
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(Fresh.NAME[n.type] ?: n.type.name, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text(n.reason.replaceFirstChar { it.uppercase() }, fontSize = 11.sp, color = C.MUTED)
                    Text(Fresh.PATH[n.type] ?: "", fontSize = 11.sp, color = C.PRIMARY)
                }
            }
        }
        val rest = needs.count { it.urgency == 0 }
        if (rest > 0) Text("+ $rest tela(s) sem pressa (estatísticas, negociações…).", fontSize = 11.sp, color = C.MUTED, modifier = Modifier.padding(top = 8.dp))
    }
}
