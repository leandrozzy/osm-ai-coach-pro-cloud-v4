from pathlib import Path
ROOT = Path('.')

coach_bridge = '''package com.osmaicoach.collector

import android.content.Context
import android.webkit.JavascriptInterface

class CoachBridge(
    private val activity: MainActivity,
    private val repository: SessionRepository
) {
    private val prefs by lazy {
        activity.getSharedPreferences("coach_native_store", Context.MODE_PRIVATE)
    }

    @JavascriptInterface
    fun startOsmSession(): String {
        activity.runOnUiThread { activity.openOsmFromBridge() }
        return "{\\\"ok\\\":true}"
    }

    @JavascriptInterface
    fun collectorStatus(): String = org.json.JSONObject().apply {
        put("serviceReady", CollectorState.isServiceReady())
        put("recording", CollectorState.isRecording())
        put("lastError", CollectorState.lastError ?: org.json.JSONObject.NULL)
    }.toString()

    @JavascriptInterface
    fun latestSession(): String = repository.latestSessionJson()

    @JavascriptInterface
    fun latestFrameBase64(index: Int): String = repository.latestFrameBase64(index) ?: ""

    @JavascriptInterface
    fun saveCoachState(json: String): Boolean = runCatching {
        require(json.length <= 8_000_000)
        prefs.edit().putString("coach_state_v1", json).commit()
    }.getOrDefault(false)

    @JavascriptInterface
    fun loadCoachState(): String = prefs.getString("coach_state_v1", "") ?: ""

    @JavascriptInterface
    fun saveApiKeys(json: String): Boolean = runCatching {
        require(json.length <= 20_000)
        prefs.edit().putString("api_keys_v1", json).commit()
    }.getOrDefault(false)

    @JavascriptInterface
    fun loadApiKeys(): String = prefs.getString("api_keys_v1", "") ?: ""

    @JavascriptInterface
    fun clearApiKeys(): Boolean = prefs.edit().remove("api_keys_v1").commit()
}
'''
(ROOT/'android-collector/app/src/main/java/com/osmaicoach/collector/CoachBridge.kt').write_text(coach_bridge, encoding='utf-8')

storage = '''const KEY='osm-ai-coach-pro:v1';
const nativeBridge=()=>globalThis.OsmCollector&&typeof globalThis.OsmCollector.loadCoachState==='function'?globalThis.OsmCollector:null;
export const emptySlot=id=>({id,name:`S${id}`,competitionType:'Liga normal',competition:'',myTeam:'',match:{_schemaVersion:3,_sources:{}},squad:{players:[],updatedAt:null},calendar:[],calendarMeta:{},tactics:null,tacticStale:false,director:{plan:null,cash:'NI',notes:'',snapshots:[]},learning:{matches:[],weights:{}},updatedAt:null});
export const defaultState=()=>({version:3,activeSlot:1,settings:{username:'leandrozzy',notifications:false,fallback:true,model:'llama-3.3-70b-versatile',visionModel:'auto',profile:'fast',refereeMap:{Verde:'Agressivo',Azul:'Agressivo',Amarelo:'Normal',Laranja:'Cauteloso',Vermelho:'Cauteloso'}},slots:[1,2,3,4].map(emptySlot)});
export function migrate(s){if(!s||typeof s!=='object'||!Array.isArray(s.slots)||s.slots.length!==4)throw Error('Backup inválido: são necessários quatro slots.');const b=defaultState();if(s.settings?.model?.startsWith('gemini'))s.settings.model=b.settings.model;return {...b,activeSlot:Number.isInteger(s.activeSlot)&&s.activeSlot>=1&&s.activeSlot<=4?s.activeSlot:1,settings:{...b.settings,...s.settings,refereeMap:{...b.settings.refereeMap,...s.settings?.refereeMap}},slots:b.slots.map((base,i)=>{const old=s.slots[i];if(!old||!Array.isArray(old.calendar)||!Array.isArray(old.squad?.players)||!Array.isArray(old.learning?.matches))throw Error('Estrutura de slot inválida.');return {...base,...old,id:i+1,match:{...old.match,_schemaVersion:3},squad:{...base.squad,...old.squad},director:{...base.director,...old.director},learning:{...base.learning,...old.learning}};})};}
export function load(){let raw=localStorage.getItem(KEY);if(!raw){try{raw=nativeBridge()?.loadCoachState?.()||'';}catch{}}if(!raw)return defaultState();try{const state=migrate(JSON.parse(raw));try{localStorage.setItem(KEY,JSON.stringify(state));nativeBridge()?.saveCoachState?.(JSON.stringify(state));}catch{}return state;}catch{throw Error('Os dados salvos não puderam ser lidos. Seus dados não foram apagados.');}}
export function save(state){const json=JSON.stringify(state);localStorage.setItem(KEY,json);try{nativeBridge()?.saveCoachState?.(json);}catch{}}
export function exportState(state){return new Blob([JSON.stringify(state,null,2)],{type:'application/json'});}
'''
(ROOT/'src/storage.js').write_text(storage, encoding='utf-8')

