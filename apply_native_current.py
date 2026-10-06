from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent

def read(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"Arquivo não encontrado: {rel}")
    return p, p.read_text(encoding="utf-8")

def write_if_changed(path, text):
    old = path.read_text(encoding="utf-8")
    if old == text:
        print("UNCHANGED:", path.relative_to(ROOT))
        return 0
    path.write_text(text, encoding="utf-8")
    print("PATCHED:", path.relative_to(ROOT))
    return 1

def replace_required(text, old, new, label):
    if old in text:
        return text.replace(old, new, 1)
    if new in text:
        return text
    raise SystemExit(f"PATCH ABORTADO: trecho não encontrado em {label}")

main_p, main = read("android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt")
svc_p, svc = read("android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt")
proc_p, proc = read("android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt")
tracker_p, tracker = read("android-collector/app/src/main/java/com/osmaicoach/collector/SlotNavigationTracker.kt")
gradle_p, gradle = read("android-collector/app/build.gradle.kts")

# -----------------------------------------------------------------------------
# V23.2
# Corrige definitivamente:
# 1) erro do apply_native_current.py com "bad escape \\d"
# 2) rastreamento dos 4 slots mais tolerante
# 3) sessão explícita continua capturando mesmo quando Android informa SystemUI
# 4) não apaga dados antigos; processamento continua incremental
# -----------------------------------------------------------------------------

# A sessão iniciada pelo botão deve continuar ativa mesmo se o Android reportar
# com.android.systemui, launcher ou anúncio enquanto o OSM está aberto.
svc = replace_required(
    svc,
    """    private fun shouldKeepCapturing(): Boolean {
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }""",
    """    private fun shouldKeepCapturing(): Boolean {
        if (isExplicitSessionArmed()) return true
        val pkg = detectForegroundPackage() ?: CollectorState.currentForegroundPackage
        return pkg == OSM_PACKAGE
    }""",
    "OsmCaptureAccessibilityService.shouldKeepCapturing"
)

# Rastreamento: confiança menos rígida. A tela central do OSM é a âncora real
# para separar as visitas aos slots.
tracker = replace_required(
    tracker,
    """            val confident = bestSlot in 1..4 && bestScore >= 5 && (bestScore-secondScore >= 2 || bestScore >= 14)""",
    """            val confident = bestSlot in 1..4 && bestScore >= 2 && (bestScore-secondScore >= 1 || bestScore >= 10)""",
    "SlotNavigationTracker.confident"
)

# Se o time/competição já existe no banco, usa como assinatura forte sem
# substituir informação válida por texto ruim do OCR.
tracker = replace_required(
    tracker,
    """                    val strong = when {
                        team.length >= 4 && n.contains(team) -> 12
                        comp.length >= 5 && n.contains(comp) -> 7
                        else -> 0
                    }""",
    """                    val strong = when {
                        team.length >= 3 && n.contains(team) -> 16
                        comp.length >= 4 && n.contains(comp) -> 10
                        else -> 0
                    }""",
    "SlotNavigationTracker.direct"
)

# Mais tolerância para reconhecer a tela dos quatro slots.
tracker = replace_required(
    tracker,
    """        if (rounds in 3..8 && leagueWords >= 1) return true
        if (rounds >= 3 && n.contains("leandrozzy")) return true""",
    """        if (rounds in 3..8 && leagueWords >= 1) return true
        if (rounds >= 3 && n.contains("leandrozzy")) return true
        if (rounds >= 3 && listOf("slot","manager","treinador","liga","batalha").any { n.contains(it) }) return true""",
    "SlotNavigationTracker.isSlotsHub"
)

# Diagnóstico preserva sessão ao invés de destruir/substituir dados quando
# nenhuma visita puder ser atribuída com confiança.
old_diag = """        if (segments.all { it.isEmpty() }) {
            prefs.edit()
                .putString("slot_tracker_summary", tracked.summary)
                .putInt("slot_hub_frames", tracked.hubFrames.size)
                .putInt("slot_unassigned_frames", session.frames.size)
                .apply()

            val message = "Nenhuma visita pôde ser ligada a S1-S4. Reprocesse com a V23 ou faça uma nova sessão iniciada pelo botão Abrir OSM."
            onProgress(
                Progress(
                    false,
                    total,
                    total,
                    "Sessão preservada — rastreamento pendente",
                    0,
                    1,
                    "slot-map",
                    message
                )
            )
            return@withContext true
        }"""

new_diag = """        if (segments.all { it.isEmpty() }) {
            prefs.edit()
                .putString("slot_tracker_summary", tracked.summary)
                .putInt("slot_hub_frames", tracked.hubFrames.size)
                .putInt("slot_unassigned_frames", session.frames.size)
                .apply()

            val message = "Sessão preservada: nenhuma visita foi ligada com segurança a S1-S4. Os dados anteriores foram mantidos."
            onProgress(
                Progress(
                    false,
                    total,
                    total,
                    "Sessão preservada — rastreamento pendente",
                    0,
                    1,
                    "slot-map",
                    message
                )
            )
            return@withContext true
        }"""

proc = replace_required(proc, old_diag, new_diag, "NativeSessionProcessor.slot-map")

# Atualiza somente a identificação visual da versão. Não mexe em dados.
main = re.sub(
    r'DetailLine\("Versão nativa","V\d+(?:\.\d+)?\s*·\s*\$\{BuildConfig\.VERSION_NAME\}"\)',
    'DetailLine("Versão nativa","V23.2 · ${BuildConfig.VERSION_NAME}")',
    main,
    count=1
)

# APK sempre recebe versionName novo no workflow.
gradle = re.sub(
    r'versionName\s*=\s*"[^"]*\$runNumber"',
    'versionName = "4.2.$runNumber"',
    gradle,
    count=1
)

changed = 0
changed += write_if_changed(main_p, main)
changed += write_if_changed(svc_p, svc)
changed += write_if_changed(proc_p, proc)
changed += write_if_changed(tracker_p, tracker)
changed += write_if_changed(gradle_p, gradle)

print()
print("V23.2 aplicada com sucesso.")
print("Arquivos alterados:", changed)
