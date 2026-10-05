from pathlib import Path

ROOT = Path('.')

(ROOT/'android-collector/app/src/main/java/com/osmaicoach/collector/SessionModels.kt').write_text('''package com.osmaicoach.collector

data class CaptureFrame(
    val index: Int,
    val fileName: String,
    val capturedAt: Long,
    val sourcePackage: String = OSM_PACKAGE,
    val width: Int,
    val height: Int,
    val fingerprint: Long,
    val textHint: String = ""
)

data class CaptureSession(
    val id: String,
    val startedAt: Long,
    var endedAt: Long? = null,
    val frames: MutableList<CaptureFrame> = mutableListOf(),
    var state: String = "recording"
)

const val OSM_PACKAGE = "com.gamebasics.osm"
''', encoding='utf-8')

(ROOT/'android-collector/app/src/main/java/com/osmaicoach/collector/SessionRepository.kt').write_text('''package com.osmaicoach.collector

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class SessionRepository(private val context: Context) {
    private val root = File(context.filesDir, "osm_sessions").apply { mkdirs() }
    private var active: CaptureSession? = null

    @Synchronized fun startIfNeeded(): CaptureSession {
        active?.let { return it }
        val now = System.currentTimeMillis()
        val id = "osm-${now}-${UUID.randomUUID().toString().take(8)}"
        return CaptureSession(id=id, startedAt=now).also { active=it; sessionDir(it).mkdirs(); persist(it) }
    }

    @Synchronized fun current(): CaptureSession? = active

    @Synchronized fun saveFrame(bitmap: Bitmap, fingerprint: Long, textHint: String = ""): CaptureFrame {
        val session=startIfNeeded(); val index=session.frames.size
        val name="frame-${index.toString().padStart(4,'0')}.jpg"; val file=File(sessionDir(session),name)
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG,88,out) }
        val frame=CaptureFrame(index,name,System.currentTimeMillis(),OSM_PACKAGE,bitmap.width,bitmap.height,fingerprint,textHint.take(6000))
        session.frames += frame; persist(session); return frame
    }

    @Synchronized fun finish(): CaptureSession? {
        val session=active ?: return null
        session.endedAt=System.currentTimeMillis(); session.state="ready"; persist(session); active=null
        context.getSharedPreferences("collector",Context.MODE_PRIVATE).edit().putString("latest_session_id",session.id).apply()
        return session
    }

    fun latestSessionJson(): String {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE).getString("latest_session_id",null) ?: return "null"
        val file=File(File(root,id),"session.json"); return if(file.exists()) file.readText() else "null"
    }

    fun latestFrameBase64(index:Int):String? {
        val id=context.getSharedPreferences("collector",Context.MODE_PRIVATE).getString("latest_session_id",null) ?: return null
        val frames=JSONObject(latestSessionJson()).optJSONArray("frames") ?: return null
        if(index !in 0 until frames.length()) return null
        val name=frames.getJSONObject(index).getString("fileName"); val bytes=File(File(root,id),name).readBytes()
        return android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP)
    }

    private fun sessionDir(session:CaptureSession)=File(root,session.id)
    private fun persist(session:CaptureSession){
        val frames=JSONArray(); session.frames.forEach { frame -> frames.put(JSONObject().apply {
            put("index",frame.index); put("fileName",frame.fileName); put("capturedAt",frame.capturedAt); put("sourcePackage",frame.sourcePackage)
            put("width",frame.width); put("height",frame.height); put("fingerprint",frame.fingerprint.toString()); put("textHint",frame.textHint)
        }) }
        val json=JSONObject().apply { put("version",2); put("kind","osm-collector-session"); put("id",session.id); put("startedAt",session.startedAt); put("endedAt",session.endedAt ?: JSONObject.NULL); put("state",session.state); put("frames",frames) }
        File(sessionDir(session),"session.json").writeText(json.toString(2))
    }
}
''', encoding='utf-8')

