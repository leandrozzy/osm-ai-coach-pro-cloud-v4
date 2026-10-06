from pathlib import Path

ROOT = Path(".")
PKG = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector"

MAIN = PKG / "MainActivity.kt"
PROCESSOR = PKG / "NativeSessionProcessor.kt"
REPO = PKG / "SessionRepository.kt"
CLASSIFIER = PKG / "ScreenClassifier.kt"
LOCAL = PKG / "LocalOcrExtractor.kt"
STORE = PKG / "NativeSlotStore.kt"
MODELS = PKG / "SessionModels.kt"

for f in (MAIN, PROCESSOR, REPO, CLASSIFIER, LOCAL, STORE, MODELS):
    if not f.exists():
        raise SystemExit(f"Arquivo não encontrado: {f}")

def replace_between(text: str, start: str, end: str, replacement: str) -> str:
    a = text.find(start)
    if a < 0:
        raise SystemExit(f"Bloco inicial não encontrado: {start}")
    b = text.find(end, a + len(start))
    if b < 0:
        raise SystemExit(f"Bloco final não encontrado: {end}")
    return text[:a] + replacement.rstrip() + "\n\n" + text[b:]

MODELS.write_text(r'''package com.osmaicoach.collector

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
    val screenTitle: String = "",
    val slotId: Int = 0,
    val ocrText: String = "",
    val analysisState: String = "captured",
    val extractedFields: Int = 0
)

data class CaptureSession(
    val id: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val frames: MutableList<CaptureFrame> = mutableListOf(),
    var state: String = "recording"
)

data class FrameAnalysisUpdate(
    val slotId: Int = 0,
    val screenType: String = "other",
    val screenTitle: String = "Tela do OSM",
    val ocrText: String = "",
    val analysisState: String = "ocr",
    val extractedFields: Int = 0
)

const val OSM_PACKAGE = "com.gamebasics.osm"
''', encoding="utf-8")

STORE.write_text(r'''package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NativePlayerData(
    var name: String = "NI",
    var position: String = "NI",
    var age: String = "NI",
    var strength: String = "NI",
    var value: String = "NI",
    var training: String = "NI",
    var selling: String = "NI"
)

data class NativeCalendarGame(
    var round: String = "NI",
    var opponent: String = "NI",
    var date: String = "NI",
    var time: String = "NI",
    var venue: String = "NI",
    var score: String = "NI",
    var competition: String = "NI",
    var cup: Boolean = false
)

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
    var players: MutableList<NativePlayerData> = mutableListOf(),
    var calendar: MutableList<NativeCalendarGame> = mutableListOf(),
    var lastUpdated: Long = 0L
)

class NativeSlotStore(context: Context) {
    private val prefs = context.getSharedPreferences("native_slots_v3", Context.MODE_PRIVATE)

    fun loadAll(): MutableList<NativeSlotData> {
        val raw = prefs.getString("slots", null) ?: return MutableList(4) { NativeSlotData(it + 1) }
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(4) { index ->
                val o = if (index < arr.length()) arr.getJSONObject(index) else JSONObject()
                val players = mutableListOf<NativePlayerData>()
                val pArr = o.optJSONArray("players") ?: JSONArray()
                for (i in 0 until pArr.length()) {
                    val p = pArr.optJSONObject(i) ?: continue
                    players += NativePlayerData(
                        name=p.optString("name","NI"),
                        position=p.optString("position","NI"),
                        age=p.optString("age","NI"),
                        strength=p.optString("strength","NI"),
                        value=p.optString("value","NI"),
                        training=p.optString("training","NI"),
                        selling=p.optString("selling","NI")
                    )
                }

                val calendar = mutableListOf<NativeCalendarGame>()
                val cArr = o.optJSONArray("calendar") ?: JSONArray()
                for (i in 0 until cArr.length()) {
                    val g = cArr.optJSONObject(i) ?: continue
                    calendar += NativeCalendarGame(
                        round=g.optString("round","NI"),
                        opponent=g.optString("opponent","NI"),
                        date=g.optString("date","NI"),
                        time=g.optString("time","NI"),
                        venue=g.optString("venue","NI"),
                        score=g.optString("score","NI"),
                        competition=g.optString("competition","NI"),
                        cup=g.optBoolean("cup",false)
                    )
                }

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
                    squadCount=o.optInt("squadCount",players.size),
                    attackers=o.optInt("attackers",0),
                    midfielders=o.optInt("midfielders",0),
                    defenders=o.optInt("defenders",0),
                    goalkeepers=o.optInt("goalkeepers",0),
                    trainingCount=o.optInt("trainingCount",0),
                    sellingCount=o.optInt("sellingCount",0),
                    calendarCount=o.optInt("calendarCount",calendar.size),
                    marketSeen=o.optBoolean("marketSeen",false),
                    trainingSeen=o.optBoolean("trainingSeen",false),
                    players=players,
                    calendar=calendar,
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

                put("players", JSONArray().apply {
                    s.players.forEach { p ->
                        put(JSONObject().apply {
                            put("name",p.name); put("position",p.position); put("age",p.age)
                            put("strength",p.strength); put("value",p.value)
                            put("training",p.training); put("selling",p.selling)
                        })
                    }
                })
                put("calendar", JSONArray().apply {
                    s.calendar.forEach { g ->
                        put(JSONObject().apply {
                            put("round",g.round); put("opponent",g.opponent); put("date",g.date)
                            put("time",g.time); put("venue",g.venue); put("score",g.score)
                            put("competition",g.competition); put("cup",g.cup)
                        })
                    }
                })
            })
        }
        prefs.edit().putString("slots", arr.toString()).apply()
    }
}
''', encoding="utf-8")

