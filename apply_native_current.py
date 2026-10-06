from pathlib import Path

ROOT = Path(".")
MAIN = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
PROCESSOR = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"

if not MAIN.exists():
    raise SystemExit(f"Arquivo não encontrado: {MAIN}")
if not PROCESSOR.exists():
    raise SystemExit(f"Arquivo não encontrado: {PROCESSOR}")

# 1) Corrige referência de Context na tela de Ajustes.
text = MAIN.read_text(encoding="utf-8")
text = text.replace(
    'val ocrPrefs=context.getSharedPreferences("native_processor_v7",MODE_PRIVATE)',
    'val ocrPrefs=this@MainActivity.getSharedPreferences("native_processor_v7",MODE_PRIVATE)'
)

# 2) Deixa claro nas configurações que as chaves de IA ficam no backend,
# e não precisam ser digitadas no APK.
text = text.replace(
    'DetailLine("Processamento","OCR local + IA Cloud")',
    'DetailLine("Processamento","OCR local + IA Cloud")\n'
    '                                DetailLine("APIs","Configuradas no backend/Vercel")'
)

MAIN.write_text(text, encoding="utf-8")

# 3) Corrige o HTTP 400 "Telas de OCR inválidas".
# /api/analyze exige `region` = "full" ou "report" em cada item de ocrImages.
processor = PROCESSOR.read_text(encoding="utf-8")

old = 'put("url",encoded.first);put("width",encoded.second.first);put("height",encoded.second.second);put("frameIndex",index)'
new = 'put("url",encoded.first);put("width",encoded.second.first);put("height",encoded.second.second);put("frameIndex",index);put("region","full")'

if old not in processor and 'put("region","full")' not in processor:
    raise SystemExit("Não encontrei o bloco de criação das imagens OCR para corrigir.")

processor = processor.replace(old, new)
PROCESSOR.write_text(processor, encoding="utf-8")

print("apply_native_current.py concluído: Context + payload OCR + indicador de APIs corrigidos.")