(ROOT/'android-collector/app/src/main/res/xml/accessibility_service_config.xml').write_text('''<?xml version="1.0" encoding="utf-8"?>
<accessibility-service xmlns:android="http://schemas.android.com/apk/res/android"
    android:accessibilityEventTypes="typeWindowStateChanged|typeWindowContentChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:notificationTimeout="150"
    android:canRetrieveWindowContent="true"
    android:canTakeScreenshot="true"
    android:description="@string/accessibility_service_description" />
''', encoding='utf-8')

(ROOT/'android-collector/app/src/main/java/com/osmaicoach/collector/OsmCaptureAccessibilityService.kt').write_text('''package com.osmaicoach.collector

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.Executor

class OsmCaptureAccessibilityService : AccessibilityService() {
    private lateinit var repository: SessionRepository
    private val handler=Handler(Looper.getMainLooper())
    private val executor:Executor by lazy { mainExecutor }
    private var lastCaptureAt=0L
    private var lastFingerprint:Long?=null
    private var osmWasForeground=false
    private var pendingCapture=false
    private var finishRunnable:Runnable?=null

    override fun onServiceConnected(){ repository=SessionRepository(applicationContext); CollectorState.lastError=null; CollectorState.setServiceReady(true) }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        val pkg=event?.packageName?.toString() ?: return; val nowOsm=pkg==OSM_PACKAGE; CollectorState.currentForegroundPackage=pkg
        if(nowOsm){ finishRunnable?.let(handler::removeCallbacks); finishRunnable=null; osmWasForeground=true; repository.startIfNeeded(); CollectorState.setRecording(true); scheduleCapture(); return }
        if(osmWasForeground) scheduleFinish()
    }

    private fun scheduleFinish(){
        finishRunnable?.let(handler::removeCallbacks)
        val task=Runnable { if(CollectorState.currentForegroundPackage!=OSM_PACKAGE && osmWasForeground){ repository.finish(); osmWasForeground=false; pendingCapture=false; CollectorState.setRecording(false); CollectorState.signalSessionReady() } }
        finishRunnable=task; handler.postDelayed(task,1200L)
    }

    private fun scheduleCapture(extraDelay:Long=0L){
        if(pendingCapture)return
        val elapsed=System.currentTimeMillis()-lastCaptureAt; val delay=maxOf(1300L-elapsed,200L,extraDelay)
        pendingCapture=true; handler.postDelayed({pendingCapture=false;captureNow()},delay)
    }

    private fun visibleText():String{
        val root=rootInActiveWindow ?: return ""; val values=LinkedHashSet<String>()
        fun walk(node:AccessibilityNodeInfo?,depth:Int){ if(node==null||depth>14||values.size>=180)return; node.text?.toString()?.trim()?.takeIf{it.isNotEmpty()}?.let(values::add); node.contentDescription?.toString()?.trim()?.takeIf{it.isNotEmpty()}?.let(values::add); for(i in 0 until node.childCount) walk(node.getChild(i),depth+1) }
        runCatching{walk(root,0)}; return values.joinToString(" | ").take(6000)
    }

    private fun captureNow(){
        if(CollectorState.currentForegroundPackage!=OSM_PACKAGE)return
        val textHint=visibleText()
        takeScreenshot(Display.DEFAULT_DISPLAY,executor,object:TakeScreenshotCallback{
            override fun onSuccess(screenshot:ScreenshotResult){
                val buffer=screenshot.hardwareBuffer; val hw=Bitmap.wrapHardwareBuffer(buffer,screenshot.colorSpace); buffer.close()
                if(hw==null){CollectorState.lastError="Falha de captura: bitmap indisponível";CollectorState.signalFrameCaptured();return}
                val bitmap=hw.copy(Bitmap.Config.ARGB_8888,false); hw.recycle(); val fp=BitmapFingerprint.aHash(bitmap); val prev=lastFingerprint
                if(prev==null||BitmapFingerprint.distance(prev,fp)>3){repository.saveFrame(bitmap,fp,textHint);lastFingerprint=fp}
                lastCaptureAt=System.currentTimeMillis();CollectorState.lastError=null;CollectorState.signalFrameCaptured();bitmap.recycle()
            }
            override fun onFailure(errorCode:Int){ if(errorCode==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){lastCaptureAt=System.currentTimeMillis();scheduleCapture(1450L);return};CollectorState.lastError="Falha de captura: $errorCode";CollectorState.signalFrameCaptured() }
        })
    }

    override fun onInterrupt(){finishRunnable?.let(handler::removeCallbacks);repository.finish();osmWasForeground=false;pendingCapture=false;CollectorState.setRecording(false);CollectorState.setServiceReady(false)}
    override fun onDestroy(){finishRunnable?.let(handler::removeCallbacks);repository.finish();osmWasForeground=false;pendingCapture=false;CollectorState.setRecording(false);CollectorState.setServiceReady(false);super.onDestroy()}
}
''', encoding='utf-8')

