from pathlib import Path

R=Path(".")

# Root Gradle: enable Compose compiler plugin
(R/"android-collector/build.gradle.kts").write_text(r"""plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
""", encoding="utf-8")

# App Gradle
(R/"android-collector/app/build.gradle.kts").write_text(r"""plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.osmaicoach.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.osmaicoach.collector"
        minSdk = 30
        targetSdk = 35
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = 2000 + runNumber
        versionName = "2.0.$runNumber"
        buildConfigField("String", "BACKEND_URL", "\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val password = System.getenv("ANDROID_SIGNING_PASSWORD")
            val path = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!password.isNullOrBlank() && !path.isNullOrBlank()) {
                storeFile = file(path)
                storePassword = password
                keyAlias = "osmcoach"
                keyPassword = password
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.01.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
""", encoding="utf-8")

pkg=R/"android-collector/app/src/main/java/com/osmaicoach/collector"
pkg.mkdir(parents=True, exist_ok=True)

# Models expanded to keep every screen
(pkg/"SessionModels.kt").write_text(r"""package com.osmaicoach.collector

data class CaptureFrame(
    val index: Int,
    val fileName: String,
    val capturedAt: Long,
    val sourcePackage: String = OSM_PACKAGE,
    val width: Int,
    val height: Int,
    val fingerprint: Long,
    val textHint: String = "",
    val screenType: String = "other",
    val screenTitle: String = ""
)

data class CaptureSession(
    val id: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val frames: MutableList<CaptureFrame> = mutableListOf(),
    var state: String = "recording"
)

const val OSM_PACKAGE = "com.gamebasics.osm"
""", encoding="utf-8")

# Native classifier - deliberately keeps unknown screens
(pkg/"ScreenClassifier.kt").write_text(r"""package com.osmaicoach.collector

object ScreenClassifier {
    data class Result(val type: String, val title: String)

    fun classify(text: String): Result {
        val t = normalize(text)
        if (t.isBlank()) return Result("other", "Tela do OSM")

        val rules = listOf(
            "calendar" to listOf("calendario","calendar","rodada","jornada","proximo jogo","próximo jogo","fixtures"),
            "squad" to listOf("elenco","plantel","squad","jogadores","players","atacante","meio campo","defesa","goleiro"),
            "match" to listOf("analise","análise","analysis","arbitro","árbitro","formacao","formação","marcacao","marcação","impedimento","offside"),
            "market" to listOf("transferencia","transferência","transfer list","mercado","comprar jogador","vender jogador"),
            "training" to listOf("treino","training","treinamento","campo de treinamento","training camp"),
            "club" to listOf("clube","club","estadio","estádio","stadium","financas","finanças","finance"),
            "ranking" to listOf("classificacao","classificação","standings","tabela","ranking"),
            "result" to listOf("resultado","result","estatisticas","estatísticas","statistics","posse de bola"),
            "tactics" to listOf("tatica","tática","tactics","pressao","pressão","ritmo","estilo de jogo")
        )

        val best = rules
            .map { (type, words) -> type to words.count { t.contains(normalize(it)) } }
            .maxByOrNull { it.second }

        val type = if (best != null && best.second > 0) best.first else "other"
        val title = when(type) {
            "calendar" -> "Calendário"
            "squad" -> "Elenco"
            "match" -> "Pré-jogo / Análise"
            "market" -> "Mercado"
            "training" -> "Treinamento"
            "club" -> "Clube"
            "ranking" -> "Classificação"
            "result" -> "Resultado / Estatísticas"
            "tactics" -> "Táticas"
            else -> firstMeaningful(text)
        }
        return Result(type, title)
    }

    private fun normalize(v: String): String =
        java.text.Normalizer.normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun firstMeaningful(text: String): String =
        text.split("|").map { it.trim() }.firstOrNull { it.length in 3..50 } ?: "Tela do OSM"
}
""", encoding="utf-8")

