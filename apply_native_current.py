from pathlib import Path

ROOT = Path(".")
PROC = ROOT / "android-collector/app/src/main/java/com/osmaicoach/collector/NativeSessionProcessor.kt"
GRADLE = ROOT / "android-collector/app/build.gradle.kts"
ANALYZE = ROOT / "api/analyze.js"
PROVIDERS = ROOT / "lib/providers.js"

for f in (PROC, GRADLE, ANALYZE, PROVIDERS):
    if not f.exists():
        raise SystemExit(f"Arquivo não encontrado: {f}")

def replace_if_needed(path, old, new):
    text = path.read_text(encoding="utf-8")
    if new in text:
        return False
    if old not in text:
        raise SystemExit(f"Trecho esperado não encontrado em {path}: {old[:120]}")
    path.write_text(text.replace(old, new, 1), encoding="utf-8")
    return True

# Força novo processamento depois da instalação.
p = PROC.read_text(encoding="utf-8")
for old_version in ("native_processor_v9", "native_processor_v10"):
    if old_version in p:
        p = p.replace(old_version, "native_processor_v11", 1)
        break
PROC.write_text(p, encoding="utf-8")

# Android envia o OCR local reconhecido para o backend.
replace_if_needed(
    PROC,
    'val result=withTimeoutOrNull(65000L){analyzeType(endpoint,session,job.third,job.second,maxImages,slot)}',
    'val nativeOcrText=job.third.mapNotNull{ocrByIndex[it]}.filter{it.isNotBlank()}.joinToString("\\n\\n").take(30000)\n            val result=withTimeoutOrNull(65000L){analyzeType(endpoint,session,job.third,job.second,maxImages,slot,nativeOcrText)}'
)

replace_if_needed(
    PROC,
    'endpointBase:String,session:CaptureSession,indices:List<Int>,type:String,maxImages:Int,slot:NativeSlotData\n    ):Pair<String,JSONObject>?=withContext(Dispatchers.IO){',
    'endpointBase:String,session:CaptureSession,indices:List<Int>,type:String,maxImages:Int,slot:NativeSlotData,nativeOcrText:String\n    ):Pair<String,JSONObject>?=withContext(Dispatchers.IO){'
)

replace_if_needed(
    PROC,
    'put("useText",true)\n            put("context",JSONObject().apply{',
    'put("useText",true)\n            put("nativeOcrText",nativeOcrText)\n            put("context",JSONObject().apply{'
)

# Produção vira backend principal para o APK.
g = GRADLE.read_text(encoding="utf-8")
g = g.replace(
    'buildConfigField("String", "BACKEND_URL", "\\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\\"")',
    'buildConfigField("String", "BACKEND_URL", "\\"https://osm-ai-coach-pro-cloud-v4.vercel.app\\"")'
)
if 'buildConfigField("String", "BACKEND_FALLBACK_URL", "\\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\\"")' not in g:
    g = g.replace(
        'buildConfigField("String", "BACKEND_FALLBACK_URL", "\\"https://osm-ai-coach-pro-cloud-v4.vercel.app\\"")',
        'buildConfigField("String", "BACKEND_FALLBACK_URL", "\\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\\"")'
    )
GRADLE.write_text(g, encoding="utf-8")

# Gemini passa a aceitar texto OCR local.
replace_if_needed(
    PROVIDERS,
    "export async function googleRead({key,type,images=[],context={},task='extract',model,budgetMs=24000}){",
    "export async function googleRead({key,type,images=[],text='',context={},task='extract',model,budgetMs=24000}){"
)

replace_if_needed(
    PROVIDERS,
    " const prompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context);\n const parts=[{text:prompt},...images.map(image=>({inlineData:{mimeType:image.slice(5,image.indexOf(';')),data:image.split(',')[1]}}))];",
    " const basePrompt=task==='tactic'?tacticPrompt(context):extractionPrompt(type,context);\n const prompt=text?basePrompt+'\\n\\nOCR LOCAL DO ANDROID (use como evidência textual, sem inventar campos):\\n'+text:basePrompt;\n const parts=[{text:prompt},...images.map(image=>({inlineData:{mimeType:image.slice(5,image.indexOf(';')),data:image.split(',')[1]}}))];"
)

# Backend usa nativeOcrText como fallback real.
a = ANALYZE.read_text(encoding="utf-8")
if "nativeOcrText" not in a:
    needle = " const usefulOcr=ocrResults.filter(r=>r.text?.trim().length>=40);"
    insert = ''' const nativeOcrText=typeof body.nativeOcrText==='string'?body.nativeOcrText.trim().slice(0,30000):'';
 if(nativeOcrText&&coverage(type,output).percent<70&&Date.now()-started<30000){
  const textReaders=[];
  if(google)textReaders.push(['google',()=>googleRead({key:google,type,text:nativeOcrText,images:[],context,model:body.models?.google,budgetMs:Math.max(1500,30000-(Date.now()-started))})]);
  if(groq)textReaders.push(['groq',()=>groqRead({key:groq,type,text:nativeOcrText,images:[],context,model:body.models?.groqText,budgetMs:Math.max(1500,30000-(Date.now()-started))})]);
  for(const [provider,reader] of textReaders){
   if(Date.now()-started>=30000)break;
   attempts.push(provider+'-native-ocr');
   try{
    const interpreted=checkedRead(await reader(),'native-ocr');
    output=fuseExtraction(output,interpreted);
    if(usefulRead(type,output)){preferredProvider=preferredProvider||provider;break;}
   }catch(e){failures.push({...errorInfo(provider,e),stage:'native-ocr'});}
  }
 }
 const usefulOcr=ocrResults.filter(r=>r.text?.trim().length>=40);'''
    if needle not in a:
        raise SystemExit("Trecho de inserção não encontrado em api/analyze.js")
    a = a.replace(needle, insert, 1)
    ANALYZE.write_text(a, encoding="utf-8")

print("FIX V11 APLICADO")
print("- Android envia OCR local")
print("- Backend interpreta nativeOcrText")
print("- APK usa produção como backend principal")