ui_path=ROOT/'src/ui.js'; ui=ui_path.read_text(encoding='utf-8'); start=ui.find("const NATIVE_SESSION_DONE="); end=ui.find("\nfunction readCurrentReview",start)
if start<0 or end<0: raise SystemExit('Bloco nativo do ui.js não encontrado')

native='''const NATIVE_SESSION_DONE='osm-ai-coach-pro:native-session-done:v2';
let nativeCollectorBusy=false,nativeCollectorNotice='';
function nativeBridge(){return globalThis.OsmCollector&&typeof globalThis.OsmCollector.latestSession==='function'?globalThis.OsmCollector:null;}
function nativeNormalize(v){return String(v||'').normalize('NFD').replace(/[\\u0300-\\u036f]/g,'').toLowerCase().replace(/[^a-z0-9]+/g,' ').trim();}
function nativeSameClub(a,b){const x=nativeNormalize(a),y=nativeNormalize(b);return !!x&&!!y&&(x===y||x.includes(y)||y.includes(x));}
function nativeFile(b64,name){const bin=atob(b64),bytes=new Uint8Array(bin.length);for(let i=0;i<bin.length;i++)bytes[i]=bin.charCodeAt(i);return new File([bytes],name,{type:'image/jpeg',lastModified:Date.now()});}
function nativeParsed(type,r){if(type==='match')return {...(r.match||{}),_fieldSources:r._matchFieldSources||r.match?._fieldSources||{}};if(type==='squad')return {players:r.players||[],playerCandidates:r.playerCandidates||[],meta:r.meta||{}};const rows=[...(r.calendar||[])];rows.meta=r.meta||{};return rows;}
function nativeTeam(type,r){return readingTeam(type,nativeParsed(type,r),r.meta||{});}
function nativeTargetSlot(preferred,r,type){const team=nativeTeam(type,r);if(known(team)){const hit=getState().slots.find(s=>known(slotTeam(s))&&nativeSameClub(slotTeam(s),team));if(hit)return hit.id;const p=getState().slots[preferred-1];if(p&&!known(slotTeam(p)))return preferred;return getState().slots.find(s=>!known(slotTeam(s)))?.id||preferred;}return preferred;}
function nativeType(frame){const t=nativeNormalize(frame?.textHint||'');if(!t)return'unknown';const groups={calendar:['calendario','calendar','jornada','rodada','proximo jogo','proximos jogos','ultimo dia','resultado'],squad:['plantel','elenco','squad','jogadores','players','guarda redes','goleiro','defesa','meio campo','atacante','avancado'],match:['analise','analysis','arbitro','referee','formacao','formation','marcacao','marking','impedimento','offside','treino secreto','secret training','campo de treinamento','training camp','estadio','stadium']};const scores=Object.fromEntries(Object.entries(groups).map(([k,ws])=>[k,ws.reduce((n,w)=>n+(t.includes(w)?1:0),0)]));const best=Object.entries(scores).sort((a,b)=>b[1]-a[1])[0];return best[1]?best[0]:'unknown';}
function nativeGroups(frames){const out=[];let pending=[];for(const f of frames){const type=nativeType(f);if(type==='unknown'){if(out.length)out.at(-1).indices.push(f.index);else pending.push(f.index);continue}const last=out.at(-1);if(last&&last.type===type)last.indices.push(f.index);else{out.push({type,indices:[...pending,f.index]});pending=[]}}if(pending.length&&out.length)out.at(-1).indices.push(...pending);return out.filter(g=>g.indices.length);}
function nativeFallback(frames){const count=frames.length,slots=Math.min(4,Math.max(1,Math.ceil(count/10))),out=[];let start=0;for(let s=0;s<slots;s++){const end=Math.round((s+1)*count/slots),idx=Array.from({length:end-start},(_,i)=>start+i);for(const type of ['match','squad','calendar'])out.push({type,indices:[...idx]});start=end}return out;}
function nativeSample(indices,max=16){if(indices.length<=max)return indices;return Array.from({length:max},(_,i)=>indices[Math.min(indices.length-1,Math.round(i*(indices.length-1)/(max-1)))]);}
async function nativeFiles(session,indices){const b=nativeBridge(),files=[];if(!b)return files;for(const i of nativeSample(indices)){const x=b.latestFrameBase64(i);if(x)files.push(nativeFile(x,'osm-'+session.id+'-'+i+'.jpg'));await new Promise(r=>setTimeout(r,0))}return files;}
async function nativeApply(files,type,preferred){const state=getState(),slot=state.slots[preferred-1],result=await analyzeMedia(files,type,{signal:new AbortController().signal,vision:hasSessionKey(),localRecovery:true,fallback:true,username:state.settings.username,myTeam:slotTeam(slot),rivalName:slot.match.rivalName,competitionType:slot.competitionType,model:state.settings.model,visionModel:state.settings.visionModel,profile:'fast'},m=>{nativeCollectorNotice='Sessão automática · S'+preferred+' · '+type+': '+m;const e=root?.querySelector?.('[data-native-status]');if(e)e.textContent=nativeCollectorNotice});const targetId=nativeTargetSlot(preferred,result,type),target=getState().slots[targetId-1],parsed=nativeParsed(type,result),next=applyReading(target,type,parsed,{meta:result.meta||{},teams:result.readingTeams||[],conflicts:result.conflicts||[],username:getState().settings.username,mode:'Sessão automática Android',readingAt:new Date().toISOString()});updateSlot(s=>Object.assign(s,next),targetId);return targetId;}
async function processNativeCollector(){const bridge=nativeBridge();if(!bridge||nativeCollectorBusy||busy)return;let session;try{session=JSON.parse(bridge.latestSession()||'null')}catch{return}if(!session||session.state!=='ready'||!session.id||!Array.isArray(session.frames)||!session.frames.length)return;if(localStorage.getItem(NATIVE_SESSION_DONE)===session.id)return;nativeCollectorBusy=true;busy=true;let success=0,currentSlot=1;const report=[];try{nativeCollectorNotice='Organizando '+session.frames.length+' telas capturadas…';if(root)render();let groups=nativeGroups(session.frames);if(!groups.length){nativeCollectorNotice='Texto da interface indisponível; usando recuperação visual…';if(root)render();groups=nativeFallback(session.frames)}for(let i=0;i<groups.length;i++){const g=groups[i],files=await nativeFiles(session,g.indices);if(!files.length)continue;try{nativeCollectorNotice='Grupo '+(i+1)+'/'+groups.length+' · '+g.type+' · analisando…';if(root)render();currentSlot=await nativeApply(files,g.type,currentSlot);success++;report.push('S'+currentSlot+' '+g.type+' ✓')}catch(error){report.push(g.type+': '+error.message)}}for(const slot of getState().slots){if(!known(slotTeam(slot)))continue;updateSlot(s=>{try{s.director.plan=buildMarketPlan(s.squad,s.director.cash,s.match.myStrength)}catch{}},slot.id)}if(success){localStorage.setItem(NATIVE_SESSION_DONE,session.id);nativeCollectorNotice='Sessão OSM atualizada: '+report.join(' · ')}else nativeCollectorNotice='Nenhum dado pôde ser aplicado. A sessão foi preservada para nova tentativa.'}catch(error){nativeCollectorNotice='Falha ao processar sessão automática: '+error.message}finally{nativeCollectorBusy=false;busy=false;if(root){render();toast(nativeCollectorNotice)}}}
'''
ui_path.write_text(ui[:start]+native+ui[end:],encoding='utf-8')
print('Finalização aplicada com sucesso.')