# Repository stores every captured screen and adds listing helpers
(pkg/"SessionRepository.kt").write_text(r"""package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SessionRepository(private val context: Context) {
    private val root = File(context.filesDir, "osm_sessions").apply { mkdirs() }
    private var active: CaptureSession? = null

    @Synchronized
    fun startIfNeeded(): CaptureSession {
        active?.let { return it }
        val now = System.currentTimeMillis()
        val id = "osm-${now}-${UUID.randomUUID().toString().take(8)}"
        return CaptureSession(id=id, startedAt=now).also {
            active=it
            sessionDir(it).mkdirs()
            persist(it)
        }
    }

    @Synchronized fun current(): CaptureSession? = active

    @Synchronized
    fun saveFrame(bitmap: Bitmap, fingerprint: Long, textHint: String = ""): CaptureFrame {
        val session=startIfNeeded()
        val index=session.frames.size
        val name="frame-${index.toString().padStart(4,'0')}.jpg"
        val file=File(sessionDir(session),name)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }

        val classified=ScreenClassifier.classify(textHint)
        val frame=CaptureFrame(
            index=index,
            fileName=name,
            capturedAt=System.currentTimeMillis(),
            width=bitmap.width,
            height=bitmap.height,
            fingerprint=fingerprint,
            textHint=textHint.take(7000),
            screenType=classified.type,
            screenTitle=classified.title.take(80)
        )
        session.frames += frame
        persist(session)
        return frame
    }

    @Synchronized
    fun finish(): CaptureSession? {
        val session=active ?: return null
        session.endedAt=System.currentTimeMillis()
        session.state="ready"
        persist(session)
        active=null
        context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .edit().putString("latest_session_id",session.id).apply()
        return session
    }

    fun latestSessionJson(): String {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .getString("latest_session_id",null) ?: return "null"
        return sessionJson(id)
    }

    fun latestSession(): CaptureSession? =
        parseSession(latestSessionJson())

    fun listSessions(limit: Int = 20): List<CaptureSession> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it,"session.json").exists() }
            ?.sortedByDescending { File(it,"session.json").lastModified() }
            ?.take(limit)
            ?.mapNotNull { parseSession(File(it,"session.json").readText()) }
            ?: emptyList()

    fun latestFrameBase64(index:Int):String?{
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .getString("latest_session_id",null) ?: return null
        val json=JSONObject(sessionJson(id))
        val frames=json.optJSONArray("frames") ?: return null
        if(index !in 0 until frames.length()) return null
        val name=frames.getJSONObject(index).getString("fileName")
        val bytes=File(File(root,id),name).readBytes()
        return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    }

    private fun sessionDir(session:CaptureSession)=File(root,session.id)
    private fun sessionJson(id:String):String{
        val f=File(File(root,id),"session.json")
        return if(f.exists()) f.readText() else "null"
    }

    private fun persist(session:CaptureSession){
        val frames=JSONArray()
        session.frames.forEach { f ->
            frames.put(JSONObject().apply{
                put("index",f.index);put("fileName",f.fileName);put("capturedAt",f.capturedAt)
                put("sourcePackage",f.sourcePackage);put("width",f.width);put("height",f.height)
                put("fingerprint",f.fingerprint.toString());put("textHint",f.textHint)
                put("screenType",f.screenType);put("screenTitle",f.screenTitle)
            })
        }
        val json=JSONObject().apply{
            put("version",3);put("kind","osm-native-session");put("id",session.id)
            put("startedAt",session.startedAt);put("endedAt",session.endedAt?:JSONObject.NULL)
            put("state",session.state);put("frames",frames)
        }
        File(sessionDir(session),"session.json").writeText(json.toString(2))
    }

    private fun parseSession(raw:String):CaptureSession?=runCatching{
        val o=JSONObject(raw)
        if(o.optString("id").isBlank()) return@runCatching null
        val s=CaptureSession(
            id=o.getString("id"),
            startedAt=o.optLong("startedAt"),
            endedAt=o.optLong("endedAt").takeIf{it>0},
            state=o.optString("state","ready")
        )
        val arr=o.optJSONArray("frames")?:JSONArray()
        for(i in 0 until arr.length()){
            val f=arr.getJSONObject(i)
            s.frames += CaptureFrame(
                index=f.optInt("index",i),
                fileName=f.optString("fileName"),
                capturedAt=f.optLong("capturedAt"),
                sourcePackage=f.optString("sourcePackage",OSM_PACKAGE),
                width=f.optInt("width"),
                height=f.optInt("height"),
                fingerprint=f.optString("fingerprint","0").toLongOrNull()?:0L,
                textHint=f.optString("textHint"),
                screenType=f.optString("screenType","other"),
                screenTitle=f.optString("screenTitle","Tela do OSM")
            )
        }
        s
    }.getOrNull()
}
""", encoding="utf-8")

