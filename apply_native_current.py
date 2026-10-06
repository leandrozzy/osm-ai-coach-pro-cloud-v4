from pathlib import Path

# A fonte Android atual já está aplicada diretamente no repositório.
# Este arquivo permanece apenas por compatibilidade com uploads antigos.
required = [
    Path("android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"),
    Path("android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt"),
    Path("android-collector/app/src/main/java/com/osmaicoach/collector/SessionRepository.kt"),
    Path("android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"),
]
missing=[str(p) for p in required if not p.exists()]
if missing:
    raise SystemExit("Arquivos Android ausentes: "+", ".join(missing))
print("OSM AI Coach Native Pro v12: fonte atual já aplicada; nenhum patch adicional necessário.")
