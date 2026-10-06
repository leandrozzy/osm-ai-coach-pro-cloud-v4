from pathlib import Path

ROOT = Path(".")
PKG = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector"
MAIN = PKG / "MainActivity.kt"
PROCESSOR = PKG / "NativeSessionProcessor.kt"
REPO = PKG / "SessionRepository.kt"
LOCAL = PKG / "LocalOcrExtractor.kt"

for f in (MAIN, PROCESSOR, REPO, LOCAL):
    if not f.exists():
        raise SystemExit(f"Arquivo não encontrado: {f}")

# 1) MainActivity
text = MAIN.read_text(encoding="utf-8")
text = text.replace(
    'val ocrPrefs=context.getSharedPreferences("native_processor_v7",MODE_PRIVATE)',
    'val ocrPrefs=this@MainActivity.getSharedPreferences("native_processor_v7",MODE_PRIVATE)'
)
if 'DetailLine("APIs","Configuradas no backend/Vercel")' not in text:
    text = text.replace(
        'DetailLine("Processamento","OCR local + IA Cloud")',
        'DetailLine("Processamento","OCR local + IA Cloud")\n'
        '                                DetailLine("APIs","Configuradas no backend/Vercel")'
    )
MAIN.write_text(text, encoding="utf-8")

# 2) SessionRepository: reclassificar frames após OCR
repo = REPO.read_text(encoding="utf-8")
if 'fun updateLatestFrameClassification' not in repo:
    marker = '    fun latestFrameBase64(index:Int):String?{\n'
    method = '''    @Synchronized
    fun updateLatestFrameClassification(classifications: Map<Int, ScreenClassifier.Result>) {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE)
            .getString("latest_session_id",null) ?: return
        val file=File(File(root,id),"session.json")
        if(!file.exists()) return
        runCatching {
            val json=JSONObject(file.readText())
            val frames=json.optJSONArray("frames") ?: return@runCatching
            for(i in 0 until frames.length()){
                val frame=frames.getJSONObject(i)
                val index=frame.optInt("index",i)
                val c=classifications[index] ?: continue
                frame.put("screenType",c.type)
                frame.put("screenTitle",c.title.take(80))
            }
            file.writeText(json.toString(2))
        }
    }

'''
    if marker not in repo:
        raise SystemExit("Não encontrei ponto para adicionar reclassificação no SessionRepository.")
    repo = repo.replace(marker, method + marker, 1)
REPO.write_text(repo, encoding="utf-8")

# 3) Local OCR: árbitro só aceita valores plausíveis
local = LOCAL.read_text(encoding="utf-8")
old_ref = '        extractLabeled(lines, listOf("árbitro","arbitro","referee"))?.let { if (slot.referee=="NI") slot.referee=it }\n'
new_ref = '''        extractLabeled(lines, listOf("árbitro","arbitro","referee"))?.let { candidate ->
            val r = normalize(candidate)
            val valid = listOf("verde","azul","amarelo","laranja","vermelho","green","blue","yellow","orange","red",
                "muito rigoroso","rigoroso","medio","médio","tolerante","leniente").any { r.contains(normalize(it)) }
            if (slot.referee=="NI" && valid) slot.referee=candidate
        }
'''
if old_ref in local:
    local = local.replace(old_ref, new_ref, 1)
LOCAL.write_text(local, encoding="utf-8")

# 4) Processor
p = PROCESSOR.read_text(encoding="utf-8")

old_img = 'put("url",encoded.first);put("width",encoded.second.first);put("height",encoded.second.second);put("frameIndex",index)'
new_img = 'put("url",encoded.first);put("width",encoded.second.first);put("height",encoded.second.second);put("frameIndex",index);put("region","full")'
if old_img in p:
    p = p.replace(old_img, new_img)

