package com.osmaicoach.collector

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

    override fun onServiceConnected(){
        repository=SessionRepository(applicationContext)
        getSharedPreferences("collector_runtime", MODE_PRIVATE)
            .edit().putBoolean("accessibility_connected", true).apply()
        CollectorState.lastError=null
        CollectorState.setServiceReady(true)
    }

    override fun onAccessibilityEvent(event:AccessibilityEvent?){
        val pkg=event?.packageName?.toString()?:return
        val isOsm=pkg==OSM_PACKAGE
        CollectorState.currentForegroundPackage=pkg

        if(isOsm){
            finishRunnable?.let(handler::removeCallbacks); finishRunnable=null
            osmWasForeground=true
            val runtime=getSharedPreferences("collector_runtime", MODE_PRIVATE)
            val requestedAt=runtime.getLong("requested_session_at",0L)
            val pending=runtime.getBoolean("start_new_session_pending",false)
            val current=repository.current()
            if(pending){
                if(current==null || current.startedAt + 500L < requestedAt){
                    repository.beginNewSession()
                }
                // A primeira tela de uma nova sessão precisa ser capturada mesmo se
                // visualmente for igual à última tela da sessão anterior.
                lastFingerprint=null
                runtime.edit().putBoolean("start_new_session_pending",false).apply()
            }else{
                repository.startIfNeeded()
            }
            runtime.edit()
                .putBoolean("osm_seen_in_session", true)
                .putLong("last_osm_seen_at", System.currentTimeMillis())
                .apply()
            CollectorState.setRecording(true)
            scheduleCapture()
        } else if (pkg == packageName && osmWasForeground) {
            // Só encerra quando o usuário VOLTA ao Coach. Propagandas, navegador,
            // Play Store e outras telas externas não quebram a sessão do OSM.
            scheduleFinish()
        }
    }

    private fun scheduleFinish(){
        finishRunnable?.let(handler::removeCallbacks)
        val task=Runnable{
            if(CollectorState.currentForegroundPackage==packageName&&osmWasForeground){
                repository.finish()
                getSharedPreferences("collector_runtime", MODE_PRIVATE).edit()
                    .putBoolean("osm_seen_in_session", false)
                    .apply()
                osmWasForeground=false
                pendingCapture=false
                CollectorState.setRecording(false)
                CollectorState.signalSessionReady()
            }
        }
        finishRunnable=task
        handler.postDelayed(task,700L)
    }

    private fun scheduleCapture(){
        if(pendingCapture)return
        val delay=maxOf(1450L-(System.currentTimeMillis()-lastCaptureAt),180L)
        pendingCapture=true
        handler.postDelayed({pendingCapture=false;captureNow()},delay)
    }

    private fun visibleText():String{
        val root=rootInActiveWindow?:return ""
        val out=LinkedHashSet<String>()
        fun walk(n:AccessibilityNodeInfo?,depth:Int){
            if(n==null||depth>16||out.size>=220)return
            n.text?.toString()?.trim()?.takeIf{it.isNotBlank()}?.let(out::add)
            n.contentDescription?.toString()?.trim()?.takeIf{it.isNotBlank()}?.let(out::add)
            for(i in 0 until n.childCount)walk(n.getChild(i),depth+1)
        }
        runCatching{walk(root,0)}
        return out.joinToString(" | ").take(7000)
    }

    private fun captureNow(){
        if(CollectorState.currentForegroundPackage!=OSM_PACKAGE)return
        val hint=visibleText()
        takeScreenshot(Display.DEFAULT_DISPLAY,executor,object:TakeScreenshotCallback{
            override fun onSuccess(result:ScreenshotResult){
                val buffer=result.hardwareBuffer
                val hardware=Bitmap.wrapHardwareBuffer(buffer,result.colorSpace)
                buffer.close()
                if(hardware==null){
                    CollectorState.lastError="Falha de captura: bitmap"
                    return
                }
                val bitmap=hardware.copy(Bitmap.Config.ARGB_8888,false)
                hardware.recycle()
                val fp=BitmapFingerprint.aHash(bitmap)
                val old=lastFingerprint
                if(old==null||BitmapFingerprint.distance(old,fp)>2){
                    repository.saveFrame(bitmap,fp,hint)
                    lastFingerprint=fp
                    CollectorState.signalFrameCaptured()
                }
                lastCaptureAt=System.currentTimeMillis()
                CollectorState.lastError=null
                bitmap.recycle()
                // Continue capturando enquanto o OSM estiver em primeiro plano.
                // Não dependemos de um novo evento de acessibilidade para cada tela.
                if(CollectorState.currentForegroundPackage==OSM_PACKAGE) scheduleCapture()
            }
            override fun onFailure(errorCode:Int){
                if(errorCode==ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT){
                    lastCaptureAt=System.currentTimeMillis()
                    scheduleCapture()
                }else{
                    CollectorState.lastError="Falha de captura: $errorCode"
                    if(CollectorState.currentForegroundPackage==OSM_PACKAGE) scheduleCapture()
                }
            }
        })
    }

    override fun onInterrupt(){finish()}
    override fun onDestroy(){finish();super.onDestroy()}

    private fun finish(){
        finishRunnable?.let(handler::removeCallbacks)
        if(::repository.isInitialized)repository.finish()
        osmWasForeground=false
        pendingCapture=false
        CollectorState.setRecording(false)
        CollectorState.setServiceReady(false)
    }
}