ai_router = '''const KEY='osm-ai-coach-pro:api-keys:v1';
const names=['google','groq','ocrspace','twelvelabs'];
const clean=value=>Object.fromEntries(Object.entries(value||{}).filter(([k,v])=>names.includes(k)&&typeof v==='string'&&v.trim()).map(([k,v])=>[k,v.trim()]));
const bridge=()=>globalThis.OsmCollector&&typeof globalThis.OsmCollector.loadApiKeys==='function'?globalThis.OsmCollector:null;
let keys={};try{keys=clean(JSON.parse(globalThis.localStorage?.getItem(KEY)||'{}'));}catch{}
if(!Object.keys(keys).length){try{const raw=bridge()?.loadApiKeys?.()||'';if(raw){keys=clean(JSON.parse(raw));globalThis.localStorage?.setItem(KEY,JSON.stringify(keys));}}catch{}}
export const setSessionKey=value=>{const next=value&&typeof value==='object'?{...keys,...clean(value)}:{};if(globalThis.localStorage){try{localStorage.setItem(KEY,JSON.stringify(next));}catch{throw Error('Não foi possível salvar as chaves neste dispositivo. Confira o armazenamento do navegador.');}}try{if(Object.keys(next).length)bridge()?.saveApiKeys?.(JSON.stringify(next));else bridge()?.clearApiKeys?.();}catch{}keys=next;};
export const hasSessionKey=()=>Object.values(keys).some(Boolean);
export const sessionProviders=()=>Object.keys(keys).filter(k=>keys[k]);
export const providerKey=name=>keys[name]||'';
export async function apiRequest(path,body={},signal,timeout=25000){const c=new AbortController(),stop=()=>c.abort();if(signal?.aborted)stop();signal?.addEventListener('abort',stop,{once:true});const timer=setTimeout(stop,timeout);try{const r=await fetch('/api/'+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({...body,keys}),signal:c.signal});const data=await r.json().catch(()=>({}));if(!r.ok)throw Error(data.error||('HTTP '+r.status));return data;}catch(e){if(e.name==='AbortError')throw Error('Tempo limite ou análise cancelada.');throw e;}finally{clearTimeout(timer);signal?.removeEventListener('abort',stop);}}
export const askAI=(task,payload,model)=>apiRequest('ai',{task,payload,model},null,27000);
export async function apiStatus(signal){const c=new AbortController(),stop=()=>c.abort(),t=setTimeout(stop,5000);if(signal?.aborted)stop();else signal?.addEventListener('abort',stop,{once:true});try{const r=await fetch('/api/status',{signal:c.signal,cache:'no-store'});return r.ok?await r.json():null;}catch{return null;}finally{clearTimeout(t);signal?.removeEventListener('abort',stop);}}
'''
(ROOT/'src/ai-router.js').write_text(ai_router, encoding='utf-8')