needle = '''        segments.forEachIndexed{slotIndex,indices->
            val slot=slots[slotIndex.coerceIn(0,3)]
            val texts=indices.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            slot.lastUpdated=System.currentTimeMillis()
        }
        store.saveAll(slots)
'''
replacement = '''        val classifications = ocrByIndex.mapValues { (_, text) -> ScreenClassifier.classify(text) }
        repository.updateLatestFrameClassification(classifications)

        segments.forEachIndexed{slotIndex,indices->
            val slot=slots[slotIndex.coerceIn(0,3)]
            val relevant=indices.filter { idx ->
                when(classifications[idx]?.type) {
                    "match","squad","calendar","club","training","market","tactics","result" -> true
                    else -> false
                }
            }
            val source=if(relevant.isNotEmpty())relevant else indices
            val texts=source.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}
            local.applyToSlot(slot,texts)
            slot.lastUpdated=System.currentTimeMillis()
        }
        store.saveAll(slots)
'''
if needle not in p:
    raise SystemExit("Não encontrei o bloco de aplicação local do Processor.")
p = p.replace(needle, replacement, 1)

old_jobs = '''        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            jobs+=Triple(i,"match",rows)
            jobs+=Triple(i,"squad",rows)
            jobs+=Triple(i,"calendar",rows)
        }
'''
new_jobs = '''        fun rowsForType(rows:List<Int>, type:String):List<Int>{
            val direct=rows.filter { classifications[it]?.type==type }
            if(direct.isNotEmpty()) return direct
            val fallbackTypes=when(type){
                "match" -> setOf("club","tactics","result")
                "squad" -> setOf("training","market")
                "calendar" -> setOf("ranking")
                else -> emptySet()
            }
            val related=rows.filter { classifications[it]?.type in fallbackTypes }
            return if(related.isNotEmpty()) related else rows
        }

        val jobs=mutableListOf<Triple<Int,String,List<Int>>>()
        segments.forEachIndexed{i,rows->
            jobs+=Triple(i,"match",rowsForType(rows,"match"))
            jobs+=Triple(i,"squad",rowsForType(rows,"squad"))
            jobs+=Triple(i,"calendar",rowsForType(rows,"calendar"))
        }
'''
if old_jobs not in p:
    raise SystemExit("Não encontrei o bloco de jobs do Processor.")
p = p.replace(old_jobs, new_jobs, 1)

old_apply = '''            }else{
                runCatching{applyResult(slot,result.first,result.second)}
                    .onSuccess{success++}
                    .onFailure{failed++;lastError="S${slot.id} $friendly: ${it.message?:"falha ao aplicar"}"}
            }
'''
new_apply = '''            }else{
                val before=knownCount(slot)
                runCatching{applyResult(slot,result.first,result.second)}
                    .onSuccess{
                        val after=knownCount(slot)
                        if(after>before){
                            success++
                        }else{
                            failed++
                            lastError="S${slot.id} $friendly: resposta recebida, mas sem dados úteis para este slot"
                        }
                    }
                    .onFailure{failed++;lastError="S${slot.id} $friendly: ${it.message?:"falha ao aplicar"}"}
            }
'''
if old_apply not in p:
    raise SystemExit("Não encontrei o contador de sucesso do Processor.")
p = p.replace(old_apply, new_apply, 1)

if 'private fun knownCount(slot:NativeSlotData)' not in p:
    marker = '    private fun sample(rows:List<Int>,max:Int):List<Int>{\n'
    helper = '''    private fun knownCount(slot:NativeSlotData):Int {
        val values=listOf(
            slot.team,slot.competition,slot.nextRival,slot.matchDate,slot.matchTime,slot.venue,slot.referee,
            slot.myStrength,slot.rivalStrength,slot.myValue,slot.rivalValue,
            slot.myGoalkeeper,slot.myDefense,slot.myMidfield,slot.myAttack,
            slot.rivalGoalkeeper,slot.rivalDefense,slot.rivalMidfield,slot.rivalAttack,
            slot.rivalFormation,slot.rivalPlan,slot.marking,slot.offside,
            slot.secretTraining,slot.trainingCamp,slot.stadium,slot.bonus
        )
        return values.count { it.isNotBlank() && it!="NI" && it!="null" } +
            (if(slot.squadCount>0)1 else 0) +
            (if(slot.calendarCount>0)1 else 0)
    }

'''
    if marker not in p:
        raise SystemExit("Não encontrei ponto para adicionar knownCount.")
    p = p.replace(marker, helper + marker, 1)

PROCESSOR.write_text(p, encoding="utf-8")

print("apply_native_current.py concluído.")
print("Melhorias: classificação por OCR, seleção correta de telas por tipo, Sessions reclassificada e sucesso somente com dados úteis.")
