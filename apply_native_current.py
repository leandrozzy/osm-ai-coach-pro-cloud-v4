from pathlib import Path

ROOT = Path(".")
MAIN = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"

if not MAIN.exists():
    raise SystemExit(f"Arquivo não encontrado: {MAIN}")

text = MAIN.read_text(encoding="utf-8")

# Correção permanente e idempotente:
# dentro de MainActivity, use a Activity como Context.
text = text.replace(
    'val ocrPrefs=context.getSharedPreferences("native_processor_v7",MODE_PRIVATE)',
    'val ocrPrefs=this@MainActivity.getSharedPreferences("native_processor_v7",MODE_PRIVATE)'
)

MAIN.write_text(text, encoding="utf-8")

print("apply_native_current.py concluído.")