CLASSIFIER.write_text(r'''package com.osmaicoach.collector

object ScreenClassifier {
    data class Result(val type: String, val title: String)

    fun classify(text: String): Result {
        val t = normalize(text)
        if (t.isBlank()) return Result("other", "Tela do OSM")

        val rules = linkedMapOf(
            "calendar" to listOf("calendario","rodada","jornada","proximo jogo","fixtures","copa"),
            "squad" to listOf("elenco","plantel","jogadores","atacante","meio campo","goleiro","valor do jogador","idade"),
            "match" to listOf("analise","arbitro","formacao","marcacao","impedimento","adversario","forca geral"),
            "tactics" to listOf("tatica","pressao","ritmo","estilo de jogo","desarme","linha de ataque"),
            "market" to listOf("transferencia","lista de transferencias","mercado","comprar jogador","vender jogador","a venda"),
            "training" to listOf("treino","treinamento","campo de treinamento","training camp"),
            "result" to listOf("resultado","estatisticas","posse de bola","chutes","cartoes"),
            "ranking" to listOf("classificacao","tabela","ranking","pontos"),
            "club" to listOf("clube","estadio","financas","objetivo","valor do elenco")
        )

        var bestType = "other"
        var bestScore = 0
        for ((type, words) in rules) {
            var score = 0
            for (w in words) if (t.contains(normalize(w))) score++
            if (score > bestScore) {
                bestScore = score
                bestType = type
            }
        }

        val title = when(bestType) {
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
        return Result(bestType, title)
    }

    private fun normalize(v: String): String =
        java.text.Normalizer.normalize(v.lowercase(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun firstMeaningful(text: String): String =
        text.lines().map { it.trim() }.firstOrNull { it.length in 3..50 } ?: "Tela do OSM"
}
''', encoding="utf-8")

REPO.write_text(r'''package com.osmaicoach.collector

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

    fun latestSession(): CaptureSession? = parseSession(latestSessionJson())

    fun listSessions(limit: Int = 20): List<CaptureSession> =
        root.listFiles()
            ?.filter { it.isDirectory && File(it,"session.json").exists() }
            ?.sortedByDescending { File(it,"session.json").lastModified() }
            ?.take(limit)
            ?.mapNotNull { parseSession(File(it,"session.json").readText()) }
            ?: emptyList()

    fun frameFile(session: CaptureSession, index: Int): File? {
        val frame = session.frames.firstOrNull { it.index == index } ?: return null
        val file = File(File(root, session.id), frame.fileName)
        return file.takeIf { it.exists() }
    }

    @Synchronized
    fun updateLatestFrameAnalysis(updates: Map<Int, FrameAnalysisUpdate>) {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")
        if(!file.exists()) return
        runCatching {
            val json=JSONObject(file.readText())
            val frames=json.optJSONArray("frames") ?: return@runCatching
            for(i in 0 until frames.length()){
                val frame=frames.getJSONObject(i)
                val index=frame.optInt("index",i)
                val u=updates[index] ?: continue
                frame.put("slotId",u.slotId)
                frame.put("screenType",u.screenType)
                frame.put("screenTitle",u.screenTitle.take(80))
                frame.put("ocrText",u.ocrText.take(12000))
                frame.put("analysisState",u.analysisState)
                frame.put("extractedFields",u.extractedFields)
            }
            file.writeText(json.toString(2))
        }
    }

    @Synchronized
    fun markLatestFrames(indices: List<Int>, slotId:Int, state:String, extractedFields:Int) {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")
        if(!file.exists()) return
        runCatching {
            val json=JSONObject(file.readText())
            val frames=json.optJSONArray("frames") ?: return@runCatching
            val wanted=indices.toSet()
            for(i in 0 until frames.length()){
                val frame=frames.getJSONObject(i)
                if(frame.optInt("index",i) !in wanted) continue
                frame.put("slotId",slotId)
                frame.put("analysisState",state)
                frame.put("extractedFields",maxOf(frame.optInt("extractedFields",0),extractedFields))
            }
            file.writeText(json.toString(2))
        }
    }

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
                put("slotId",f.slotId);put("ocrText",f.ocrText)
                put("analysisState",f.analysisState);put("extractedFields",f.extractedFields)
            })
        }
        val json=JSONObject().apply{
            put("version",4);put("kind","osm-native-session");put("id",session.id)
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
                screenTitle=f.optString("screenTitle","Tela do OSM"),
                slotId=f.optInt("slotId",0),
                ocrText=f.optString("ocrText",""),
                analysisState=f.optString("analysisState","captured"),
                extractedFields=f.optInt("extractedFields",0)
            )
        }
        s
    }.getOrNull()
}
''', encoding="utf-8")

