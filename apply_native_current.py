from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent

def load(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"Arquivo ausente: {rel}")
    return p, p.read_text(encoding="utf-8")

def save_if_changed(p, old, new):
    if old != new:
        p.write_text(new, encoding="utf-8")
        print(f"PATCHED: {p.relative_to(ROOT)}")
        return 1
    print(f"OK/UNCHANGED: {p.relative_to(ROOT)}")
    return 0

changed = 0

# 1) Processor
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"
p, s = load(rel)
old = s

s = s.replace(
    'val candidates=listOf(BuildConfig.BACKEND_URL,BuildConfig.BACKEND_FALLBACK_URL)',
    'val candidates=listOf(BuildConfig.BACKEND_FALLBACK_URL,BuildConfig.BACKEND_URL)'
)

s = s.replace(
    'repository.updateLatestFrameAnalysis(\n            ocrByIndex.mapValues',
    'repository.updateFrameAnalysis(session.id,\n            ocrByIndex.mapValues'
)

s = s.replace(
    'repository.markLatestFrames(job.third,slot.id,"IA falhou",0)',
    'repository.markFrames(session.id,job.third,slot.id,"IA falhou",0)'
)
s = s.replace(
    'repository.markLatestFrames(job.third,slot.id,"Interpretada ✓",maxOf(changed,after-before))',
    'repository.markFrames(session.id,job.third,slot.id,"Interpretada ✓",maxOf(changed,after-before))'
)
s = s.replace(
    'repository.markLatestFrames(job.third,slot.id,"Sem dado útil",0)',
    'repository.markFrames(session.id,job.third,slot.id,"Sem dado útil",0)'
)

s = s.replace(
'''        // Pausas muito longas costumam ser propaganda/app externo e não troca de slot.
        // Só usamos pausas moderadas como possível fronteira; caso contrário dividimos
        // a navegação sequencial em quatro blocos estáveis.
        val cuts=gaps.filter{it.second in 2500L..15000L}
            .sortedByDescending{it.second}.take(3).map{it.first}.sorted()''',
'''        // V17: separar os slots pelas maiores pausas reais da navegação.
        // Propagandas não encerram a sessão. Ignoramos apenas micro-pausas e
        // ausências muito longas, evitando o corte artificial em quatro quartos.
        val cuts=gaps.filter{it.second in 1400L..90000L}
            .sortedByDescending{it.second}.take(3).map{it.first}.sorted()'''
)

changed += save_if_changed(p, old, s)

# 2) Repository
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/SessionRepository.kt"
p, s = load(rel)
old = s

s = s.replace(
'''    @Synchronized
    fun updateLatestFrameAnalysis(updates: Map<Int, FrameAnalysisUpdate>) {
        val id=prefs.getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")''',
'''    @Synchronized
    fun updateFrameAnalysis(sessionId: String, updates: Map<Int, FrameAnalysisUpdate>) {
        val file=File(File(root,sessionId),"session.json")'''
)

s = s.replace(
'''    @Synchronized
    fun markLatestFrames(indices: List<Int>, slotId:Int, state:String, extractedFields:Int) {
        val id=prefs.getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")''',
'''    @Synchronized
    fun markFrames(sessionId: String, indices: List<Int>, slotId:Int, state:String, extractedFields:Int) {
        val file=File(File(root,sessionId),"session.json")'''
)

changed += save_if_changed(p, old, s)

# 3) OCR local
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/LocalOcrExtractor.kt"
p, s = load(rel)
old = s

old_read = '''    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).await().text.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap.recycle()
        }
    }'''

new_read = '''    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        var cropped: android.graphics.Bitmap? = null
        return try {
            // V17: remove status bar e barra de navegação antes do OCR.
            // Isso evita horário, bateria e temperatura virarem dados do jogo.
            val top = (bitmap.height * 0.055f).toInt().coerceAtLeast(0)
            val bottom = (bitmap.height * 0.075f).toInt().coerceAtLeast(0)
            val usableHeight = (bitmap.height - top - bottom).coerceAtLeast(1)
            cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, top, bitmap.width, usableHeight)
            val image = InputImage.fromBitmap(cropped!!, 0)
            recognizer.process(image).await().text.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            cropped?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }'''