# Accessibility is persistent: check system setting, capture all distinct screens, no "activate every launch"
(pkg/"OsmCaptureAccessibilityService.kt").write_text(r"""package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class OsmCaptureAccessibilityService : AccessibilityService() {
    private lateinit var repository: SessionRepository
    private val handler=Handler(Looper.getMainLooper())
    private val executor:Executor by lazy { mainExecutor }
    private var lastCaptureAt=0L
    private var lastFingerprint:Long?=null
    private var osmWasForeground=false
    private var pendingCapture=false
    private var finishRunnable:Runnable?=null

    override fun onServiceConnected(){
        repository=SessionRepository(applicationContext)
        CollectorState.lastError=null
        CollectorState.setServiceReady(true)
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        val pkg=event?.packageName?.toString()?:return
        val isOsm=pkg==OSM_PACKAGE
        CollectorState.currentForegroundPackage=pkg

        if(isOsm){
            finishRunnable?.let(handler::removeCallbacks);finishRunnable=null
            osmWasForeground=true
            repository.startIfNeeded()
            CollectorState.setRecording(true)
            scheduleCapture()
        }else if(osmWasForeground){
            scheduleFinish()
        }
    }

    private fun scheduleFinish(){
        finishRunnable?.let(handler::removeCallbacks)
        val task=Runnable{
            if(CollectorState.currentForegroundPackage!=OSM_PACKAGE&&osmWasForeground){
                repository.finish()
                osmWasForeground=false
                pendingCapture=false
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        }
        finishRunnable=task
        handler.postDelayed(task,1200L)
    }

    private fun scheduleCapture(){
        if(pendingCapture)return
        val delay=maxOf(1450L-(System.currentTimeMillis()-lastCaptureAt),180L)
        pendingCapture=true
        handler.postDelayed({pendingCapture=false;captureNow()},delay)
    }

    private fun visibleText():String{
        val root=rootInActiveWindow?:return ""
        val out=LinkedHashSet<String>()
        fun walk(n:AccessibilityNodeInfo?,depth:Int){
            if(n==null||depth>16||out.size>=220)return
            n.text?.toString()?.trim()?.takeIf{it.isNotBlank()}?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf{it.isNotBlank()}?.let(out::add)
            for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)
        }
        runCatching{walk(root,0)}
        return out.joinToString(" | ").take(7000)
    }

    private fun captureNow(){
        if(CollectorState.currentForegroundPackage!=OSM_PACKAGE)return
        val hint=visibleText()
        takeScreenshot(Display.DEFAULT_DISPLAY,executor,object:TakeScreenshotCallback{
            override fun onSuccess(result:ScreenshotResult){
                val buffer=result.hardwareBuffer
                val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                buffer.close()
                if(hardware==null){
                    CollectorState.lastError="Falha de captura: bitmap"
                    return
                }
                val bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false)
                hardware.recycle()
                val fp=BitmapFingerprint.aHash(bitmap)
                val old=lastFingerprint
                if(old==null||BitmapFingerprint.distance(old,fp)>2){
                    repository.saveFrame(bitmap,fp,hint)
                    lastFingerprint=fp
                    CollectorState.signalFrameCaptured()
                }
                lastCaptureAt=System.currentTimeMillis()
                CollectorState.lastError=null
                bitmap.recycle()
            }
            override fun onFailure(errorCode:Int){
                if(errorCode==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){
                    lastCaptureAt=System.currentTimeMillis()
                    scheduleCapture()
                }else CollectorState.lastError="Falha de captura: $errorCode"
            }
        })
    }

    override fun onInterrupt(){finish()}
    override fun onDestroy(){finish();super.onDestroy()}

    private fun finish(){
        finishRunnable?.let(handler::removeCallbacks)
        if(::repository.isInitialized)repository.finish()
        osmWasForeground=false
        pendingCapture=false
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
    }
}
""", encoding="utf-8")