LOCAL.write_text(r'''package com.osmaicoach.collector

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.Normalizer
import java.util.Locale

class LocalOcrExtractor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).await().text.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap.recycle()
        }
    }

    fun applyToSlot(slot: NativeSlotData, texts: List<String>) {
        if (texts.isEmpty()) return
        val joined = texts.joinToString("\n")
        val lines = joined.lines().map { it.trim() }.filter { it.length in 2..120 }
        val n = normalize(joined)

        extractLabeled(lines, listOf("meu time","my team","clube","club"))?.let { candidate ->
            if (slot.team=="NI" && validName(candidate)) slot.team=candidate
        }
        extractLabeled(lines, listOf("competição","competicao","competition","liga","league"))?.let { candidate ->
            if (slot.competition=="NI" && validName(candidate)) slot.competition=candidate
        }
        extractLabeled(lines, listOf("adversário","adversario","opponent","rival","próximo rival","proximo rival"))?.let { candidate ->
            if (slot.nextRival=="NI" && validName(candidate)) slot.nextRival=candidate
        }

        extractLabeled(lines, listOf("árbitro","arbitro","referee"))?.let { candidate ->
            val r = normalize(candidate)
            val valid = listOf(
                "verde","azul","amarelo","laranja","vermelho",
                "muito rigoroso","rigoroso","medio","tolerante","leniente",
                "green","blue","yellow","orange","red"
            ).any { r.contains(normalize(it)) }
            if (slot.referee=="NI" && valid) slot.referee=candidate
        }

        extractLabeled(lines, listOf("estádio","estadio","stadium"))?.let {
            if (slot.stadium=="NI") slot.stadium=it
        }

        Regex("\\b[3-5]\\s*[-–]\\s*[1-5]\\s*[-–]\\s*[1-5]\\s*[AB]?\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.value?.replace(" ","")?.let { if (slot.rivalFormation=="NI") slot.rivalFormation=it }

        Regex("\\b(\\d{2,3})\\s*(?:x|×|vs\\.?)\\s*(\\d{2,3})\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.let {
                if (slot.myStrength=="NI") slot.myStrength=it.groupValues[1]
                if (slot.rivalStrength=="NI") slot.rivalStrength=it.groupValues[2]
            }

        val fullDate = Regex("\\b([0-3]?\\d/[01]?\\d/(?:20)?\\d{2})\\b").find(joined)?.value
        if (fullDate != null && slot.matchDate=="NI") slot.matchDate=fullDate

        Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(joined)?.value
            ?.let { if (slot.matchTime=="NI") slot.matchTime=it }

        if (slot.venue=="NI") {
            slot.venue = when {
                n.contains(" em casa ") || n.contains(" casa ") || n.contains(" home ") -> "Casa"
                n.contains(" fora ") || n.contains(" away ") -> "Fora"
                else -> "NI"
            }
        }
        if (slot.marking=="NI") {
            slot.marking = when {
                n.contains("marcacao a zona") || n.contains("zonal") -> "Marcação à zona"
                n.contains("marcacao individual") || n.contains("individual marking") -> "Marcação individual"
                else -> "NI"
            }
        }
        if (slot.rivalPlan=="NI") {
            slot.rivalPlan = when {
                n.contains("jogo de passes") || n.contains("passing game") -> "Jogo de passes"
                n.contains("pelas alas") || n.contains("wing play") -> "Pelas alas"
                n.contains("contra ataque") || n.contains("counter attack") -> "Contra-ataque"
                n.contains("bola longa") || n.contains("long ball") -> "Bola longa"
                n.contains("chutar de longe") || n.contains("shoot on sight") -> "Chutar de longe"
                else -> "NI"
            }
        }
        if (slot.offside=="NI") {
            slot.offside = when {
                n.contains("impedimento nao") || n.contains("offside no") -> "Não"
                n.contains("impedimento sim") || n.contains("offside yes") -> "Sim"
                else -> "NI"
            }
        }

        if(n.contains("mercado") || n.contains("transfer")) slot.marketSeen=true
        if(n.contains("treino") || n.contains("training")) slot.trainingSeen=true

        val dates = Regex("\\b[0-3]?\\d/[01]?\\d/(?:20)?\\d{2}\\b").findAll(joined).count()
        if (dates > slot.calendarCount) slot.calendarCount = dates

        val playerRows = lines.count {
            Regex("\\b\\d{1,2}\\s*(?:anos|years)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        if (playerRows > slot.squadCount) slot.squadCount = playerRows

        slot.attackers = maxOf(slot.attackers, countTokens(lines, listOf("ATA","ATT")))
        slot.midfielders = maxOf(slot.midfielders, countTokens(lines, listOf("MEI","MID")))
        slot.defenders = maxOf(slot.defenders, countTokens(lines, listOf("DEF")))
        slot.goalkeepers = maxOf(slot.goalkeepers, countTokens(lines, listOf("GOL","GK")))
    }

    private fun validName(v:String):Boolean {
        val n=normalize(v)
        if(n.length !in 2..60) return false
        if(n in setOf("boa","ni","sim","nao","casa","fora","liga normal")) return false
        return n.any { it.isLetter() }
    }

    private fun extractLabeled(lines: List<String>, labels: List<String>): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val n = normalize(line)
            val matched = labels.firstOrNull {
                n == normalize(it) ||
                n.startsWith(normalize(it)+" ") ||
                n.startsWith(normalize(it)+":")
            }
            if (matched != null) {
                val same = line.substringAfter(":", "").trim()
                if (same.isNotBlank() && normalize(same) != normalize(matched)) return cleanValue(same)
                if (i+1 < lines.size) {
                    val next = cleanValue(lines[i+1])
                    if (next.length in 2..60 && !looksLikeUiLabel(next)) return next
                }
            }
        }
        return null
    }

    private fun looksLikeUiLabel(v:String):Boolean {
        val n=normalize(v)
        return listOf(
            "calendario","elenco","tatica","mercado","treino","classificacao",
            "resultado","proximo jogo","arbitro","formacao","forca"
        ).any { n==it }
    }

    private fun cleanValue(v:String)=v.replace("|"," ").replace(Regex("\\s+")," ").trim().take(80)

    private fun countTokens(lines:List<String>,tokens:List<String>):Int =
        lines.count { line -> tokens.any { t -> Regex("\\b${Regex.escape(t)}\\b",RegexOption.IGNORE_CASE).containsMatchIn(line) } }

    private fun normalize(v:String):String =
        Normalizer.normalize(v.lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"),"")
            .replace(Regex("[^a-z0-9 :/.-]+")," ")
            .replace(Regex("\\s+")," ")
            .trim()
}
''', encoding="utf-8")