ui_path = ROOT/'src/ui.js'
ui = ui_path.read_text(encoding='utf-8')
needle = 'const liveReadings=new Map();'
block = r'''const liveReadings=new Map();
const NATIVE_SESSION_DONE='osm-ai-coach-pro:native-session-done:v1';
let nativeCollectorBusy=false,nativeCollectorNotice='';
function nativeBridge(){return globalThis.OsmCollector&&typeof globalThis.OsmCollector.latestSession==='function'?globalThis.OsmCollector:null;}
function nativeNormalize(value){return String(value||'').normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
function nativeSameClub(a,b){const x=nativeNormalize(a),y=nativeNormalize(b);return !!x&&!!y&&(x===y||x.includes(y)||y.includes(x));}
function nativeFile(base64,name){const bin=atob(base64),bytes=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);return new File([bytes],name,{type:'image/jpeg',lastModified:Date.now()});}
function nativeRanges(count){if(count<1)return[];const groups=count>=12?4:count>=6?2:1,base=Math.floor(count/groups),extra=count%groups,out=[];let start=0;for(let i=0;i<groups;i++){const size=base+(i<extra?1:0);out.push([start,start+size]);start+=size;}return out;}
function nativeSampleIndices(start,end,max=18){const n=end-start;if(n<=max)return Array.from({length:n},(_,i)=>start+i);return Array.from({length:max},(_,i)=>start+Math.min(n-1,Math.round(i*(n-1)/(max-1))));}
function nativeReadingParsed(type,result){if(type==='match')return {...(result.match||{}),_fieldSources:result._matchFieldSources||result.match?._fieldSources||{}};if(type==='squad')return {players:result.players||[],playerCandidates:result.playerCandidates||[],meta:result.meta||{}};const rows=[...(result.calendar||[])];rows.meta=result.meta||{};return rows;}
function nativeTargetSlot(defaultId,result,type){const team=readingTeam(type,nativeReadingParsed(type,result),result.meta||{});if(known(team)){const hit=getState().slots.find(slot=>known(slotTeam(slot))&&nativeSameClub(slotTeam(slot),team));if(hit)return hit.id;}return defaultId;}
async function nativeLoadFiles(session,start,end){const bridge=nativeBridge();if(!bridge)return[];const files=[];for(const index of nativeSampleIndices(start,end,18)){const b64=bridge.latestFrameBase64(index);if(b64)files.push(nativeFile(b64,'osm-session-'+session.id+'-'+String(index).padStart(3,'0')+'.jpg'));await new Promise(r=>setTimeout(r,0));}return files;}
async function nativeAnalyzeApply(files,type,slotId){if(!files.length)return {ok:false,error:'Sem telas'};const state=getState(),slot=state.slots[slotId-1];const result=await analyzeMedia(files,type,{signal:new AbortController().signal,vision:hasSessionKey(),localRecovery:true,fallback:true,username:state.settings.username,myTeam:slotTeam(slot),rivalName:slot.match.rivalName,competitionType:slot.competitionType,model:state.settings.model,visionModel:state.settings.visionModel,profile:'fast'},message=>{nativeCollectorNotice='Sessão automática · S'+slotId+' · '+type+': '+message;const e=root?.querySelector?.('[data-native-status]');if(e)e.textContent=nativeCollectorNotice;});const targetId=nativeTargetSlot(slotId,result,type),target=getState().slots[targetId-1],parsed=nativeReadingParsed(type,result);const next=applyReading(target,type,parsed,{meta:result.meta||{},teams:result.readingTeams||[],conflicts:result.conflicts||[],username:getState().settings.username,mode:'Sessão automática Android',readingAt:new Date().toISOString()});updateSlot(s=>Object.assign(s,next),targetId);return {ok:true,targetId};}
async function processNativeCollector(){const bridge=nativeBridge();if(!bridge||nativeCollectorBusy||busy)return;let session;try{session=JSON.parse(bridge.latestSession()||'null');}catch{return;}if(!session||session.state!=='ready'||!session.id||!Array.isArray(session.frames)||!session.frames.length)return;if(localStorage.getItem(NATIVE_SESSION_DONE)===session.id)return;nativeCollectorBusy=true;busy=true;nativeCollectorNotice='Processando automaticamente '+session.frames.length+' telas do OSM…';try{if(root)render();const ranges=nativeRanges(session.frames.length),report=[];for(let i=0;i<ranges.length;i++){const [start,end]=ranges[i],files=await nativeLoadFiles(session,start,end);if(!files.length)continue;let targetId=Math.min(i+1,4);try{nativeCollectorNotice='S'+targetId+' · lendo partida…';if(root)render();const match=await nativeAnalyzeApply(files,'match',targetId);if(match.ok)targetId=match.targetId;report.push('S'+targetId+' partida ✓');}catch(error){report.push('S'+targetId+' partida: '+error.message);}try{nativeCollectorNotice='S'+targetId+' · lendo elenco…';if(root)render();await nativeAnalyzeApply(files,'squad',targetId);report.push('S'+targetId+' elenco ✓');}catch(error){report.push('S'+targetId+' elenco: '+error.message);}try{nativeCollectorNotice='S'+targetId+' · lendo calendário…';if(root)render();await nativeAnalyzeApply(files,'calendar',targetId);report.push('S'+targetId+' calendário ✓');}catch(error){report.push('S'+targetId+' calendário: '+error.message);}updateSlot(s=>{try{s.director.plan=buildMarketPlan(s.squad,s.director.cash,s.match.myStrength);}catch{}},targetId);}localStorage.setItem(NATIVE_SESSION_DONE,session.id);nativeCollectorNotice='Sessão OSM processada: '+report.join(' · ');}catch(error){nativeCollectorNotice='Falha ao processar sessão automática: '+error.message;}finally{nativeCollectorBusy=false;busy=false;if(root){render();toast(nativeCollectorNotice);}}}
'''
if needle not in ui: raise SystemExit('Ponto liveReadings não encontrado')
ui = ui.replace(needle, block, 1)
old_today = "return pagehead('Seu dia, organizado.','Uma visão geral do que fazer e do que falta nos quatro slots.','VISÃO GERAL')+notice+renderSlotDashboard"
new_today = "return pagehead('Seu dia, organizado.','Uma visão geral do que fazer e do que falta nos quatro slots.','VISÃO GERAL')+notice+(nativeBridge()?'<p class=\\\"muted\\\" data-native-status>'+esc(nativeCollectorNotice||'Coletor Android conectado. Ao fechar o OSM, a sessão será processada automaticamente.')+'</p>':'')+renderSlotDashboard"
if old_today not in ui: raise SystemExit('today() não encontrado')
ui = ui.replace(old_today, new_today, 1)
old_tail = "window.addEventListener('online',render);window.addEventListener('offline',render);\n render();apiStatus().then(r=>{api=r;const footer=root.querySelector('footer span:last-child');if(footer)footer.textContent=configuredProviders();});\n setInterval(()=>pollNotifications(getState()),15000);"
new_tail = "window.addEventListener('online',render);window.addEventListener('offline',render);\n window.addEventListener('osm-collector-change',()=>{processNativeCollector().catch(error=>{nativeCollectorNotice=error.message;if(!busy)render();});});\n render();apiStatus().then(r=>{api=r;const footer=root.querySelector('footer span:last-child');if(footer)footer.textContent=configuredProviders();});\n setTimeout(()=>processNativeCollector().catch(()=>{}),1400);\n setInterval(()=>pollNotifications(getState()),15000);"
if old_tail not in ui: raise SystemExit('final initApp não encontrado')
ui = ui.replace(old_tail, new_tail, 1)
ui_path.write_text(ui, encoding='utf-8')

