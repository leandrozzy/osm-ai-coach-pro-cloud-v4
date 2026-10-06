from pathlib import Path

ROOT = Path(__file__).resolve().parent

required = [
    "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt",
    "android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt",
    "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt",
    "android-collector/app/src/main/java/com/osmaicoach/collector/SlotNavigationTracker.kt",
    "android-collector/app/build.gradle.kts",
]

missing = [rel for rel in required if not (ROOT / rel).exists()]
if missing:
    raise SystemExit("Arquivos nativos ausentes:\n- " + "\n- ".join(missing))

print("OK: fontes nativas presentes.")
print("Nenhum patch automático foi aplicado.")
print("O código versionado no GitHub é a fonte única da build.")