# Professional native Compose UI.
(pkg/"MainActivity.kt").write_text(r"""package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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

class MainActivity:ComponentActivity(){
    private lateinit var repo:SessionRepository

    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        repo=SessionRepository(this)
        setContent{CoachTheme{NativeCoachApp()}}
    }

    @Composable
    private fun NativeCoachApp(){
        var tab by remember{mutableStateOf(0)}
        var refresh by remember{mutableIntStateOf(0)}
        DisposableEffect(Unit){
            val l:()->Unit={runOnUiThread{refresh++}}
            CollectorState.addListener(l)
            onDispose{CollectorState.removeListener(l)}
        }
        LaunchedEffect(Unit){while(true){kotlinx.coroutines.delay(1500);refresh++}}

        val serviceReady=isAccessibilityServiceEnabled()
        val recording=CollectorState.isRecording()
        val latest=repo.latestSession()
        val sessions=repo.listSessions()

        Scaffold(
            containerColor=Color(0xFFF6F7F2),
            topBar={
                Surface(color=Color(0xFF101E27),shadowElevation=4.dp){
                    Column(Modifier.fillMaxWidth().padding(18.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Surface(shape=RoundedCornerShape(12.dp),color=Color(0xFFB8E34D)){
                                Icon(Icons.Default.SportsSoccer,null,Modifier.padding(10.dp),tint=Color(0xFF101E27))
                            }
                            Spacer(Modifier.width(12.dp))
                            Column{
                                Text("OSM AI Coach Pro",color=Color.White,fontWeight=FontWeight.Bold,fontSize=20.sp)
                                Text("Coach nativo • leitura contínua",color=Color(0xFFAAC0CC),fontSize=12.sp)
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Icon(
                                if(serviceReady)Icons.Default.CheckCircle else Icons.Default.Warning,
                                null,tint=if(serviceReady)Color(0xFFB8E34D) else Color(0xFFFFC857)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when{
                                    !serviceReady->"Leitura automática desativada"
                                    recording->"Lendo o OSM agora"
                                    else->"Leitura automática pronta"
                                },
                                color=Color.White,fontWeight=FontWeight.SemiBold
                            )
                        }
                        if(!serviceReady){
                            Spacer(Modifier.height(10.dp))
                            Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){
                                Text("Ativar uma vez")
                            }
                        }
                    }
                }
            },
            bottomBar={
                NavigationBar(containerColor=Color(0xFF101E27)){
                    val labels=listOf("Hoje","Sessões","Slots","Diretor","Ajustes")
                    val icons=listOf(Icons.Default.Home,Icons.Default.Route,Icons.Default.Dashboard,Icons.Default.TrendingUp,Icons.Default.Settings)
                    labels.forEachIndexed{i,label->
                        NavigationBarItem(
                            selected=tab==i,onClick={tab=i},
                            icon={Icon(icons[i],null)},
                            label={Text(label)},
                            colors=NavigationBarItemDefaults.colors(
                                selectedIconColor=Color(0xFFB8E34D),
                                selectedTextColor=Color(0xFFB8E34D),
                                unselectedIconColor=Color(0xFF9FB0B8),
                                unselectedTextColor=Color(0xFF9FB0B8),
                                indicatorColor=Color(0xFF21323C)
                            )
                        )
                    }
                }
            }
        ){pad->
            Box(Modifier.padding(pad).fillMaxSize()){
                when(tab){
                    0->TodayScreen(serviceReady,recording,latest){openOsm()}
                    1->SessionsScreen(sessions)
                    2->SlotsScreen(latest)
                    3->DirectorScreen(latest)
                    else->SettingsScreen(serviceReady)
                }
            }
        }
    }

    @Composable
    private fun TodayScreen(serviceReady:Boolean,recording:Boolean,latest:CaptureSession?,openOsm:()->Unit){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){
            item{
                Text("Seu dia, organizado.",fontSize=30.sp,fontWeight=FontWeight.Bold,color=Color(0xFF17242B))
                Text("O app acompanha tudo o que você visita no OSM e preserva cada tela útil.",color=Color(0xFF68777E))
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(20.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Leitura automática",fontWeight=FontWeight.Bold,fontSize=18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            if(recording)"Gravando sua navegação no OSM"
                            else if(serviceReady)"Pronta. Você só precisa abrir o OSM."
                            else "Ative a acessibilidade uma única vez."
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick=openOsm,enabled=serviceReady&&!recording,modifier=Modifier.fillMaxWidth()){
                            Icon(Icons.Default.PlayArrow,null);Spacer(Modifier.width(8.dp));Text("Abrir OSM")
                        }
                    }
                }
            }
            item{SessionSummaryCard(latest)}
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Metric("Telas",latest?.frames?.size?.toString()?:"0",Modifier.weight(1f))
                    Metric("Tipos",latest?.frames?.map{it.screenType}?.distinct()?.size?.toString()?:"0",Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun SessionSummaryCard(session:CaptureSession?){
        Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(20.dp)){
            Column(Modifier.padding(18.dp)){
                Text("Última sessão",fontWeight=FontWeight.Bold,fontSize=18.sp)
                if(session==null){
                    Text("Nenhuma sessão capturada ainda.")
                }else{
                    Text("${session.frames.size} telas preservadas")
                    Spacer(Modifier.height(8.dp))
                    val groups=session.frames.groupingBy{it.screenType}.eachCount()
                    Text(groups.entries.sortedByDescending{it.value}.joinToString(" • "){"${labelType(it.key)} ${it.value}"},
                        fontSize=13.sp,color=Color(0xFF55645B))
                }
            }
        }
    }

    @Composable
    private fun SessionsScreen(sessions:List<CaptureSession>){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            item{
                Text("Jornada da sessão",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Tudo o que você navegou no OSM fica registrado aqui.",color=Color.Gray)
            }
            sessions.forEach{session->
                item{
                    Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                        Column(Modifier.padding(16.dp)){
                            Text(formatTime(session.startedAt),fontWeight=FontWeight.Bold)
                            Text("${session.frames.size} telas • ${session.state}",fontSize=13.sp,color=Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            session.frames.takeLast(14).forEach{frame->
                                Row(Modifier.fillMaxWidth().padding(vertical=5.dp),verticalAlignment=Alignment.CenterVertically){
                                    Surface(shape=RoundedCornerShape(8.dp),color=typeColor(frame.screenType)){
                                        Text(labelType(frame.screenType),Modifier.padding(horizontal=8.dp,vertical=4.dp),fontSize=11.sp)
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Text(frame.screenTitle,Modifier.weight(1f),maxLines=1)
                                    Text(SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(Date(frame.capturedAt)),fontSize=11.sp,color=Color.Gray)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotsScreen(latest:CaptureSession?){
        val types=latest?.frames?.groupingBy{it.screenType}?.eachCount().orEmpty()
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{Text("Slots",fontSize=28.sp,fontWeight=FontWeight.Bold)}
            items((1..4).toList()){slot->
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(16.dp)){
                        Row(verticalAlignment=Alignment.CenterVertically){
                            Surface(shape=RoundedCornerShape(10.dp),color=Color(0xFF101E27)){
                                Text("S$slot",Modifier.padding(10.dp),color=Color(0xFFB8E34D),fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column{
                                Text("Slot $slot",fontWeight=FontWeight.Bold,fontSize=18.sp)
                                Text("Dados preenchidos conforme a navegação",fontSize=12.sp,color=Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("Última sessão: ${types.values.sum()} telas disponíveis para processamento.",fontSize=13.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun DirectorScreen(latest:CaptureSession?){
        val markets=latest?.frames?.count{it.screenType=="market"}?:0
        val trainings=latest?.frames?.count{it.screenType=="training"}?:0
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{
                Text("Diretor",fontSize=28.sp,fontWeight=FontWeight.Bold)
                Text("Mercado, treino e evolução a partir das telas realmente visitadas.",color=Color.Gray)
            }
            item{
                Row(horizontalArrangement=Arrangement.spacedBy(12.dp)){
                    Metric("Mercado",markets.toString(),Modifier.weight(1f))
                    Metric("Treinos",trainings.toString(),Modifier.weight(1f))
                }
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Nada é descartado",fontWeight=FontWeight.Bold)
                        Text("Se você abrir transferências, estádio, treino, classificação, resultado ou qualquer outra tela, ela fica preservada e categorizada na Jornada.")
                    }
                }
            }
        }
    }

    @Composable
    private fun SettingsScreen(serviceReady:Boolean){
        LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)){
            item{Text("Configurações",fontSize=28.sp,fontWeight=FontWeight.Bold)}
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Leitura automática",fontWeight=FontWeight.Bold)
                        Text(if(serviceReady)"Ativa. Não precisa autorizar de novo ao abrir o app." else "Desativada no Android.")
                        if(!serviceReady){
                            Spacer(Modifier.height(10.dp))
                            Button(onClick={startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))}){Text("Abrir Acessibilidade")}
                        }
                    }
                }
            }
            item{
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
                    Column(Modifier.padding(18.dp)){
                        Text("Arquitetura",fontWeight=FontWeight.Bold)
                        Text("Interface e armazenamento nativos. A internet é usada apenas quando o Coach precisa consultar IA/backend.")
                    }
                }
            }
        }
    }

    @Composable private fun Metric(label:String,value:String,modifier:Modifier=Modifier){
        Card(modifier,colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)){
            Column(Modifier.padding(16.dp)){Text(value,fontSize=28.sp,fontWeight=FontWeight.Bold);Text(label,color=Color.Gray)}
        }
    }

    private fun openOsm(){
        val launch=packageManager.getLaunchIntentForPackage(OSM_PACKAGE)
        if(launch!=null)startActivity(launch)
    }

    private fun isAccessibilityServiceEnabled():Boolean{
        val expected=ComponentName(this,OsmCaptureAccessibilityService::class.java).flattenToString()
        val enabled=runCatching{Settings.Secure.getInt(contentResolver,Settings.Secure.ACCESSIBILITY_ENABLED,0)==1}.getOrDefault(false)
        if(!enabled)return false
        val services=Settings.Secure.getString(contentResolver,Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?:return false
        return services.split(':').any{it.equals(expected,true)}
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
fun CoachTheme(content:@Composable()->Unit){
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

# Accessibility config
resxml=R/"android-collector/app/src/main/res/xml"
resxml.mkdir(parents=True,exist_ok=True)
(resxml/"accessibility_service_config.xml").write_text(r"""<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="120"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/accessibility_service_description" />
""", encoding="utf-8")

print("Native Pro v1 applied")