gradle = '''plugins {\n    id("com.android.application")\n    id("org.jetbrains.kotlin.android")\n}\n\nandroid {\n    namespace = "com.osmaicoach.collector"\n    compileSdk = 35\n\n    defaultConfig {\n        applicationId = "com.osmaicoach.collector"\n        minSdk = 30\n        targetSdk = 35\n        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1\n        versionCode = 1000 + runNumber\n        versionName = "1.0.$runNumber"\n        buildConfigField("String", "COACH_URL", "\\\"https://osm-ai-coach-pro-cloud-v4-git-android-collector-v1-lro-design.vercel.app\\\"")\n    }\n\n    buildFeatures { buildConfig = true }\n\n    signingConfigs {\n        create("release") {\n            val password = System.getenv("ANDROID_SIGNING_PASSWORD")\n            val path = System.getenv("ANDROID_KEYSTORE_PATH")\n            if (!password.isNullOrBlank() && !path.isNullOrBlank()) {\n                storeFile = file(path)\n                storePassword = password\n                keyAlias = "osmcoach"\n                keyPassword = password\n            }\n        }\n    }\n\n    buildTypes {\n        getByName("release") {\n            signingConfig = signingConfigs.getByName("release")\n            isMinifyEnabled = false\n        }\n    }\n\n    compileOptions {\n        sourceCompatibility = JavaVersion.VERSION_17\n        targetCompatibility = JavaVersion.VERSION_17\n    }\n    kotlinOptions { jvmTarget = "17" }\n}\n'''
(ROOT/'android-collector/app/build.gradle.kts').write_text(gradle, encoding='utf-8')
print('Patch aplicado com sucesso.')
