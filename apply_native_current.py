from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"

if not MAIN.exists():
    raise SystemExit(f"Arquivo não encontrado: {MAIN}")

text = MAIN.read_text(encoding="utf-8")
changed = 0

def patch(old: str, new: str, marker: str):
    global text, changed
    if marker in text:
        print(f"OK: já aplicado -> {marker}")
        return
    if old not in text:
        print(f"AVISO: trecho não encontrado -> {marker}. Mantendo o restante da build.")
        return
    text = text.replace(old, new, 1)
    changed += 1
    print(f"OK: aplicado -> {marker}")

patch(
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val serviceReady = servicePermission
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"''',
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        // V24: o controle da sessão não pode desaparecer só porque o Android/ASUS
        // derrubou momentaneamente a conexão do AccessibilityService.
        val serviceReady = servicePermission || serviceConnected
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"''',
"V24: o controle da sessão"
)

patch(
'''                        if (!serviceReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text(if(servicePermission) "Reconectar leitura" else "Ativar leitura automática")
                            }
                        }''',
'''                        if (recording) {
                            Spacer(Modifier.height(10.dp))
                            Button(
                                onClick = {
                                    finishCaptureSession()
                                    forceProcessToken++
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFFC857),
                                    contentColor = Color(0xFF122630)
                                )
                            ) {
                                Icon(Icons.Default.StopCircle, null)
                                Spacer(Modifier.width(8.dp))
                                Text("ENCERRAR CAPTURA E ATUALIZAR", fontWeight = FontWeight.Bold)
                            }

                            if (!serviceReady) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "A sessão continua preservada, mas a leitura automática do Android desconectou. Você pode encerrar sem perder a captura ou reativar a leitura.",
                                    color = Color(0xFFFFD6A0),
                                    fontSize = 11.sp
                                )
                                Spacer(Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("Reativar leitura do Android", color = Color.White)
                                }
                            }
                        } else if (!serviceReady) {
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) {
                                Text(if(servicePermission) "Reconectar leitura" else "Ativar leitura automática")
                            }
                        }''',
"ENCERRAR CAPTURA E ATUALIZAR"
)

patch(
'''                            Button(
                                onClick=openOsm,
                                enabled=serviceReady&&!processing.running,
                                modifier=Modifier.fillMaxWidth().height(52.dp),
                                colors=ButtonDefaults.buttonColors(containerColor=Color(0xFFB8E34D),contentColor=Color(0xFF122630))
                            ){
                                Icon(Icons.Default.PlayArrow,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Voltar ao OSM",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(9.dp))
                            OutlinedButton(
                                onClick=stopCapture,
                                enabled=!processing.running,
                                modifier=Modifier.fillMaxWidth().height(48.dp)
                            ){
                                Icon(Icons.Default.StopCircle,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Encerrar captura e atualizar",fontWeight=FontWeight.Bold)
                            }''',
'''                            Button(
                                onClick=openOsm,
                                enabled=true,
                                modifier=Modifier.fillMaxWidth().height(52.dp),
                                colors=ButtonDefaults.buttonColors(
                                    containerColor=Color(0xFFB8E34D),
                                    contentColor=Color(0xFF122630)
                                )
                            ){
                                Icon(Icons.Default.PlayArrow,null)
                                Spacer(Modifier.width(8.dp))
                                Text("Voltar ao OSM",fontWeight=FontWeight.Bold)
                            }
                            Spacer(Modifier.height(9.dp))
                            Button(
                                onClick=stopCapture,
                                enabled=true,
                                modifier=Modifier.fillMaxWidth().height(52.dp),
                                colors=ButtonDefaults.buttonColors(
                                    containerColor=Color(0xFFFFC857),
                                    contentColor=Color(0xFF122630),
                                    disabledContainerColor=Color(0xFFFFC857),
                                    disabledContentColor=Color(0xFF122630)
                                )
                            ){
                                Icon(Icons.Default.StopCircle,null)
                                Spacer(Modifier.width(8.dp))
                                Text("ENCERRAR CAPTURA E ATUALIZAR",fontWeight=FontWeight.Bold)
                            }''',
"disabledContainerColor=Color(0xFFFFC857)"
)

patch(
'''                                onClick=openOsm,
                                enabled=serviceReady&&!processing.running,''',
'''                                onClick=openOsm,
                                enabled=serviceReady,''',
"enabled=serviceReady,"
)

patch(
'''                    DetailLine("Versão nativa","V23.2 · ${BuildConfig.VERSION_NAME}")''',
'''                    DetailLine("Versão nativa","V24.0 · ${BuildConfig.VERSION_NAME}")''',
'DetailLine("Versão nativa","V24.0'
)

MAIN.write_text(text, encoding="utf-8")
print(f"Concluído. Alterações aplicadas: {changed}")
print("V24: botão ENCERRAR sempre visível e clicável durante sessão; voltar ao OSM não depende do processamento; sessão não fica sem controle quando o serviço oscila.")
