from pathlib import Path

R = Path(".")
pkg = R / "android-collector/app/src/main/java/com/osmaicoach/collector"

# ---------- Persistent native slot model ----------
(pkg / "NativeSlotStore.kt").write_text(r"""package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NativeSlotData(
    val id: Int,
    var team: String = "NI",
    var competition: String = "NI",
    var competitionType: String = "Liga normal",
    var nextRival: String = "NI",
    var matchDate: String = "NI",
    var matchTime: String = "NI",
    var venue: String = "NI",
    var referee: String = "NI",
    var myStrength: String = "NI",
    var rivalStrength: String = "NI",
    var myValue: String = "NI",
    var rivalValue: String = "NI",
    var myGoalkeeper: String = "NI",
    var myDefense: String = "NI",
    var myMidfield: String = "NI",
    var myAttack: String = "NI",
    var rivalGoalkeeper: String = "NI",
    var rivalDefense: String = "NI",
    var rivalMidfield: String = "NI",
    var rivalAttack: String = "NI",
    var rivalFormation: String = "NI",
    var rivalPlan: String = "NI",
    var marking: String = "NI",
    var offside: String = "NI",
    var secretTraining: String = "NI",
    var trainingCamp: String = "NI",
    var stadium: String = "NI",
    var bonus: String = "NI",
    var squadCount: Int = 0,
    var attackers: Int = 0,
    var midfielders: Int = 0,
    var defenders: Int = 0,
    var goalkeepers: Int = 0,
    var trainingCount: Int = 0,
    var sellingCount: Int = 0,
    var calendarCount: Int = 0,
    var marketSeen: Boolean = false,
    var trainingSeen: Boolean = false,
    var lastUpdated: Long = 0L
)

class NativeSlotStore(context: Context) {
    private val prefs = context.getSharedPreferences("native_slots_v2", Context.MODE_PRIVATE)

    fun loadAll(): MutableList<NativeSlotData> {
        val raw = prefs.getString("slots", null) ?: return MutableList(4) { NativeSlotData(it + 1) }
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(4) { index ->
                val o = if (index < arr.length()) arr.getJSONObject(index) else JSONObject()
                NativeSlotData(
                    id=index+1,
                    team=o.optString("team","NI"),
                    competition=o.optString("competition","NI"),
                    competitionType=o.optString("competitionType","Liga normal"),
                    nextRival=o.optString("nextRival","NI"),
                    matchDate=o.optString("matchDate","NI"),
                    matchTime=o.optString("matchTime","NI"),
                    venue=o.optString("venue","NI"),
                    referee=o.optString("referee","NI"),
                    myStrength=o.optString("myStrength","NI"),
                    rivalStrength=o.optString("rivalStrength","NI"),
                    myValue=o.optString("myValue","NI"),
                    rivalValue=o.optString("rivalValue","NI"),
                    myGoalkeeper=o.optString("myGoalkeeper","NI"),
                    myDefense=o.optString("myDefense","NI"),
                    myMidfield=o.optString("myMidfield","NI"),
                    myAttack=o.optString("myAttack","NI"),
                    rivalGoalkeeper=o.optString("rivalGoalkeeper","NI"),
                    rivalDefense=o.optString("rivalDefense","NI"),
                    rivalMidfield=o.optString("rivalMidfield","NI"),
                    rivalAttack=o.optString("rivalAttack","NI"),
                    rivalFormation=o.optString("rivalFormation","NI"),
                    rivalPlan=o.optString("rivalPlan","NI"),
                    marking=o.optString("marking","NI"),
                    offside=o.optString("offside","NI"),
                    secretTraining=o.optString("secretTraining","NI"),
                    trainingCamp=o.optString("trainingCamp","NI"),
                    stadium=o.optString("stadium","NI"),
                    bonus=o.optString("bonus","NI"),
                    squadCount=o.optInt("squadCount",0),
                    attackers=o.optInt("attackers",0),
                    midfielders=o.optInt("midfielders",0),
                    defenders=o.optInt("defenders",0),
                    goalkeepers=o.optInt("goalkeepers",0),
                    trainingCount=o.optInt("trainingCount",0),
                    sellingCount=o.optInt("sellingCount",0),
                    calendarCount=o.optInt("calendarCount",0),
                    marketSeen=o.optBoolean("marketSeen",false),
                    trainingSeen=o.optBoolean("trainingSeen",false),
                    lastUpdated=o.optLong("lastUpdated",0L)
                )
            }
        }.getOrElse { MutableList(4) { NativeSlotData(it + 1) } }
    }

    fun saveAll(slots: List<NativeSlotData>) {
        val arr = JSONArray()
        slots.forEach { s ->
            arr.put(JSONObject().apply {
                put("team",s.team); put("competition",s.competition); put("competitionType",s.competitionType)
                put("nextRival",s.nextRival); put("matchDate",s.matchDate); put("matchTime",s.matchTime)
                put("venue",s.venue); put("referee",s.referee); put("myStrength",s.myStrength); put("rivalStrength",s.rivalStrength)
                put("myValue",s.myValue); put("rivalValue",s.rivalValue)
                put("myGoalkeeper",s.myGoalkeeper); put("myDefense",s.myDefense); put("myMidfield",s.myMidfield); put("myAttack",s.myAttack)
                put("rivalGoalkeeper",s.rivalGoalkeeper); put("rivalDefense",s.rivalDefense); put("rivalMidfield",s.rivalMidfield); put("rivalAttack",s.rivalAttack)
                put("rivalFormation",s.rivalFormation); put("rivalPlan",s.rivalPlan); put("marking",s.marking); put("offside",s.offside)
                put("secretTraining",s.secretTraining); put("trainingCamp",s.trainingCamp); put("stadium",s.stadium); put("bonus",s.bonus)
                put("squadCount",s.squadCount); put("attackers",s.attackers); put("midfielders",s.midfielders); put("defenders",s.defenders); put("goalkeepers",s.goalkeepers)
                put("trainingCount",s.trainingCount); put("sellingCount",s.sellingCount); put("calendarCount",s.calendarCount)
                put("marketSeen",s.marketSeen); put("trainingSeen",s.trainingSeen); put("lastUpdated",s.lastUpdated)
            })
        }
        prefs.edit().putString("slots", arr.toString()).apply()
    }
}
""", encoding="utf-8")

