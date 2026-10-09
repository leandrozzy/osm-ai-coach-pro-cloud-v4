package com.osmaicoach.collector

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build

/**
 * Saúde do app fora da tela: registra travamentos e se o serviço de leitura estava vivo, para explicar ao
 * usuário quando o Android fechou o app à força (economia de bateria do fabricante), o que desliga a
 * leitura automática e cancela os lembretes.
 */
object Health {
    private fun p(ctx: Context) = ctx.getSharedPreferences("health", Context.MODE_PRIVATE)

    @Volatile private var installed = false
    @Volatile private var lastBeat = 0L

    /** Guarda o último travamento (horário + causa) antes do processo morrer. */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val where = e.stackTrace.take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
                p(app).edit()
                    .putLong("crash_at", System.currentTimeMillis())
                    .putString("crash", (e.javaClass.simpleName + ": " + (e.message ?: "")).take(160) + " @ " + where.take(240))
                    .commit()
            } catch (x: Exception) {
                // nada a fazer: o processo já está caindo
            }
            prev?.uncaughtException(t, e)
        }
    }

    /** Batida do serviço de leitura (no máx. 1 gravação por minuto). */
    fun beat(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastBeat < 60000L) return
        lastBeat = now
        p(ctx).edit().putLong("svc_alive", now).apply()
    }

    fun lastAlive(ctx: Context): Long = p(ctx).getLong("svc_alive", 0L)

    fun lastCrash(ctx: Context): Pair<Long, String>? {
        val at = p(ctx).getLong("crash_at", 0L)
        if (at == 0L) return null
        return Pair(at, p(ctx).getString("crash", "") ?: "")
    }

    fun clearCrash(ctx: Context) {
        p(ctx).edit().remove("crash_at").remove("crash").apply()
    }

    val brand: String get() = Build.MANUFACTURER.lowercase()

    /** Passo a passo do fabricante para o Android não fechar o app. */
    fun brandSteps(): String = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
            "Xiaomi/Redmi/POCO: Segurança → Inicialização automática → ligar OSM AI Coach; Bateria → Sem restrições; nos Recentes, segure o app e toque no cadeado."
        brand.contains("samsung") ->
            "Samsung: Configurações → Bateria → Limites de uso em segundo plano → tirar o app de \"Apps em suspensão\" e \"suspensão profunda\"; Bateria do app → Sem restrição."
        brand.contains("motorola") ->
            "Motorola: Info do app → Bateria → Sem restrição; não feche o app pelos Recentes."
        brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
            "OPPO/realme/OnePlus: Info do app → Bateria → Permitir atividade em segundo plano e Inicialização automática."
        brand.contains("huawei") || brand.contains("honor") ->
            "Huawei/Honor: Bateria → Inicialização de apps → OSM AI Coach em manual com tudo ligado."
        brand.contains("vivo") ->
            "vivo: Bateria → Consumo em segundo plano alto → permitir; Inicialização automática ligada."
        else -> "Info do app → Bateria → Sem restrição; não feche o app pelos Recentes."
    }

    /** Abre a tela de inicialização automática do fabricante; se não existir, a Info do app. */
    fun openAutostart(ctx: Context) {
        val candidates = listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        for (c in candidates) {
            try {
                ctx.startActivity(Intent().setComponent(c).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (e: Exception) {
                // não existe neste aparelho: tenta o próximo
            }
        }
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + ctx.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Diag.lastError = "Não consegui abrir as configurações do app"
        }
    }
}
