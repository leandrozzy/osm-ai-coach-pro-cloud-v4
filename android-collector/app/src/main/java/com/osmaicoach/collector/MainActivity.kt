package com.osmaicoach.collector

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

    override fun onResume() {
        super.onResume()
        // Fallback: alguns aparelhos não emitem imediatamente um evento de
        // acessibilidade ao voltar para o Coach. Se o OSM foi visto depois do
        // último lançamento, finalizamos a sessão aqui também.
        android.os.Handler(mainLooper).postDelayed({
            val runtime=getSharedPreferences("collector_runtime",Context.MODE_PRIVATE)
            val launchAt=runtime.getLong("coach_launch_at",0L)
            val lastOsm=runtime.getLong("last_osm_seen_at",0L)
            if(launchAt>0L && lastOsm>=launchAt && runtime.getBoolean("osm_seen_in_session",false)){
                repo.finish()
                runtime.edit()
                    .putBoolean("osm_seen_in_session",false)
                    .putLong("coach_launch_at",0L)
                    .apply()
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        },900L)
    }

    @Composable
    private fun NativeCoachApp() {
        var tab by remember { mutableStateOf(0) }
        var selectedSlot by remember { mutableStateOf<Int?>(null) }
        var refresh by remember { mutableIntStateOf(0) }
        var processing by remember { mutableStateOf(NativeSessionProcessor.Progress()) }
        var forceProcessToken by remember { mutableIntStateOf(0) }

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

        // Do not key processing by refresh.
        // refresh changes every 1.2s for UI redraws. When it was a key,
        // Compose cancelled the active request and restarted it at 0/12.
        val latestForProcessing = repo.latestSession()
        val latestSessionId = latestForProcessing?.id ?: ""

        LaunchedEffect(latestSessionId, forceProcessToken) {
            val latestNow = repo.latestSession()
            if (latestNow?.state == "ready" && !processing.running) {
                val processor = NativeSessionProcessor(this@MainActivity, repo, slotStore)
                processor.processLatest(force=forceProcessToken > 0) { p ->
                    runOnUiThread {
                        processing = p
                        refresh++
                    }
                }
            }
        }

        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val serviceReady = servicePermission && serviceConnected
        val recording = CollectorState.isRecording()
        val latest = repo.latestSession()
        val sessions = repo.listSessions()
        val slots = slotStore.loadAll()

        LaunchedEffect(servicePermission, serviceConnected) {
            if (servicePermission && !serviceConnected) {
                runCatching {
                    val component = ComponentName(
                        this@MainActivity,
                        OsmCaptureAccessibilityService::class.java
                    )
                    packageManager.setComponentEnabledSetting(
                        component,
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP
                    )
                }
            }
        }

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
                                servicePermission -> "Leitura autorizada • reconectando serviço"
                                else -> "Ative a leitura automática uma única vez"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (!serviceReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text(if(servicePermission) "Reconectar leitura" else "Ativar leitura automática")
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
                        0 -> TodayScreen(serviceReady, recording, latest, processing, slots, onReprocess={ forceProcessToken++ }) { openOsm() }
                        1 -> SessionsScreen(sessions)
                        2 -> SlotsScreen(slots, latest) { selectedSlot = it }
                        3 -> DirectorScreen(latest)
                        else -> SettingsScreen(serviceReady, latest, processing, onReprocess={ forceProcessToken++ })
                    }
                }
            }
        }
    }

    @Composable
    private fun TodayScreen(
        serviceReady:Boolean,
        recording:Boolean,
        latest:CaptureSession?,
        processing:NativeSessionProcessor.Progress,
        slots:List<NativeSlotData>,
        onReprocess:()->Unit,
        openOsm:()->Unit
    ) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text("CENTRO DE COMANDO",fontSize=11.sp,color=Color(0xFF718078),fontWeight=FontWeight.Bold)
                Text("Seu OSM, em um só lugar.",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color(0xFF17242B))
                Text("Jogue normalmente. O Coach registra a sessão e atualiza seus quatro slots.",color=Color(0xFF68777E))
            }

            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF122630)),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Icon(if(recording)Icons.Default.FiberManualRecord else Icons.Default.Verified,null,
                                tint=if(recording)Color(0xFFFFC857) else Color(0xFFB8E34D))
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if(recording)"Sessão sendo capturada" else "Leitura automática pronta",
                                    color=Color.White,fontWeight=FontWeight.Bold,fontSize=17.sp)
                                Text(if(recording)"Navegue livremente pelo OSM."
                                    else "Permissão ativa e pronta para nova sessão.",
                                    color=Color(0xFFB6C6CE),fontSize=12.sp)
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        Button(
                            onClick=openOsm,
                            enabled=serviceReady&&!recording&&!processing.running,
                            modifier=Modifier.fillMaxWidth().height(52.dp),
                            colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                        ){
                            Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Abrir OSM",fontWeight=FontWeight.Bold)
                        }
                    }
                }
            }

            if(processing.running || processing.total>0) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                                Column {
                                    Text(if(processing.running)"Atualizando seus slots" else "Último processamento",fontWeight=FontWeight.Bold)
                                    Text(processing.label,fontSize=12.sp,color=Color.Gray)
                                    if(processing.running) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Processamento ativo — a tela pode atualizar sem reiniciar a análise.",fontSize=10.sp,color=Color(0xFF6B7B72))
                                    }
                                }
                                Text("${processing.current}/${processing.total}",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(10.dp))
                            LinearProgressIndicator(
                                progress={if(processing.total>0)processing.current.toFloat()/processing.total else 0f},
                                modifier=Modifier.fillMaxWidth().height(9.dp),
                                color=Color(0xFF7AA526),trackColor=Color(0xFFDDE5D1)
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Aplicados ${processing.success} • Falhas ${processing.failed}",fontSize=12.sp)
                            if(processing.lastError.isNotBlank()) {
                                Spacer(Modifier.height(5.dp))
                                Text(processing.lastError,fontSize=11.sp,color=Color(0xFF9C4D36))
                            }
                            if(!processing.running) {
                                Spacer(Modifier.height(12.dp))
                                OutlinedButton(onClick=onReprocess,modifier=Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Reprocessar última sessão")
                                }
                            }
                        }
                    }
                }
            }

            item { Text("Seus slots",fontWeight=FontWeight.Bold,fontSize=20.sp) }

            items(slots) { slot ->
                val pct=completion(slot)
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(15.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Surface(shape=RoundedCornerShape(10.dp),color=Color(0xFF122630)) {
                                Text("S${slot.id}",Modifier.padding(horizontal=10.dp,vertical=8.dp),color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(if(slot.team!="NI")slot.team else "Slot ${slot.id}",fontWeight=FontWeight.Bold)
                                Text(if(slot.competition!="NI")slot.competition else "Liga aguardando leitura",fontSize=11.sp,color=Color.Gray)
                            }
                            Text("$pct%",fontWeight=FontWeight.Bold)
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress={pct/100f},modifier=Modifier.fillMaxWidth().height(6.dp),
                            color=Color(0xFF7AA526),trackColor=Color(0xFFE8ECDF)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Próximo: ${slot.nextRival} • ${slot.matchDate} • ${slot.venue}",fontSize=12.sp,color=Color(0xFF56656B))
                    }
                }
            }

            item { SessionSummaryCard(latest) }
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
                Text("Veja o que foi capturado, reconhecido e aplicado em cada slot.", color=Color.Gray)
            }
            sessions.forEach { session ->
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White), shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            val ended = session.endedAt?.let { formatTime(it) } ?: "em andamento"
                            Text("${formatTime(session.startedAt)} → $ended", fontWeight=FontWeight.Bold)
                            Text(if(session.state=="recording") "CAPTURANDO AGORA" else "Sessão concluída",
                                fontSize=11.sp, color=if(session.state=="recording") Color(0xFFB36B00) else Color(0xFF6E9920), fontWeight=FontWeight.Bold)
                            val ocrOk=session.frames.count{it.ocrText.isNotBlank()}
                            val understood=session.frames.count{it.screenType!="other"}
                            Text("${session.frames.size} telas • OCR $ocrOk/${session.frames.size} • reconhecidas $understood",
                                fontSize=13.sp,color=Color.Gray)
                            Spacer(Modifier.height(12.dp))

                            (1..4).forEach { slotId ->
                                val rows=session.frames.filter{it.slotId==slotId}
                                if(rows.isNotEmpty()){
                                    Text("S$slotId",fontWeight=FontWeight.Bold,color=Color(0xFF6E9920))
                                    Spacer(Modifier.height(4.dp))
                                    rows.forEach { frame ->
                                        Row(
                                            Modifier.fillMaxWidth().padding(vertical=6.dp),
                                            verticalAlignment=Alignment.CenterVertically
                                        ) {
                                            Surface(shape=RoundedCornerShape(8.dp), color=typeColor(frame.screenType)) {
                                                Text(labelType(frame.screenType),Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=10.sp)
                                            }
                                            Spacer(Modifier.width(9.dp))
                                            Column(Modifier.weight(1f)) {
                                                Text(frame.screenTitle.ifBlank{"Tela do OSM"},maxLines=1,fontSize=13.sp)
                                                val ocr=if(frame.ocrText.isNotBlank())"OCR ✓" else "OCR —"
                                                Text("$ocr • ${frame.analysisState} • ${frame.extractedFields} dado(s)",
                                                    fontSize=10.sp,color=Color.Gray)
                                            }
                                            Text(SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt)),
                                                fontSize=10.sp,color=Color.Gray)
                                        }
                                    }
                                    Spacer(Modifier.height(10.dp))
                                }
                            }

                            val unassigned=session.frames.filter{it.slotId==0}
                            if(unassigned.isNotEmpty()){
                                Text("Não atribuídas a slot: ${unassigned.size}",fontSize=12.sp,color=Color(0xFF9C4D36))
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
        var section by remember(slot.id) { mutableIntStateOf(0) }
        val tabs = listOf("Resumo","Pré-jogo","Elenco","Calendário","Tática","Diretor","Aprendizado")

        Column(Modifier.fillMaxSize()) {
            Surface(color=Color.White,shadowElevation=2.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                        Column(Modifier.weight(1f)) {
                            Text("S${slot.id} · ${if(slot.team!="NI")slot.team else "Time NI"}",fontSize=22.sp,fontWeight=FontWeight.Bold)
                            Text("${completion(slot)}% preenchido · ${if(slot.competition!="NI")slot.competition else "liga NI"}",fontSize=12.sp,color=Color.Gray)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(tabs.size) { i ->
                            FilterChip(selected=section==i,onClick={section=i},label={Text(tabs[i])})
                        }
                    }
                }
            }

            when(section) {
                0 -> SlotOverview(slot)
                1 -> SlotPreGame(slot)
                2 -> SlotSquad(slot)
                3 -> SlotCalendar(slot)
                4 -> SlotTactics(slot)
                5 -> SlotDirector(slot)
                else -> SlotLearning(slot)
            }
        }
    }

    @Composable
    private fun SlotOverview(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFF122630)),shape=RoundedCornerShape(22.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("PRÓXIMO JOGO",fontSize=11.sp,color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(if(slot.nextRival!="NI")slot.nextRival else "Rival ainda não identificado",
                            color=Color.White,fontSize=24.sp,fontWeight=FontWeight.Bold)
                        Text("${slot.matchDate} · ${slot.matchTime} · ${slot.venue}",color=Color(0xFFB7C8CF),fontSize=13.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            QuickStat("Minha força",slot.myStrength,Modifier.weight(1f))
                            QuickStat("Rival",slot.rivalStrength,Modifier.weight(1f))
                            QuickStat("Árbitro",slot.referee,Modifier.weight(1f))
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Metric("${completion(slot)}%","Dados completos",Modifier.weight(1f))
                    Metric(slot.squadCount.toString(),"Jogadores",Modifier.weight(1f))
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Clube e competição",fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Spacer(Modifier.height(10.dp))
                        DetailLine("Time",slot.team)
                        DetailLine("Competição",slot.competition)
                        DetailLine("Tipo",slot.competitionType)
                        DetailLine("Estádio",slot.stadium)
                        DetailLine("Bônus",slot.bonus)
                    }
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Ações rápidas",fontWeight=FontWeight.Bold,fontSize=17.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            Button(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("Gerar tática")}
                            OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("433 forte")}
                        }
                    }
                }
            }
            if(missingFields(slot).isNotBlank()) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)),shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("O que ainda falta",fontWeight=FontWeight.Bold)
                            Spacer(Modifier.height(6.dp))
                            Text(missingFields(slot),fontSize=13.sp)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun QuickStat(label:String,value:String,modifier:Modifier=Modifier) {
        Surface(modifier,shape=RoundedCornerShape(13.dp),color=Color(0xFF1E3440)) {
            Column(Modifier.padding(10.dp)) {
                Text(label,fontSize=10.sp,color=Color(0xFFAFC1C9))
                Text(value,fontWeight=FontWeight.Bold,color=Color.White,fontSize=14.sp,maxLines=1)
            }
        }
    }

    @Composable
    private fun DetailLine(label:String,value:String) {
        Row(Modifier.fillMaxWidth().padding(vertical=7.dp)) {
            Text(label,Modifier.weight(1f),fontSize=13.sp,color=Color.Gray)
            Text(value,fontSize=13.sp,fontWeight=FontWeight.SemiBold)
        }
        HorizontalDivider(color=Color(0xFFEEF0EA))
    }

    @Composable
    private fun SlotPreGame(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Dados da partida", listOf(
                "Rival" to slot.nextRival,"Data" to slot.matchDate,"Horário" to slot.matchTime,
                "Local" to slot.venue,"Árbitro" to slot.referee,
                "Minha força" to slot.myStrength,"Força rival" to slot.rivalStrength,
                "Meu elenco" to slot.myValue,"Elenco rival" to slot.rivalValue
            ))}
            item { DetailSection("Forças por setor", listOf(
                "Meu GOL" to slot.myGoalkeeper,"Minha DEF" to slot.myDefense,
                "Meu MEI" to slot.myMidfield,"Meu ATA" to slot.myAttack,
                "Rival GOL" to slot.rivalGoalkeeper,"Rival DEF" to slot.rivalDefense,
                "Rival MEI" to slot.rivalMidfield,"Rival ATA" to slot.rivalAttack
            ))}
            item { DetailSection("Scout rival", listOf(
                "Formação" to slot.rivalFormation,"Plano" to slot.rivalPlan,
                "Marcação" to slot.marking,"Impedimento" to slot.offside,
                "Treino secreto" to slot.secretTraining,"Campo de treinamento" to slot.trainingCamp
            ))}
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("Gerar tática IA")}
                    OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("4-3-3 forte")}
                }
            }
        }
    }

    @Composable
    private fun SlotSquad(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Elenco · ${slot.players.size.coerceAtLeast(slot.squadCount)} jogadores",fontWeight=FontWeight.Bold,fontSize=18.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            MiniMetric("ATA",slot.attackers.toString(),Modifier.weight(1f))
                            MiniMetric("MEI",slot.midfielders.toString(),Modifier.weight(1f))
                            MiniMetric("DEF",slot.defenders.toString(),Modifier.weight(1f))
                            MiniMetric("GOL",slot.goalkeepers.toString(),Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Treinando: ${slot.trainingCount} • À venda: ${slot.sellingCount}")
                    }
                }
            }

            if(slot.players.isEmpty()) {
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)),shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Nenhum jogador individual confirmado",fontWeight=FontWeight.Bold)
                            Text("Abra a tela completa do elenco no OSM e reprocese a sessão.",fontSize=12.sp,color=Color.Gray)
                        }
                    }
                }
            } else {
                items(slot.players) { p ->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFFEFF5E5)) {
                                    Text(p.position,Modifier.padding(horizontal=8.dp,vertical=5.dp),fontSize=11.sp,fontWeight=FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Text(p.name,Modifier.weight(1f),fontWeight=FontWeight.Bold)
                                Text(p.strength,fontWeight=FontWeight.Bold,color=Color(0xFF486A19))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("${p.age} anos • ${p.value} • treino: ${p.training} • venda: ${p.selling}",
                                fontSize=11.sp,color=Color.Gray)
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotCalendar(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item { DetailSection("Calendário", listOf(
                "Jogos lidos" to slot.calendar.size.coerceAtLeast(slot.calendarCount).toString(),
                "Próximo rival" to slot.nextRival,
                "Data" to slot.matchDate,
                "Horário" to slot.matchTime,
                "Local" to slot.venue
            ))}

            if(slot.calendar.isEmpty()){
                item {
                    Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)),shape=RoundedCornerShape(18.dp)) {
                        Column(Modifier.padding(18.dp)) {
                            Text("Calendário ainda sem partidas confirmadas",fontWeight=FontWeight.Bold)
                            Text("Navegue pelo calendário completo no OSM e use Reprocessar última sessão.",fontSize=12.sp,color=Color.Gray)
                        }
                    }
                }
            } else {
                items(slot.calendar) { game ->
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically) {
                                Surface(shape=RoundedCornerShape(8.dp),color=Color(0xFFEFF5E5)) {
                                    Text(game.round,Modifier.padding(horizontal=8.dp,vertical=5.dp),fontSize=11.sp,fontWeight=FontWeight.Bold)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(game.opponent,fontWeight=FontWeight.Bold)
                                    Text("${game.date} • ${game.time} • ${game.venue}${if(game.cup)" • Copa" else ""}",
                                        fontSize=11.sp,color=Color.Gray)
                                }
                                Text(game.score,fontWeight=FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotTactics(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Tática completa", listOf(
                "Formação" to "NI","Estilo de jogo" to "NI","Pressão" to "NI",
                "Mentalidade / Estilo" to "NI","Ritmo / Temporização" to "NI",
                "Marcação" to "NI","Impedimento" to "NI","Desarme" to "NI",
                "Ataque" to "NI","Meio" to "NI","Defesa" to "NI"
            ))}
            item { Button(onClick={},enabled=false,modifier=Modifier.fillMaxWidth()){Text("Gerar melhor tática com IA")} }
        }
    }

    @Composable
    private fun SlotDirector(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Mercado e evolução", listOf(
                "Mercado visitado" to if(slot.marketSeen)"Sim" else "Não",
                "Treino visitado" to if(slot.trainingSeen)"Sim" else "Não",
                "Jogadores treinando" to slot.trainingCount.toString(),
                "Jogadores à venda" to slot.sellingCount.toString(),
                "Elenco atual" to slot.squadCount.toString()
            ))}
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Plano persistente",fontWeight=FontWeight.Bold)
                        Text("Compras, vendas, treinamento, eventos ativos e projeção de força ficarão salvos aqui por slot.",color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotLearning(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Aprendizado compartilhado S1–S4",fontWeight=FontWeight.Bold)
                        Text("Resultados, desempenho da tática, força relativa, humano/CPU, cartões e contexto serão usados para aprender sem apagar o histórico do slot.",color=Color.Gray)
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
    private fun SettingsScreen(
        serviceReady:Boolean,
        latest:CaptureSession?,
        processing:NativeSessionProcessor.Progress,
        onReprocess:()->Unit
    ) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Text("Configurações",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Leitura, IA, dados e diagnóstico do Coach.",color=Color.Gray)
            }
            item {
                SettingsCard("Leitura automática",Icons.Default.Visibility) {
                    SettingStatus("Permissão no Android",if(isAccessibilityServiceEnabledInSettings())"Ativa" else "Desativada",isAccessibilityServiceEnabledInSettings())
                    SettingStatus("Serviço conectado",if(serviceReady)"Conectado" else "Desconectado",serviceReady)
                    SettingStatus("Captura do OSM",when {
                        CollectorState.isRecording() -> "Gravando"
                        serviceReady -> "Pronta"
                        else -> "Aguardando serviço"
                    },serviceReady)
                    if(!serviceReady) {
                        Spacer(Modifier.height(10.dp))
                        Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))},modifier=Modifier.fillMaxWidth()) {
                            Text(if(isAccessibilityServiceEnabledInSettings()) "Reconectar serviço" else "Abrir acessibilidade")
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            if(isAccessibilityServiceEnabledInSettings())
                                "A autorização está marcada no Android, mas o serviço não está executando. Desative e ative novamente uma vez nesta tela."
                            else
                                "Ative OSM AI Coach — leitura automática.",
                            fontSize=11.sp,color=Color(0xFF9C4D36)
                        )
                    }
                }
            }
            item {
                SettingsCard("Conta e slots",Icons.Default.AccountCircle) {
                    DetailLine("Usuário OSM","leandrozzy")
                    DetailLine("Slots","4")
                    DetailLine("Armazenamento","Local no aparelho")
                }
            }
            item {
                SettingsCard("IA e processamento",Icons.Default.AutoAwesome) {
                    DetailLine("Processamento","OCR local + IA Cloud")
                                DetailLine("APIs","Configuradas no backend/Vercel")
                                val ocrPrefs=this@MainActivity.getSharedPreferences("native_processor_v13",MODE_PRIVATE)
                                DetailLine("Telas com texto OCR","${ocrPrefs.getInt("local_ocr_readable",0)}/${ocrPrefs.getInt("local_ocr_total",0)}")
                                DetailLine("Rastreamento de slots",ocrPrefs.getString("slot_tracker_summary","Ainda não processado") ?: "Ainda não processado")
                    DetailLine("Versão nativa","V19 · ${BuildConfig.VERSION_NAME}")
                    val rt=getSharedPreferences("collector_runtime",Context.MODE_PRIVATE)
                    DetailLine("Serviço criado",formatDiagnosticTime(rt.getLong("service_created_at",0L)))
                    DetailLine("Serviço conectado em",formatDiagnosticTime(rt.getLong("service_connected_at",0L)))
                    DetailLine("Janela detectada",rt.getString("last_foreground_package","NI") ?: "NI")
                    DetailLine("Último heartbeat",formatDiagnosticTime(rt.getLong("last_heartbeat_at",0L)))
                    DetailLine("Último evento",formatDiagnosticTime(rt.getLong("last_accessibility_event_at",0L)))
                    DetailLine("Último frame salvo",formatDiagnosticTime(rt.getLong("last_saved_frame_at",0L)))
                    DetailLine("Sessão ativa",repo.current()?.let { "${it.frames.size} telas · ${formatTime(it.startedAt)}" } ?: "Não")
                    DetailLine("Última sessão",latest?.let{"${formatTime(it.startedAt)} · ${it.frames.size} telas"}?:"Nenhuma")
                    DetailLine("Resultado","${processing.success} aplicados · ${processing.failed} falhas")
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick=onReprocess,enabled=latest!=null&&!processing.running,modifier=Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Refresh,null);Spacer(Modifier.width(7.dp));Text("Reprocessar última sessão")
                    }
                }
            }
            item {
                SettingsCard("Diagnóstico",Icons.Default.BugReport) {
                    DetailLine("Sessão preservada",if(latest!=null)"Sim" else "Não")
                    DetailLine("Estado",latest?.state?:"NI")
                    DetailLine(
                        "Último erro",
                        processing.lastError.ifBlank {
                            getSharedPreferences("collector_runtime",MODE_PRIVATE)
                                .getString("session_metadata_error","Nenhum") ?: "Nenhum"
                        }
                    )
                }
            }
            item {
                SettingsCard("Notificações e automação",Icons.Default.Notifications) {
                    Text("Alertas de jogo, pendências, resultado e mercado ficarão concentrados aqui.",fontSize=13.sp)
                }
            }
        }
    }

    @Composable
    private fun SettingsCard(
        title:String,
        icon:androidx.compose.ui.graphics.vector.ImageVector,
        content:@Composable ColumnScope.()->Unit
    ) {
        Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment=Alignment.CenterVertically) {
                    Icon(icon,null,tint=Color(0xFF6E942D))
                    Spacer(Modifier.width(9.dp))
                    Text(title,fontWeight=FontWeight.Bold,fontSize=17.sp)
                }
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }

    @Composable
    private fun SettingStatus(label:String,value:String,good:Boolean) {
        Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f),fontSize=13.sp,color=Color.Gray)
            Surface(shape=RoundedCornerShape(999.dp),color=if(good)Color(0xFFEAF4DD) else Color(0xFFFFE9D8)) {
                Text(value,Modifier.padding(horizontal=9.dp,vertical=5.dp),fontSize=11.sp,fontWeight=FontWeight.Bold)
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
        // Registra a intenção de iniciar uma sessão ANTES de abrir o OSM.
        // Activity e AccessibilityService usam o mesmo marcador; assim não há
        // perda da sessão mesmo quando o Android recria uma das duas.
        val now=System.currentTimeMillis()
        val session=repo.beginNewSession()
        getSharedPreferences("collector_runtime",Context.MODE_PRIVATE).edit()
            .putBoolean("osm_seen_in_session", false)
            .putBoolean("start_new_session_pending", true)
            .putLong("requested_session_at", now)
            .putLong("coach_launch_at", now)
            .putString("requested_session_id", session.id)
            .apply()
        packageManager.getLaunchIntentForPackage(OSM_PACKAGE)?.let {
            it.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            startActivity(it)
        }
    }

    private fun isAccessibilityServiceEnabledInSettings():Boolean {
        val expected = ComponentName(this, OsmCaptureAccessibilityService::class.java)
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val byManager = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo
                    si != null && si.packageName == expected.packageName && si.name == expected.className
                }
        }.getOrDefault(false)
        if (byManager) return true

        val setting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return setting.split(':').any {
            runCatching { ComponentName.unflattenFromString(it) == expected }.getOrDefault(false)
        }
    }

    private fun isAccessibilityServiceActuallyConnected():Boolean {
        if (CollectorState.isServiceReady()) return true
        val rt = getSharedPreferences("collector_runtime",Context.MODE_PRIVATE)
        val heartbeat = rt.getLong("last_heartbeat_at",0L)
        val connected = rt.getBoolean("accessibility_connected",false)
        return connected && heartbeat > 0L && (System.currentTimeMillis() - heartbeat) < 4500L
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

    private fun formatDiagnosticTime(ms:Long):String = if(ms<=0L) "Nunca" else SimpleDateFormat("dd/MM HH:mm:ss",Locale.getDefault()).format(Date(ms))

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