# ---------- Replace MainActivity with professional clickable-slot version ----------
(pkg / "MainActivity.kt").write_text(r"""package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {
    private lateinit var repo: SessionRepository
    private lateinit var slotStore: NativeSlotStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = SessionRepository(this)
        slotStore = NativeSlotStore(this)
        setContent { CoachTheme { NativeCoachApp() } }
    }

    @Composable
    private fun NativeCoachApp() {
        var tab by remember { mutableStateOf(0) }
        var selectedSlot by remember { mutableStateOf<Int?>(null) }
        var refresh by remember { mutableIntStateOf(0) }

        DisposableEffect(Unit) {
            val listener: () -> Unit = { runOnUiThread { refresh++ } }
            CollectorState.addListener(listener)
            onDispose { CollectorState.removeListener(listener) }
        }

        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1200)
                refresh++
            }
        }

        val serviceReady = isAccessibilityServiceEnabledRobust()
        val recording = CollectorState.isRecording()
        val latest = repo.latestSession()
        val sessions = repo.listSessions()
        val slots = slotStore.loadAll()

        Scaffold(
            containerColor = Color(0xFFF5F7F2),
            topBar = {
                Surface(color = Color(0xFF0F1E27), shadowElevation = 5.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFB8E34D)) {
                                Icon(Icons.Default.SportsSoccer, null, Modifier.padding(10.dp), tint = Color(0xFF0F1E27))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text("OSM AI Coach Pro", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                Text("Native Pro", color = Color(0xFFA8BDC7), fontSize = 12.sp)
                            }
                            Surface(shape = RoundedCornerShape(999.dp), color = if (serviceReady) Color(0xFF20372A) else Color(0xFF3A2D20)) {
                                Text(
                                    if (serviceReady) "LEITURA ON" else "LEITURA OFF",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (serviceReady) Color(0xFFB8E34D) else Color(0xFFFFC857),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            when {
                                recording -> "Lendo o OSM agora • você pode navegar normalmente"
                                serviceReady -> "Leitura automática pronta"
                                else -> "Ative a leitura automática uma única vez"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (!serviceReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text("Ativar leitura automática")
                            }
                        }
                    }
                }
            },
            bottomBar = {
                NavigationBar(containerColor = Color(0xFF0F1E27)) {
                    val labels = listOf("Hoje","Sessões","Slots","Diretor","Ajustes")
                    val icons = listOf(Icons.Default.Home,Icons.Default.Route,Icons.Default.Dashboard,Icons.Default.TrendingUp,Icons.Default.Settings)
                    labels.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i && selectedSlot == null,
                            onClick = { selectedSlot = null; tab = i },
                            icon = { Icon(icons[i], null) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFB8E34D),
                                selectedTextColor = Color(0xFFB8E34D),
                                unselectedIconColor = Color(0xFF9FB0B8),
                                unselectedTextColor = Color(0xFF9FB0B8),
                                indicatorColor = Color(0xFF21323C)
                            )
                        )
                    }
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                if (selectedSlot != null) {
                    SlotDetailScreen(slots[selectedSlot!! - 1], onBack = { selectedSlot = null })
                } else {
                    when (tab) {
                        0 -> TodayScreen(serviceReady, recording, latest) { openOsm() }
                        1 -> SessionsScreen(sessions)
                        2 -> SlotsScreen(slots, latest) { selectedSlot = it }
                        3 -> DirectorScreen(latest)
                        else -> SettingsScreen(serviceReady)
                    }
                }
            }
        }
    }

    @Composable
    private fun TodayScreen(serviceReady:Boolean, recording:Boolean, latest:CaptureSession?, openOsm:()->Unit) {
        LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(16.dp)) {
            item {
                Text("Seu dia, organizado.", fontSize=30.sp, fontWeight=FontWeight.Bold, color=Color(0xFF17242B))
                Text("O Coach acompanha tudo que você visita no OSM.", color=Color(0xFF68777E))
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White), shape=RoundedCornerShape(20.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Leitura automática", fontWeight=FontWeight.Bold, fontSize=18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if(recording) "Capturando sua navegação agora."
                            else if(serviceReady) "Ativa. Não precisa autorizar de novo."
                            else "Aguardando autorização do Android."
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick=openOsm, enabled=serviceReady&&!recording, modifier=Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.PlayArrow,null); Spacer(Modifier.width(8.dp)); Text("Abrir OSM")
                        }
                    }
                }
            }
            item { SessionSummaryCard(latest) }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Metric("Telas", latest?.frames?.size?.toString()?:"0", Modifier.weight(1f))
                    Metric("Tipos", latest?.frames?.map{it.screenType}?.distinct()?.size?.toString()?:"0", Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun SessionSummaryCard(session:CaptureSession?) {
        Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)), shape=RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text("Última sessão", fontWeight=FontWeight.Bold, fontSize=18.sp)
                if(session==null) Text("Nenhuma sessão capturada ainda.")
                else {
                    Text("${session.frames.size} telas preservadas")
                    Spacer(Modifier.height(8.dp))
                    val groups=session.frames.groupingBy{it.screenType}.eachCount()
                    Text(groups.entries.sortedByDescending{it.value}.joinToString(" • "){"${labelType(it.key)} ${it.value}"}, fontSize=13.sp, color=Color(0xFF55645B))
                }
            }
        }
    }

    @Composable
    private fun SessionsScreen(sessions:List<CaptureSession>) {
        LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Text("Jornada da sessão", fontSize=28.sp, fontWeight=FontWeight.Bold)
                Text("Tudo que você navegou fica registrado.", color=Color.Gray)
            }
            sessions.forEach { session ->
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White), shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text(formatTime(session.startedAt), fontWeight=FontWeight.Bold)
                            Text("${session.frames.size} telas • ${session.state}", fontSize=13.sp, color=Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            session.frames.takeLast(20).forEach { frame ->
                                Row(Modifier.fillMaxWidth().padding(vertical=5.dp), verticalAlignment=Alignment.CenterVertically) {
                                    Surface(shape=RoundedCornerShape(8.dp), color=typeColor(frame.screenType)) {
                                        Text(labelType(frame.screenType), Modifier.padding(horizontal=8.dp, vertical=4.dp), fontSize=11.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(frame.screenTitle, Modifier.weight(1f), maxLines=1)
                                    Text(SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt)), fontSize=11.sp, color=Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotsScreen(slots:List<NativeSlotData>, latest:CaptureSession?, onOpen:(Int)->Unit) {
        LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text("Seus 4 slots", fontSize=28.sp, fontWeight=FontWeight.Bold)
                Text("Toque em um slot para ver todos os dados.", color=Color.Gray)
            }

            items(slots) { slot ->
                val pct = completion(slot)
                Card(
                    modifier=Modifier.fillMaxWidth().clickable{onOpen(slot.id)},
                    colors=CardDefaults.cardColors(containerColor=Color.White),
                    shape=RoundedCornerShape(20.dp)
                ) {
                    Column(Modifier.padding(17.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Surface(shape=RoundedCornerShape(11.dp), color=Color(0xFF0F1E27)) {
                                Text("S${slot.id}", Modifier.padding(11.dp), color=Color(0xFFB8E34D), fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if(slot.team!="NI") slot.team else "Slot ${slot.id}", fontWeight=FontWeight.Bold, fontSize=18.sp)
                                Text(if(slot.competition!="NI") slot.competition else "Liga ainda não identificada", fontSize=12.sp, color=Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight,null,tint=Color.Gray)
                        }
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress={pct/100f},
                            modifier=Modifier.fillMaxWidth().height(7.dp),
                            color=Color(0xFF7AA526),
                            trackColor=Color(0xFFE8ECDF)
                        )
                        Spacer(Modifier.height(7.dp))
                        Text("$pct% preenchido", fontSize=12.sp, color=Color.Gray)
                        Spacer(Modifier.height(10.dp))
                        Text("Próximo rival: ${slot.nextRival}  •  Elenco: ${slot.squadCount}  •  Calendário: ${slot.calendarCount}", fontSize=13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotDetailScreen(slot:NativeSlotData, onBack:()->Unit) {
        LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                    Column {
                        Text("S${slot.id} · ${if(slot.team!="NI")slot.team else "Time NI"}", fontSize=26.sp, fontWeight=FontWeight.Bold)
                        Text("${completion(slot)}% dos campos identificados", color=Color.Gray)
                    }
                }
            }

            item { DetailSection("Liga e clube", listOf(
                "Meu time" to slot.team,
                "Competição" to slot.competition,
                "Tipo" to slot.competitionType,
                "Estádio" to slot.stadium,
                "Bônus" to slot.bonus
            ))}

            item { DetailSection("Próxima partida", listOf(
                "Rival" to slot.nextRival,
                "Data" to slot.matchDate,
                "Horário" to slot.matchTime,
                "Local" to slot.venue,
                "Árbitro" to slot.referee,
                "Minha força" to slot.myStrength,
                "Força rival" to slot.rivalStrength,
                "Meu elenco" to slot.myValue,
                "Elenco rival" to slot.rivalValue
            ))}

            item { DetailSection("Forças por setor", listOf(
                "Meu GOL" to slot.myGoalkeeper,
                "Minha DEF" to slot.myDefense,
                "Meu MEI" to slot.myMidfield,
                "Meu ATA" to slot.myAttack,
                "Rival GOL" to slot.rivalGoalkeeper,
                "Rival DEF" to slot.rivalDefense,
                "Rival MEI" to slot.rivalMidfield,
                "Rival ATA" to slot.rivalAttack
            ))}

            item { DetailSection("Análise do adversário", listOf(
                "Formação" to slot.rivalFormation,
                "Plano" to slot.rivalPlan,
                "Marcação" to slot.marking,
                "Impedimento" to slot.offside,
                "Treino secreto" to slot.secretTraining,
                "Campo de treinamento" to slot.trainingCamp
            ))}

            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White), shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Elenco", fontWeight=FontWeight.Bold, fontSize=18.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            MiniMetric("ATA",slot.attackers.toString(),Modifier.weight(1f))
                            MiniMetric("MEI",slot.midfielders.toString(),Modifier.weight(1f))
                            MiniMetric("DEF",slot.defenders.toString(),Modifier.weight(1f))
                            MiniMetric("GOL",slot.goalkeepers.toString(),Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Total: ${slot.squadCount} • Treinando: ${slot.trainingCount} • À venda: ${slot.sellingCount}")
                    }
                }
            }

            item { DetailSection("Mercado, calendário e evolução", listOf(
                "Calendário" to "${slot.calendarCount} jogos lidos",
                "Mercado visitado" to if(slot.marketSeen)"Sim" else "Não",
                "Treino visitado" to if(slot.trainingSeen)"Sim" else "Não"
            ))}

            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)), shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Campos ainda NI", fontWeight=FontWeight.Bold)
                        Text(missingFields(slot).ifEmpty{"Nenhum campo principal pendente."})
                    }
                }
            }
        }
    }

    @Composable
    private fun DetailSection(title:String, rows:List<Pair<String,String>>) {
        Card(colors=CardDefaults.cardColors(containerColor=Color.White), shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp)) {
                Text(title, fontWeight=FontWeight.Bold, fontSize=18.sp)
                Spacer(Modifier.height(10.dp))
                rows.forEach { (label,value) ->
                    Row(Modifier.fillMaxWidth().padding(vertical=7.dp)) {
                        Text(label, Modifier.weight(1f), color=Color.Gray, fontSize=13.sp)
                        Text(value, fontWeight=FontWeight.SemiBold, fontSize=13.sp)
                    }
                    HorizontalDivider(color=Color(0xFFEEF0EA))
                }
            }
        }
    }

    @Composable
    private fun MiniMetric(label:String,value:String,modifier:Modifier=Modifier) {
        Surface(modifier,shape=RoundedCornerShape(12.dp),color=Color(0xFFF0F4E9)) {
            Column(Modifier.padding(10.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(value,fontWeight=FontWeight.Bold,fontSize=20.sp)
                Text(label,fontSize=11.sp,color=Color.Gray)
            }
        }
    }

    @Composable
    private fun DirectorScreen(latest:CaptureSession?) {
        val markets=latest?.frames?.count{it.screenType=="market"}?:0
        val trainings=latest?.frames?.count{it.screenType=="training"}?:0
        LazyColumn(contentPadding=PaddingValues(20.dp), verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { Text("Diretor",fontSize=28.sp,fontWeight=FontWeight.Bold) }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Metric("Mercado",markets.toString(),Modifier.weight(1f))
                    Metric("Treinos",trainings.toString(),Modifier.weight(1f))
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Nada é descartado",fontWeight=FontWeight.Bold)
                        Text("Mercado, treino, estádio, classificação, resultados e outras telas continuam preservadas para análise.")
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(serviceReady:Boolean) {
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { Text("Configurações",fontSize=28.sp,fontWeight=FontWeight.Bold) }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Leitura automática",fontWeight=FontWeight.Bold)
                        Text(if(serviceReady)"Ativa. O botão não deve mais aparecer ao reabrir." else "Desativada no Android.")
                        if(!serviceReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}) { Text("Ativar") }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Metric(label:String,value:String,modifier:Modifier=Modifier) {
        Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text(value,fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text(label,color=Color.Gray)
            }
        }
    }

    private fun openOsm() {
        packageManager.getLaunchIntentForPackage(OSM_PACKAGE)?.let { startActivity(it) }
    }

    private fun isAccessibilityServiceEnabledRobust():Boolean {
        if (CollectorState.isServiceReady()) return true

        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this,OsmCaptureAccessibilityService::class.java)

        val enabledByManager = manager
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                serviceInfo.packageName == expected.packageName && serviceInfo.name.endsWith(expected.className.substringAfterLast('.'))
            }
        if(enabledByManager) return true

        val enabledSetting = Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabledSetting.split(':').any { raw ->
            raw.contains(packageName,ignoreCase=true) &&
            raw.contains(OsmCaptureAccessibilityService::class.java.simpleName,ignoreCase=true)
        }
    }

    private fun completion(s:NativeSlotData):Int {
        val values=listOf(
            s.team,s.competition,s.nextRival,s.matchDate,s.venue,s.referee,s.myStrength,s.rivalStrength,
            s.myValue,s.rivalValue,s.rivalFormation,s.rivalPlan,s.marking,s.offside,s.secretTraining,s.trainingCamp
        )
        val filled=values.count{it!="NI"&&it.isNotBlank()}
        return ((filled.toDouble()/values.size)*100).toInt()
    }

    private fun missingFields(s:NativeSlotData):String {
        val rows=listOf(
            "Meu time" to s.team,"Liga" to s.competition,"Rival" to s.nextRival,"Data" to s.matchDate,
            "Local" to s.venue,"Árbitro" to s.referee,"Minha força" to s.myStrength,"Força rival" to s.rivalStrength,
            "Meu valor" to s.myValue,"Valor rival" to s.rivalValue,"Formação rival" to s.rivalFormation,
            "Plano rival" to s.rivalPlan,"Marcação" to s.marking,"Impedimento" to s.offside,
            "Treino secreto" to s.secretTraining,"Campo de treinamento" to s.trainingCamp
        )
        return rows.filter{it.second=="NI"||it.second.isBlank()}.joinToString(" • "){it.first}
    }

    private fun formatTime(ms:Long)=SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault()).format(Date(ms))

    private fun labelType(type:String)=when(type){
        "calendar"->"Calendário";"squad"->"Elenco";"match"->"Análise";"market"->"Mercado"
        "training"->"Treino";"club"->"Clube";"ranking"->"Tabela";"result"->"Resultado"
        "tactics"->"Tática";else->"Outra"
    }

    private fun typeColor(type:String)=when(type){
        "market"->Color(0xFFFFE7C2);"training"->Color(0xFFE1F3FF);"calendar"->Color(0xFFE8F4DD)
        "squad"->Color(0xFFEDE7FF);"match"->Color(0xFFFFE1E1);else->Color(0xFFECEFEF)
    }
}

@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme=lightColorScheme(
            primary=Color(0xFF182832),
            secondary=Color(0xFF7AA526),
            background=Color(0xFFF6F7F2),
            surface=Color.White
        ),
        content=content
    )
}
""", encoding="utf-8")

print("Native Pro v2 applied.")
