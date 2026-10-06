from pathlib import Path
import re

ROOT = Path(__file__).resolve().parent

def load(rel):
    p = ROOT / rel
    if not p.exists():
        raise SystemExit(f"Arquivo ausente: {rel}")
    return p, p.read_text(encoding="utf-8")

def save(p, old, new):
    if old != new:
        p.write_text(new, encoding="utf-8")
        print("PATCHED:", p.relative_to(ROOT))
        return 1
    print("UNCHANGED:", p.relative_to(ROOT))
    return 0

changed = 0

# PROCESSOR
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"
p, s = load(rel)
old = s

s = s.replace(
    'val candidates=listOf(BuildConfig.BACKEND_FALLBACK_URL,BuildConfig.BACKEND_URL)',
    'val candidates=listOf(BuildConfig.BACKEND_URL,BuildConfig.BACKEND_FALLBACK_URL)'
)
s = s.replace('ocrText=text,', 'ocrText=text.take(500),')
s = s.replace('OSM-AI-Coach-Native/13', 'OSM-AI-Coach-Native/18')
changed += save(p, old, s)

# SESSION REPOSITORY
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/SessionRepository.kt"
p, s = load(rel)
old = s

s = s.replace('frame.put("ocrText",u.ocrText.take(2500))',
              'frame.put("ocrText",u.ocrText.take(500))')
s = s.replace('frame.put("ocrText",u.ocrText.take(12000))',
              'frame.put("ocrText",u.ocrText.take(500))')
s = s.replace('file.writeText(json.toString(2))',
              'file.writeText(json.toString())')
s = s.replace('File(sessionDir(session),"session.json").writeText(json.toString(2))',
              'File(sessionDir(session),"session.json").writeText(json.toString())')

block1 = '''            file.writeText(json.toString())
        }
    }

    @Synchronized
    fun markFrames'''
block1_new = '''            file.writeText(json.toString())
            context.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE).edit()
                .remove("session_metadata_error")
                .apply()
        }.onFailure {
            context.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE).edit()
                .putString("session_metadata_error", "updateFrameAnalysis: " + (it.message ?: it.javaClass.simpleName))
                .apply()
        }
    }

    @Synchronized
    fun markFrames'''
if block1 in s:
    s = s.replace(block1, block1_new, 1)

block2 = '''            file.writeText(json.toString())
        }
    }

    fun latestFrameBase64'''
block2_new = '''            file.writeText(json.toString())
        }.onFailure {
            context.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE).edit()
                .putString("session_metadata_error", "markFrames: " + (it.message ?: it.javaClass.simpleName))
                .apply()
        }
    }

    fun latestFrameBase64'''
if block2 in s:
    s = s.replace(block2, block2_new, 1)

changed += save(p, old, s)

# CAPTURE
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt"
p, s = load(rel)
old = s
s = s.replace('textHint=textHint.take(7000)', 'textHint=textHint.take(1800)')
s = s.replace('>= 8000L', '>= 12000L')
s = s.replace('1500L - (System.currentTimeMillis() - lastCaptureAt)',
              '1900L - (System.currentTimeMillis() - lastCaptureAt)')
changed += save(p, old, s)

# UI
rel = "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
p, s = load(rel)
old = s
s = s.replace('DetailLine("Versão nativa","V17 · ${BuildConfig.VERSION_NAME}")',
              'DetailLine("Versão nativa","V18 · ${BuildConfig.VERSION_NAME}")')

old_err = 'DetailLine("Último erro",processing.lastError.ifBlank{"Nenhum"})'
new_err = '''DetailLine(
                        "Último erro",
                        processing.lastError.ifBlank {
                            getSharedPreferences("collector_runtime",MODE_PRIVATE)
                                .getString("session_metadata_error","Nenhum") ?: "Nenhum"
                        }
                    )'''
s = s.replace(old_err, new_err)
changed += save(p, old, s)

# VERSION
rel = "android-collector/app/build.gradle.kts"
p, s = load(rel)
old = s
s = re.sub(r'versionName\s*=\s*"2\.[0-9]+\.\$runNumber"',
           'versionName = "2.6.$runNumber"', s)
changed += save(p, old, s)

print()
print(f"V18 aplicada. Arquivos alterados: {changed}")
print("Esperado:")
print("- Ajustes e Sessões mostram o mesmo OCR.")
print("- Sessões deixa de mostrar todas as telas como não atribuídas.")
print("- Backend de produção usado primeiro.")
print("- session.json menor e mais estável.")
