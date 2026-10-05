from pathlib import Path

R = Path('.')
pkg = R / 'android-collector/app/src/main/java/com/osmaicoach/collector'

# 1) Persist proof that the accessibility service really connected.
svc = pkg / 'OsmCaptureAccessibilityService.kt'
s = svc.read_text(encoding='utf-8')
patterns = [
("""override fun onServiceConnected(){\n        repository=SessionRepository(applicationContext)\n        CollectorState.lastError=null\n        CollectorState.setServiceReady(true)\n    }""",
 """override fun onServiceConnected(){\n        repository=SessionRepository(applicationContext)\n        getSharedPreferences(\"collector_runtime\", MODE_PRIVATE)\n            .edit().putBoolean(\"accessibility_connected\", true).apply()\n        CollectorState.lastError=null\n        CollectorState.setServiceReady(true)\n    }"""),
("""override fun onServiceConnected() {\n        repository = SessionRepository(applicationContext)\n        CollectorState.lastError = null\n        CollectorState.setServiceReady(true)\n    }""",
 """override fun onServiceConnected() {\n        repository = SessionRepository(applicationContext)\n        getSharedPreferences(\"collector_runtime\", MODE_PRIVATE)\n            .edit().putBoolean(\"accessibility_connected\", true).apply()\n        CollectorState.lastError = null\n        CollectorState.setServiceReady(true)\n    }""")
]
changed=False
for old,new in patterns:
    if old in s:
        s=s.replace(old,new,1); changed=True; break
if not changed and 'accessibility_connected' not in s:
    raise SystemExit('onServiceConnected não encontrado')
svc.write_text(s, encoding='utf-8')

# 2) MainActivity: robust accessibility status + professional slot workspace.
main = pkg / 'MainActivity.kt'
m = main.read_text(encoding='utf-8')

start = m.find('    private fun isAccessibilityServiceEnabledRobust():Boolean {')
end = m.find('\n    private fun completion(', start)
if start < 0 or end < 0:
    raise SystemExit('isAccessibilityServiceEnabledRobust não encontrado')
new_check = r'''    private fun isAccessibilityServiceEnabledRobust():Boolean {
        if (CollectorState.isServiceReady()) return true

        val prefs = getSharedPreferences("collector_runtime", Context.MODE_PRIVATE)
        val connectedOnce = prefs.getBoolean("accessibility_connected", false)
        val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expected = ComponentName(this, OsmCaptureAccessibilityService::class.java)

        val enabledByManager = runCatching {
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val si = info.resolveInfo?.serviceInfo ?: return@any false
                    si.packageName == expected.packageName &&
                    (si.name == expected.className || si.name.endsWith(".${OsmCaptureAccessibilityService::class.java.simpleName}"))
                }
        }.getOrDefault(false)

        val secure = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        val enabledBySecure = secure.split(':').any { raw ->
            raw.contains(packageName, true) &&
            raw.contains(OsmCaptureAccessibilityService::class.java.simpleName, true)
        }

        if (enabledByManager || enabledBySecure) {
            prefs.edit().putBoolean("accessibility_connected", true).apply()
            return true
        }

        // Some Android builds keep the switch enabled but do not expose it
        // reliably through Settings.Secure after the app process restarts.
        // If this exact service successfully connected before, keep the UI ON.
        return connectedOnce
    }
'''
m = m[:start] + new_check + m[end:]

start = m.find('    @Composable\n    private fun SlotDetailScreen(')
end = m.find('\n    @Composable\n    private fun DetailSection', start)
if start < 0 or end < 0:
    raise SystemExit('SlotDetailScreen não encontrado')