if old_read in s:
    s = s.replace(old_read, new_read)

s = s.replace(
'''        if(n in setOf("boa","ni","sim","nao","casa","fora","liga normal")) return false
        return n.any { it.isLetter() }''',
'''        if(n in setOf("boa","ni","sim","nao","casa","fora","liga normal")) return false
        if(listOf("proximo","dados completos","leitura automatica","sessao","osm ai coach").any { n.contains(it) }) return false
        return n.any { it.isLetter() }'''
)

needle = '''            val pos=when {
                window.contains(Regex("\\\\b(?:ATA|ATT|FW)\\\\b",RegexOption.IGNORE_CASE))->"ATA"
                window.contains(Regex("\\\\b(?:MEI|MID|MF)\\\\b",RegexOption.IGNORE_CASE))->"MEI"
                window.contains(Regex("\\\\b(?:DEF|CB|LB|RB)\\\\b",RegexOption.IGNORE_CASE))->"DEF"
                window.contains(Regex("\\\\b(?:GOL|GK)\\\\b",RegexOption.IGNORE_CASE))->"GOL"
                else->"NI"
            }
            if(slot.players.none{normalize(it.name)==normalize(line)}) {'''

replacement = '''            val pos=when {
                window.contains(Regex("\\\\b(?:ATA|ATT|FW)\\\\b",RegexOption.IGNORE_CASE))->"ATA"
                window.contains(Regex("\\\\b(?:MEI|MID|MF)\\\\b",RegexOption.IGNORE_CASE))->"MEI"
                window.contains(Regex("\\\\b(?:DEF|CB|LB|RB)\\\\b",RegexOption.IGNORE_CASE))->"DEF"
                window.contains(Regex("\\\\b(?:GOL|GK)\\\\b",RegexOption.IGNORE_CASE))->"GOL"
                else->"NI"
            }
            if(value=="NI" && pos=="NI") continue
            if(slot.players.none{normalize(it.name)==normalize(line)}) {'''

if needle in s:
    s = s.replace(needle, replacement)

changed += save_if_changed(p, old, s)

# 4) UI diagnostics and visible version marker
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
p, s = load(rel)
old = s

s = s.replace('getSharedPreferences("native_processor_v12",MODE_PRIVATE)',
              'getSharedPreferences("native_processor_v13",MODE_PRIVATE)')
s = s.replace('DetailLine("Versão nativa","V16 · ${BuildConfig.VERSION_NAME}")',
              'DetailLine("Versão nativa","V17 · ${BuildConfig.VERSION_NAME}")')
s = s.replace('DetailLine("Versão nativa","V15 · ${BuildConfig.VERSION_NAME}")',
              'DetailLine("Versão nativa","V17 · ${BuildConfig.VERSION_NAME}")')

changed += save_if_changed(p, old, s)

# 5) Build version
rel = "android-collector/app/build.gradle.kts"
p, s = load(rel)
old = s

s = re.sub(r'versionName\s*=\s*"2\.[0-9]+\.\$runNumber"',
           'versionName = "2.5.$runNumber"', s)
s = re.sub(r'versionName\s*=\s*"2\.[0-9]+\.\$runNumber\.\$runAttempt"',
           'versionName = "2.5.$runNumber.$runAttempt"', s)

changed += save_if_changed(p, old, s)

print("")
print(f"OSM AI Coach Native Pro V17 aplicado. Arquivos alterados: {changed}")
print("Correções: backend correto da branch Android, OCR sem barras do sistema,")
print("persistência por sessionId, divisão S1-S4 mais robusta, filtro de falsos dados")
print("e diagnóstico coerente com o Processor.")
