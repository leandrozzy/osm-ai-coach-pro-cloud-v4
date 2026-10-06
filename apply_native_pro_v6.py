from pathlib import Path

R = Path(".")
main = R / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
m = main.read_text(encoding="utf-8")

old = """        LaunchedEffect(refresh, forceProcessToken) {
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

        val serviceReady = isAccessibilityServiceEnabledRobust()
"""
new = """        // Do not key processing by refresh.
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

        val serviceReady = isAccessibilityServiceEnabledRobust()
"""
if old not in m:
    raise SystemExit("Bloco LaunchedEffect da v5 não encontrado; confirme que a v5 foi aplicada.")
m = m.replace(old, new, 1)

needle = 'Text(processing.label,fontSize=12.sp,color=Color.Gray)'
replacement = """Text(processing.label,fontSize=12.sp,color=Color.Gray)
                                    if(processing.running) {
                                        Spacer(Modifier.height(4.dp))
                                        Text("Processamento ativo — a tela pode atualizar sem reiniciar a análise.",fontSize=10.sp,color=Color(0xFF6B7B72))
                                    }"""
if needle in m:
    m = m.replace(needle, replacement, 1)

main.write_text(m, encoding="utf-8")
print("Native Pro v6 applied: fixed coroutine cancellation loop.")
