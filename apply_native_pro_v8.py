from pathlib import Path

R = Path(".")
processor = R / "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"
p = processor.read_text(encoding="utf-8")

old = '''            val file=repository.frameFile(session,frame.index)
            val text=if(file!=null) withTimeoutOrNull(12000L){local.read(file)} ?: "" else ""
            ocrByIndex[frame.index]=text
            current++
            onProgress(Progress(true,current,total,"OCR local · tela ${frame.index+1}/${session.frames.size}",success,failed,"ocr",lastError))
'''
new = '''            val file=repository.frameFile(session,frame.index)
            val visualText=if(file!=null) withTimeoutOrNull(12000L){local.read(file)} ?: "" else ""
            val text=listOf(frame.textHint,visualText).filter{it.isNotBlank()}.distinct().joinToString("\\n")
            ocrByIndex[frame.index]=text
            current++
            val readable=ocrByIndex.values.count{it.isNotBlank()}
            prefs.edit().putInt("local_ocr_readable",readable).putInt("local_ocr_total",session.frames.size).apply()
            onProgress(Progress(true,current,total,"OCR local · tela ${frame.index+1}/${session.frames.size} · $readable com texto",success,failed,"ocr",lastError))
'''
if old not in p:
    raise SystemExit("Bloco OCR da v7 não encontrado.")
p = p.replace(old,new,1)

p = p.replace(
    'val result=withTimeoutOrNull(30000L){analyzeType(endpoint,session,job.third,job.second,2,slot)}',
    'val result=withTimeoutOrNull(60000L){analyzeType(endpoint,session,job.third,job.second,2,slot)}'
)
p = p.replace(
    'requestMethod="POST";connectTimeout=10000;readTimeout=22000;doOutput=true;instanceFollowRedirects=true',
    'requestMethod="POST";connectTimeout=10000;readTimeout=52000;doOutput=true;instanceFollowRedirects=true'
)

if 'private var cloudError:String=""' not in p:
    p = p.replace(
        '    private val local=LocalOcrExtractor()\n',
        '    private val local=LocalOcrExtractor()\n    @Volatile private var cloudError:String=""\n'
    )

p = p.replace(
'''            if(result==null){
                failed++
                lastError="S${slot.id} $friendly: IA Cloud sem resposta"
            }else{''',
'''            if(result==null){
                failed++
                val detail=cloudError.ifBlank{"sem resposta dentro do limite"}
                lastError="S${slot.id} $friendly: $detail"
            }else{'''
)

old_return = '''            if(code !in 200..299||raw.isBlank())null
            else{
                val root=JSONObject(raw)
                val data=root.optJSONObject("data")?:return@withContext null
                type to data
            }
        }catch(_:Throwable){null}finally{conn.disconnect()}
'''
new_return = '''            if(code !in 200..299){
                cloudError="HTTP $code: "+raw.take(180).replace("\\n"," ")
                null
            }else if(raw.isBlank()){
                cloudError="HTTP $code sem conteúdo"
                null
            }else{
                val root=JSONObject(raw)
                val data=root.optJSONObject("data")
                if(data==null){
                    cloudError="Resposta sem campo data: "+raw.take(180).replace("\\n"," ")
                    null
                }else{
                    cloudError=""
                    type to data
                }
            }
        }catch(e:Throwable){
            cloudError=(e.javaClass.simpleName+": "+(e.message?:"erro de rede")).take(220)
            null
        }finally{conn.disconnect()}
'''
if old_return not in p:
    raise SystemExit("Bloco de retorno cloud da v7 não encontrado.")
p = p.replace(old_return,new_return,1)

old_final = '        onProgress(Progress(false,total,total,"Sessão finalizada",success,failed,"done",lastError))'
new_final = '''        val readable=prefs.getInt("local_ocr_readable",0)
        onProgress(Progress(false,total,total,"Sessão finalizada · OCR $readable/${session.frames.size}",success,failed,"done",lastError))'''
if old_final in p:
    p = p.replace(old_final,new_final,1)

processor.write_text(p, encoding="utf-8")

main = R / "android-collector/app/src/main/java/com/osmaicoach/collector/MainActivity.kt"
m = main.read_text(encoding="utf-8")
anchor = 'DetailLine("Processamento","OCR local + IA Cloud")'
if anchor in m and 'Telas com texto OCR' not in m:
    replacement = anchor + '''
                                val ocrPrefs=context.getSharedPreferences("native_processor_v7",MODE_PRIVATE)
                                DetailLine("Telas com texto OCR","${ocrPrefs.getInt("local_ocr_readable",0)}/${ocrPrefs.getInt("local_ocr_total",0)}")'''
    m = m.replace(anchor,replacement,1)
main.write_text(m,encoding="utf-8")
print("Native Pro v8 applied.")