workspace = r'''    @Composable
    private fun SlotDetailScreen(slot:NativeSlotData, onBack:()->Unit) {
        var section by remember(slot.id) { mutableIntStateOf(0) }
        val tabs = listOf("Resumo","Pré-jogo","Elenco","Calendário","Tática","Diretor","Aprendizado")

        Column(Modifier.fillMaxSize()) {
            Surface(color=Color.White,shadowElevation=2.dp) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal=10.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                        IconButton(onClick=onBack){Icon(Icons.Default.ArrowBack,null)}
                        Column(Modifier.weight(1f)) {
                            Text("S${slot.id} · ${if(slot.team!="NI")slot.team else "Time NI"}",fontSize=22.sp,fontWeight=FontWeight.Bold)
                            Text("${completion(slot)}% preenchido · ${if(slot.competition!="NI")slot.competition else "liga NI"}",fontSize=12.sp,color=Color.Gray)
                        }
                    }
                    androidx.compose.foundation.lazy.LazyRow(
                        contentPadding=PaddingValues(horizontal=14.dp,vertical=8.dp),
                        horizontalArrangement=Arrangement.spacedBy(8.dp)
                    ) {
                        items(tabs.size) { i ->
                            FilterChip(selected=section==i,onClick={section=i},label={Text(tabs[i])})
                        }
                    }
                }
            }

            when(section) {
                0 -> SlotOverview(slot)
                1 -> SlotPreGame(slot)
                2 -> SlotSquad(slot)
                3 -> SlotCalendar(slot)
                4 -> SlotTactics(slot)
                5 -> SlotDirector(slot)
                else -> SlotLearning(slot)
            }
        }
    }

    @Composable
    private fun SlotOverview(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Liga e clube", listOf(
                "Meu time" to slot.team,
                "Competição" to slot.competition,
                "Tipo" to slot.competitionType,
                "Estádio" to slot.stadium,
                "Bônus" to slot.bonus
            ))}
            item { DetailSection("Próxima partida", listOf(
                "Rival" to slot.nextRival,"Data" to slot.matchDate,"Horário" to slot.matchTime,
                "Local" to slot.venue,"Árbitro" to slot.referee,
                "Minha força" to slot.myStrength,"Força rival" to slot.rivalStrength
            ))}
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFFFF4D9)),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Campos ainda NI",fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text(missingFields(slot).ifEmpty{"Nenhum campo principal pendente."})
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotPreGame(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Dados da partida", listOf(
                "Rival" to slot.nextRival,"Data" to slot.matchDate,"Horário" to slot.matchTime,
                "Local" to slot.venue,"Árbitro" to slot.referee,
                "Minha força" to slot.myStrength,"Força rival" to slot.rivalStrength,
                "Meu elenco" to slot.myValue,"Elenco rival" to slot.rivalValue
            ))}
            item { DetailSection("Forças por setor", listOf(
                "Meu GOL" to slot.myGoalkeeper,"Minha DEF" to slot.myDefense,
                "Meu MEI" to slot.myMidfield,"Meu ATA" to slot.myAttack,
                "Rival GOL" to slot.rivalGoalkeeper,"Rival DEF" to slot.rivalDefense,
                "Rival MEI" to slot.rivalMidfield,"Rival ATA" to slot.rivalAttack
            ))}
            item { DetailSection("Scout rival", listOf(
                "Formação" to slot.rivalFormation,"Plano" to slot.rivalPlan,
                "Marcação" to slot.marking,"Impedimento" to slot.offside,
                "Treino secreto" to slot.secretTraining,"Campo de treinamento" to slot.trainingCamp
            ))}
            item {
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Button(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("Gerar tática IA")}
                    OutlinedButton(onClick={},enabled=false,modifier=Modifier.weight(1f)){Text("4-3-3 forte")}
                }
            }
        }
    }

    @Composable
    private fun SlotSquad(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Elenco · ${slot.squadCount} jogadores",fontWeight=FontWeight.Bold,fontSize=18.sp)
                        Spacer(Modifier.height(14.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            MiniMetric("ATA",slot.attackers.toString(),Modifier.weight(1f))
                            MiniMetric("MEI",slot.midfielders.toString(),Modifier.weight(1f))
                            MiniMetric("DEF",slot.defenders.toString(),Modifier.weight(1f))
                            MiniMetric("GOL",slot.goalkeepers.toString(),Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("Treinando: ${slot.trainingCount} • À venda: ${slot.sellingCount}")
                    }
                }
            }
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color(0xFFEFF5E5)),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Jogadores",fontWeight=FontWeight.Bold)
                        Text("A lista individual completa será preenchida pela leitura: nome, posição, idade, força, valor, treino e venda.",color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotCalendar(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Calendário", listOf(
                "Jogos lidos" to slot.calendarCount.toString(),"Próximo rival" to slot.nextRival,
                "Data" to slot.matchDate,"Horário" to slot.matchTime,"Local" to slot.venue
            ))}
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Rodadas, Copa e resultados",fontWeight=FontWeight.Bold)
                        Text("Casa/fora, rodada, adversário, placar e competição aparecerão aqui após o processamento da sessão.",color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotTactics(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Tática completa", listOf(
                "Formação" to "NI","Estilo de jogo" to "NI","Pressão" to "NI",
                "Mentalidade / Estilo" to "NI","Ritmo / Temporização" to "NI",
                "Marcação" to "NI","Impedimento" to "NI","Desarme" to "NI",
                "Ataque" to "NI","Meio" to "NI","Defesa" to "NI"
            ))}
            item { Button(onClick={},enabled=false,modifier=Modifier.fillMaxWidth()){Text("Gerar melhor tática com IA")} }
        }
    }

    @Composable
    private fun SlotDirector(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item { DetailSection("Mercado e evolução", listOf(
                "Mercado visitado" to if(slot.marketSeen)"Sim" else "Não",
                "Treino visitado" to if(slot.trainingSeen)"Sim" else "Não",
                "Jogadores treinando" to slot.trainingCount.toString(),
                "Jogadores à venda" to slot.sellingCount.toString(),
                "Elenco atual" to slot.squadCount.toString()
            ))}
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Plano persistente",fontWeight=FontWeight.Bold)
                        Text("Compras, vendas, treinamento, eventos ativos e projeção de força ficarão salvos aqui por slot.",color=Color.Gray)
                    }
                }
            }
        }
    }

    @Composable
    private fun SlotLearning(slot:NativeSlotData) {
        LazyColumn(contentPadding=PaddingValues(18.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            item {
                Card(colors=CardDefaults.cardColors(containerColor=Color.White),shape=RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Aprendizado compartilhado S1–S4",fontWeight=FontWeight.Bold)
                        Text("Resultados, desempenho da tática, força relativa, humano/CPU, cartões e contexto serão usados para aprender sem apagar o histórico do slot.",color=Color.Gray)
                    }
                }
            }
        }
    }
'''
m = m[:start] + workspace + m[end:]
main.write_text(m, encoding='utf-8')

print('Native Pro v3 applied')