PROCESSOR.write_text(r'''package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

class NativeSessionProcessor(
    private val context: Context,
    private val repository: SessionRepository,
    private val store: NativeSlotStore
) {
    data class Progress(
        val running:Boolean=false,
        val current:Int=0,
        val total:Int=0,
        val label:String="",
        val success:Int=0,
        val failed:Int=0,
        val stage:String="idle",
        val lastError:String=""
    )

    private val prefs=context.getSharedPreferences("native_processor_v9",Context.MODE_PRIVATE)
    private val local=LocalOcrExtractor()
    @Volatile private var cloudError:String=""

    fun isProcessed(sessionId:String)=prefs.getString("processed_session",null)==sessionId

    suspend fun processLatest(force:Boolean=false,onProgress:(Progress)->Unit):Boolean=withContext(Dispatchers.IO){
        val session=repository.latestSession()?:return@withContext false
        if(session.state!="ready"||session.frames.isEmpty())return@withContext false
        if(!force&&isProcessed(session.id))return@withContext false

        val segments=splitIntoSlots(session)
        if(segments.isEmpty())return@withContext false

        val slots=store.loadAll()
        val total=session.frames.size + segments.size*3
        var current=0
        var success=0
        var failed=0
        var lastError=""

        onProgress(Progress(true,0,total,"OCR local: preparando ${session.frames.size} telas",0,0,"ocr",""))

        val ocrByIndex=linkedMapOf<Int,String>()
        session.frames.sortedBy{it.index}.forEach{frame->
            val file=repository.frameFile(session,frame.index)
            val visualText=if(file!=null) withTimeoutOrNull(12000L){local.read(file)} ?: "" else ""
            val text=listOf(frame.textHint,visualText).filter{it.isNotBlank()}.distinct().joinToString("\n")
            ocrByIndex[frame.index]=text
            current++
            val readable=ocrByIndex.values.count{it.isNotBlank()}
            prefs.edit().putInt("local_ocr_readable",readable).putInt("local_ocr_total",session.frames.size).apply()
            onProgress(Progress(true,current,total,"OCR local · tela ${frame.index+1}/${session.frames.size} · $readable com texto",success,failed,"ocr",lastError))
        }

        val classifications = ocrByIndex.mapValues { (_, text) -> ScreenClassifier.classify(text) }
        val frameToSlot=mutableMapOf<Int,Int>()
        segments.forEachIndexed { slotIndex, rows ->
            rows.forEach { frameToSlot[it]=slotIndex+1 }
        }

        repository.updateLatestFrameAnalysis(
            ocrByIndex.mapValues { (index,text) ->
                val c=classifications[index] ?: ScreenClassifier.Result("other","Tela do OSM")
                FrameAnalysisUpdate(
                    slotId=frameToSlot[index] ?: 0,
                    screenType=c.type,
                    screenTitle=c.title,
                    ocrText=text,
                    analysisState=if(text.isBlank())"sem OCR" else "OCR ✓",
                    extractedFields=0
                )
            }
        )

        segments.forEachIndexed{slotIndex,indices->
            val slot=slots[slotIndex.coerceIn(0,3)]
            val useful=indices.filter { idx -> classifications[idx]?.type!="other" }
            val source=if(useful.isNotEmpty())useful else indices
            val texts=source.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            slot.lastUpdated=System.currentTimeMillis()
        }
        store.saveAll(slots)

        val endpoint=probeBackend()
        if(endpoint==null){
            lastError="IA Cloud indisponível. OCR local foi mantido."
            failed += segments.size*3
            current=total
            prefs.edit().putString("processed_session",session.id).apply()
            onProgress(Progress(false,current,total,"Sessão concluída com OCR local",success,failed,"done",lastError))
            return@withContext true
        }

        fun rowsForType(rows:List<Int>, type:String):List<Int>{
            val direct=rows.filter { classifications[it]?.type==type }
            if(direct.isNotEmpty()) return direct
            val fallbackTypes=when(type){
                "match" -> setOf("club","tactics","result")
                "squad" -> setOf("training","market")
                "calendar" -> setOf("ranking")
                else -> emptySet()
            }
            val related=rows.filter { classifications[it]?.type in fallbackTypes }
            return if(related.isNotEmpty()) related else rows
        }

        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            jobs+=Triple(i,"match",rowsForType(rows,"match"))
            jobs+=Triple(i,"squad",rowsForType(rows,"squad"))
            jobs+=Triple(i,"calendar",rowsForType(rows,"calendar"))
        }

        jobs.forEach{job->
            val slot=slots[job.first.coerceIn(0,3)]
            val friendly=when(job.second){"match"->"Pré-jogo";"squad"->"Elenco";else->"Calendário"}
            onProgress(Progress(true,current,total,"S${slot.id} · $friendly · IA",success,failed,"cloud",lastError))

            val maxImages=if(job.second=="match")3 else 2
            val result=withTimeoutOrNull(65000L){analyzeType(endpoint,session,job.third,job.second,maxImages,slot)}
            if(result==null){
                failed++
                val detail=cloudError.ifBlank{"sem resposta dentro do limite"}
                lastError="S${slot.id} $friendly: $detail"
                repository.markLatestFrames(job.third,slot.id,"IA falhou",0)
            }else{
                val before=knownCount(slot)
                val changed=runCatching{applyResult(slot,result.first,result.second)}.getOrElse { 0 }
                val after=knownCount(slot)
                if(after>before || changed>0){
                    success++
                    repository.markLatestFrames(job.third,slot.id,"Interpretada ✓",maxOf(changed,after-before))
                }else{
                    failed++
                    lastError="S${slot.id} $friendly: resposta recebida, mas sem dados úteis para este slot"
                    repository.markLatestFrames(job.third,slot.id,"Sem dado útil",0)
                }
            }
            current++
            slot.lastUpdated=System.currentTimeMillis()
            store.saveAll(slots)
            onProgress(Progress(true,current,total,"S${slot.id} · $friendly · concluído",success,failed,"cloud",lastError))
        }

        prefs.edit().putString("processed_session",session.id).apply()
        val readable=prefs.getInt("local_ocr_readable",0)
        onProgress(Progress(false,total,total,"Sessão finalizada · OCR $readable/${session.frames.size}",success,failed,"done",lastError))
        true
    }

    private fun probeBackend():String?{
        val candidates=listOf(BuildConfig.BACKEND_URL,BuildConfig.BACKEND_FALLBACK_URL)
            .filter{it.isNotBlank()}.distinct()
        for(base in candidates){
            try{
                val conn=(URL(base.trimEnd('/')+"/api/analyze").openConnection() as HttpURLConnection).apply{
                    requestMethod="GET";connectTimeout=6000;readTimeout=6000;instanceFollowRedirects=true
                    setRequestProperty("User-Agent","OSM-AI-Coach-Native/9")
                }
                val code=conn.responseCode
                conn.disconnect()
                if(code in 200..499)return base
            }catch(_:Throwable){}
        }
        return null
    }

    private fun splitIntoSlots(session:CaptureSession):List<List<Int>>{
        val frames=session.frames.sortedBy{it.capturedAt}
        if(frames.isEmpty())return emptyList()

        val gaps=mutableListOf<Pair<Int,Long>>()
        for(i in 1 until frames.size) {
            gaps += i to (frames[i].capturedAt-frames[i-1].capturedAt).coerceAtLeast(0)
        }
        val cuts=gaps.filter{it.second>=2500L}
            .sortedByDescending{it.second}.take(3).map{it.first}.sorted()

        val result=mutableListOf<List<Int>>()
        if(cuts.size==3){
            var from=0
            for(cut in cuts+frames.size){
                if(cut>from)result+=frames.subList(from,cut).map{it.index}
                from=cut
            }
        }

        if(result.size!=4 && frames.size>=8){
            result.clear()
            var from=0
            for(i in 0 until 4){
                val to=if(i==3)frames.size else ((i+1)*frames.size/4)
                if(to>from) result+=frames.subList(from,to).map{it.index}
                from=to
            }
        }

        if(result.isEmpty())result+=frames.map{it.index}
        return result.take(4)
    }

    private suspend fun analyzeType(
        endpointBase:String,session:CaptureSession,indices:List<Int>,type:String,maxImages:Int,slot:NativeSlotData
    ):Pair<String,JSONObject>?=withContext(Dispatchers.IO){
        val selected=sample(indices,maxImages)
        if(selected.isEmpty())return@withContext null
        val images=JSONArray()
        selected.forEach{index->
            val file=repository.frameFile(session,index)?:return@forEach
            val encoded=encodeImage(file.absolutePath)?:return@forEach
            images.put(JSONObject().apply{
                put("url",encoded.first)
                put("width",encoded.second.first)
                put("height",encoded.second.second)
                put("frameIndex",index)
                put("region","full")
            })
        }
        if(images.length()==0)return@withContext null

        val body=JSONObject().apply{
            put("type",type)
            put("images",images)
            put("ocrImages",images)
            put("forceOCR",true)
            put("useVisual",true)
            put("useText",true)
            put("context",JSONObject().apply{
                put("username","leandrozzy")
                if(slot.team!="NI")put("myTeam",slot.team)
                put("competitionType",slot.competitionType)
                if(slot.nextRival!="NI")put("rivalName",slot.nextRival)
            })
        }

        val conn=(URL(endpointBase.trimEnd('/')+"/api/analyze").openConnection() as HttpURLConnection).apply{
            requestMethod="POST";connectTimeout=12000;readTimeout=56000;doOutput=true;instanceFollowRedirects=true
            setRequestProperty("Content-Type","application/json")
            setRequestProperty("Accept","application/json")
            setRequestProperty("User-Agent","OSM-AI-Coach-Native/9")
        }

        return@withContext try{
            conn.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8));it.flush()}
            val code=conn.responseCode
            val raw=(if(code in 200..299)conn.inputStream else conn.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            if(code !in 200..299){
                cloudError="HTTP $code: "+raw.take(220).replace("\n"," ")
                null
            }else if(raw.isBlank()){
                cloudError="HTTP $code sem conteúdo"
                null
            }else{
                val root=JSONObject(raw)
                val data=root.optJSONObject("data")
                if(data==null){
                    cloudError="Resposta sem campo data: "+raw.take(220).replace("\n"," ")
                    null
                }else{
                    cloudError=""
                    type to data
                }
            }
        }catch(e:Throwable){
            cloudError=(e.javaClass.simpleName+": "+(e.message?:"erro de rede")).take(240)
            null
        }finally{conn.disconnect()}
    }

    private fun applyResult(slot:NativeSlotData,type:String,data:JSONObject):Int =
        when(type){
            "match"->applyMatch(slot,data.optJSONObject("match")?:JSONObject())
            "squad"->applySquad(slot,data)
            "calendar"->applyCalendar(slot,data)
            else->0
        }

    private fun applyMatch(slot:NativeSlotData,m:JSONObject):Int{
        var changed=0
        fun put(key:String,set:(String)->Unit){
            val v=knownString(m,key) ?: return
            set(v); changed++
        }

        put("myName"){slot.team=it};put("rivalName"){slot.nextRival=it}
        put("location"){slot.venue=it}
        put("referee"){ if(validReferee(it)) slot.referee=it else changed-- }
        put("myStrength"){slot.myStrength=it};put("rivalStrength"){slot.rivalStrength=it}
        put("mySquadValue"){slot.myValue=it};put("rivalSquadValue"){slot.rivalValue=it}
        put("myGK"){slot.myGoalkeeper=it};put("myDEF"){slot.myDefense=it}
        put("myMID"){slot.myMidfield=it};put("myATT"){slot.myAttack=it}
        put("rivalGK"){slot.rivalGoalkeeper=it};put("rivalDEF"){slot.rivalDefense=it}
        put("rivalMID"){slot.rivalMidfield=it};put("rivalATT"){slot.rivalAttack=it}
        put("rivalFormation"){slot.rivalFormation=it};put("rivalPlan"){slot.rivalPlan=it}
        put("rivalMarking"){slot.marking=it};put("rivalOffside"){slot.offside=it}
        put("secretTraining"){slot.secretTraining=it};put("trainingCamp"){slot.trainingCamp=it}
        put("stadium"){slot.stadium=it};put("myBonus"){slot.bonus=it}
        return changed
    }

    private fun applySquad(slot:NativeSlotData,data:JSONObject):Int{
        var changed=0
        val meta=data.optJSONObject("meta")?:JSONObject()

        fun putMeta(key:String,set:(String)->Unit){
            val v=knownString(meta,key) ?: return
            set(v); changed++
        }
        putMeta("team"){slot.team=it};putMeta("competition"){slot.competition=it}
        putMeta("competitionType"){slot.competitionType=it};putMeta("squadValue"){slot.myValue=it}
        putMeta("strength"){slot.myStrength=it};putMeta("GK"){slot.myGoalkeeper=it}
        putMeta("DEF"){slot.myDefense=it};putMeta("MID"){slot.myMidfield=it};putMeta("ATT"){slot.myAttack=it}

        val arr=data.optJSONArray("players")?:JSONArray()
        if(arr.length()>0){
            val parsed=mutableListOf<NativePlayerData>()
            for(i in 0 until arr.length()){
                val p=arr.optJSONObject(i)?:continue
                val name=firstKnown(p,"name","player","playerName") ?: continue
                parsed += NativePlayerData(
                    name=name,
                    position=firstKnown(p,"position","pos","role") ?: "NI",
                    age=firstKnown(p,"age") ?: "NI",
                    strength=firstKnown(p,"strength","rating","overall") ?: "NI",
                    value=firstKnown(p,"value","marketValue") ?: "NI",
                    training=firstKnown(p,"training","inTraining") ?: "NI",
                    selling=firstKnown(p,"selling","forSale","listed") ?: "NI"
                )
            }
            if(parsed.isNotEmpty()){
                slot.players = parsed.distinctBy{it.name.lowercase()+"|"+it.position.lowercase()}.toMutableList()
                slot.squadCount=slot.players.size
                slot.attackers=slot.players.count{normPos(it.position)=="ATA"}
                slot.midfielders=slot.players.count{normPos(it.position)=="MEI"}
                slot.defenders=slot.players.count{normPos(it.position)=="DEF"}
                slot.goalkeepers=slot.players.count{normPos(it.position)=="GOL"}
                slot.trainingCount=slot.players.count{truthy(it.training)}
                slot.sellingCount=slot.players.count{truthy(it.selling)}
                changed += slot.players.size
            }
        }
        return changed
    }

    private fun applyCalendar(slot:NativeSlotData,data:JSONObject):Int{
        var changed=0
        val meta=data.optJSONObject("meta")?:JSONObject()
        fun putMeta(key:String,set:(String)->Unit){
            val v=knownString(meta,key) ?: return
            set(v); changed++
        }
        putMeta("team"){slot.team=it};putMeta("competition"){slot.competition=it};putMeta("competitionType"){slot.competitionType=it}

        val rows=data.optJSONArray("calendar")?:JSONArray()
        if(rows.length()==0)return changed

        val parsed=mutableListOf<NativeCalendarGame>()
        for(i in 0 until rows.length()){
            val row=rows.optJSONObject(i)?:continue
            val opponent=firstKnown(row,"opponent","rival","team") ?: "NI"
            val home = if(row.has("home")&&!row.isNull("home")) row.optBoolean("home") else null
            parsed += NativeCalendarGame(
                round=firstKnown(row,"round","matchday") ?: "NI",
                opponent=opponent,
                date=firstKnown(row,"date") ?: "NI",
                time=firstKnown(row,"time") ?: "NI",
                venue=when(home){true->"Casa";false->"Fora";null->firstKnown(row,"venue","location") ?: "NI"},
                score=firstKnown(row,"score","displayedScore","result") ?: "NI",
                competition=firstKnown(row,"competition","type") ?: slot.competition,
                cup=row.optBoolean("cup",false)
            )
        }

        if(parsed.isNotEmpty()){
            slot.calendar=parsed.distinctBy{
                listOf(it.round,it.opponent,it.date,it.time).joinToString("|").lowercase()
            }.toMutableList()
            slot.calendarCount=slot.calendar.size
            changed += slot.calendar.size

            val future=slot.calendar.firstOrNull {
                it.score=="NI" || it.score.isBlank() || it.score=="-" || !Regex("\\d+\\s*[xX:-]\\s*\\d+").containsMatchIn(it.score)
            } ?: slot.calendar.lastOrNull()

            future?.let{
                if(it.opponent!="NI")slot.nextRival=it.opponent
                if(it.date!="NI")slot.matchDate=it.date
                if(it.time!="NI")slot.matchTime=it.time
                if(it.venue!="NI")slot.venue=it.venue
            }
        }
        return changed
    }

    private fun knownString(o:JSONObject,key:String):String?{
        if(!o.has(key)||o.isNull(key))return null
        val v=o.opt(key)?.toString()?.trim().orEmpty()
        return v.takeIf{it.isNotBlank()&&it!="NI"&&it!="null"}
    }

    private fun firstKnown(o:JSONObject,vararg keys:String):String?{
        for(k in keys) knownString(o,k)?.let{return it}
        return null
    }

    private fun validReferee(v:String):Boolean{
        val n=v.lowercase()
        return listOf("verde","azul","amarelo","laranja","vermelho","rigoroso","médio","medio","tolerante","green","blue","yellow","orange","red").any{n.contains(it)}
    }

    private fun normPos(v:String):String{
        val n=v.uppercase()
        return when{
            n.contains("ATA")||n.contains("ATT")||n.contains("FW") -> "ATA"
            n.contains("MEI")||n.contains("MID")||n.contains("MF") -> "MEI"
            n.contains("DEF")||n.contains("CB")||n.contains("LB")||n.contains("RB") -> "DEF"
            n.contains("GOL")||n.contains("GK") -> "GOL"
            else -> "NI"
        }
    }

    private fun truthy(v:String):Boolean{
        val n=v.lowercase()
        return n=="sim"||n=="yes"||n=="true"||n=="1"||n.contains("trein")||n.contains("venda")
    }

    private fun knownCount(slot:NativeSlotData):Int {
        val values=listOf(
            slot.team,slot.competition,slot.nextRival,slot.matchDate,slot.matchTime,slot.venue,slot.referee,
            slot.myStrength,slot.rivalStrength,slot.myValue,slot.rivalValue,
            slot.myGoalkeeper,slot.myDefense,slot.myMidfield,slot.myAttack,
            slot.rivalGoalkeeper,slot.rivalDefense,slot.rivalMidfield,slot.rivalAttack,
            slot.rivalFormation,slot.rivalPlan,slot.marking,slot.offside,
            slot.secretTraining,slot.trainingCamp,slot.stadium,slot.bonus
        )
        return values.count { it.isNotBlank() && it!="NI" && it!="null" } +
            slot.players.size + slot.calendar.size
    }

    private fun sample(rows:List<Int>,max:Int):List<Int>{
        if(rows.size<=max)return rows
        if(max<=1)return listOf(rows[rows.size/2])
        return (0 until max).map{i->rows[((i.toDouble()*(rows.size-1))/(max-1)).toInt()]}.distinct()
    }

    private fun encodeImage(path:String):Pair<String,Pair<Int,Int>>?{
        val original=BitmapFactory.decodeFile(path)?:return null
        val targetWidth=minOf(900,original.width)
        val targetHeight=(original.height*(targetWidth.toFloat()/original.width)).toInt().coerceAtLeast(1)
        val scaled=if(original.width!=targetWidth)Bitmap.createScaledBitmap(original,targetWidth,targetHeight,true) else original
        val width=scaled.width;val height=scaled.height
        var quality=60;var bytes:ByteArray
        do{
            val out=ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG,quality,out)
            bytes=out.toByteArray()
            quality-=7
        }while(bytes.size>450_000&&quality>=32)
        if(scaled!==original)scaled.recycle()
        original.recycle()
        return "data:image/jpeg;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP) to (width to height)
    }
}
''', encoding="utf-8")

main = MAIN.read_text(encoding="utf-8")

sessions_block = r'''    @Composable
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
                            Text(formatTime(session.startedAt), fontWeight=FontWeight.Bold)
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
    }'''

main = replace_between(
    main,
    "    @Composable\n    private fun SessionsScreen",
    "    @Composable\n    private fun SlotsScreen",
    sessions_block
)

squad_block = r'''    @Composable
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
    }'''

main = replace_between(
    main,
    "    @Composable\n    private fun SlotSquad",
    "    @Composable\n    private fun SlotCalendar",
    squad_block
)

calendar_block = r'''    @Composable
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
    }'''

main = replace_between(
    main,
    "    @Composable\n    private fun SlotCalendar",
    "    @Composable\n    private fun SlotTactics",
    calendar_block
)

MAIN.write_text(main, encoding="utf-8")

print("FIX ESTRUTURAL APLICADO")
print("Arquivos atualizados:")
for f in (MODELS, STORE, CLASSIFIER, REPO, LOCAL, PROCESSOR, MAIN):
    print(" -", f)
print("Resultado esperado: Sessões por slot/tipo, elenco completo persistente e calendário completo persistente.")
