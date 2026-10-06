from pathlib import Path

ROOT = Path(__file__).resolve().parent
MAIN = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"

if not MAIN.exists():
    raise SystemExit(f"ERRO: {MAIN} não encontrado")

text = MAIN.read_text(encoding="utf-8")

def replace_once(old: str, new: str, name: str):
    global text
    count = text.count(old)
    if count == 0:
        if new in text:
            print(f"OK já aplicado: {name}")
            return
        raise SystemExit(f"ERRO: trecho não encontrado para {name}")
    if count != 1:
        raise SystemExit(f"ERRO: {name} apareceu {count} vezes; patch abortado para não corromper o app")
    text = text.replace(old, new, 1)
    print(f"OK: {name}")

replace_once(
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val serviceReady = servicePermission
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"''',
'''        val servicePermission = isAccessibilityServiceEnabledInSettings()
        val serviceConnected = isAccessibilityServiceActuallyConnected()
        val recording = CollectorState.isRecording() || repo.current()?.state == "recording"
        // V24: durante uma sessão o usuário nunca pode perder o controle da captura.
        // A permissão pode continuar marcada enquanto o AccessibilityService oscila.
        val serviceReady = servicePermission || serviceConnected''',
"estado da sessão independente do serviço"
)

replace_once(
'''                            Surface(shape = RoundedCornerShape(999.dp), color = if (serviceReady) Color(0xFF20372A) else Color(0xFF3A2D20)) {
                                Text(
                                    if (serviceReady) "LEITURA ON" else "LEITURA OFF",
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (serviceReady) Color(0xFFB8E34D) else Color(0xFFFFC857),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }''',
'''                            Surface(
                                shape = RoundedCornerShape(999.dp),
                                color = when {
                                    recording -> Color(0xFF4A3A1F)
                                    serviceReady -> Color(0xFF20372A)
                                    else -> Color(0xFF3A2D20)
                                }
                            ) {
                                Text(
                                    when {
                                        recording -> "SESSÃO ATIVA"
                                        serviceReady -> "LEITURA ON"
                                        else -> "LEITURA OFF"
                                    },
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = when {
                                        recording -> Color(0xFFFFC857)
                                        serviceReady -> Color(0xFFB8E34D)
                                        else -> Color(0xFFFFC857)
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }''',
"indicador de sessão no cabeçalho"
)

replace_once(
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

                            if (!serviceConnected) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "A sessão está preservada, mas o serviço de leitura do Android desconectou. Você ainda pode encerrar a captura sem perder o que já foi salvo.",
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
"botão global de encerrar sessão"
)

replace_once(
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
                                    contentColor=Color(0xFF122630)
                                )
                            ){
                                Icon(Icons.Default.StopCircle,null)
                                Spacer(Modifier.width(8.dp))
                                Text("ENCERRAR CAPTURA E ATUALIZAR",fontWeight=FontWeight.Bold)
                            }''',
"botões da tela Hoje sempre utilizáveis"
)

replace_once(
'''                                onClick=openOsm,
                                enabled=serviceReady&&!processing.running,''',
'''                                onClick=openOsm,
                                enabled=serviceReady,''',
"abrir OSM não bloqueado pelo processamento"
)

replace_once(
'''                    DetailLine("Versão nativa","V23.2 · ${BuildConfig.VERSION_NAME}")''',
'''                    DetailLine("Versão nativa","V24.0 · ${BuildConfig.VERSION_NAME}")''',
"versão V24.0"
)

MAIN.write_text(text, encoding="utf-8")

checks = [
    'Text("ENCERRAR CAPTURA E ATUALIZAR"',
    'recording -> "SESSÃO ATIVA"',
    'DetailLine("Versão nativa","V24.0',
    'enabled=true'
]
for check in checks:
    if check not in text:
        raise SystemExit(f"ERRO DE VALIDAÇÃO: {check}")

print("PATCH V24 APLICADO COM SUCESSO")
