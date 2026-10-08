package com.osmaicoach.collector

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.util.Calendar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Navegação pedida por uma notificação: abre o slot na aba certa. */
object Route {
    var slot by mutableIntStateOf(0)
    var tab by mutableIntStateOf(0)
    var nonce by mutableIntStateOf(0)
}

/** O horário do jogo vem do card do calendário ("22:18"), que é o horário real da partida. */
object MatchClock {
    private val RX_TIME = Regex("^(\\d{1,2}):(\\d{2})$")
    private val RX_DATE = Regex("^(\\d{2})/(\\d{2})/(\\d{2})$")

    fun toMillis(date: String?, time: String, now: Long): Long? {
        val m = RX_TIME.find(time.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        var dated = false
        if (date != null) {
            val d = RX_DATE.find(date.trim())
            if (d != null) {
                cal.set(2000 + d.groupValues[3].toInt(), d.groupValues[2].toInt() - 1, d.groupValues[1].toInt())
                dated = true
            }
        }
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        var t = cal.timeInMillis
        // Sem data no card, o horário é de hoje; se já passou há muito tempo, é o de amanhã.
        if (!dated && t < now - 12L * 3600000L) t += 24L * 3600000L
        return t
    }
}

object Notifier {
    const val CH_GAME = "jogos"
    const val CH_DIRECTOR = "diretor"
    private const val MIN = 60000L

    data class Alarm(val kind: String, val at: Long)

    /** Alarmes futuros para um jogo: tática (1 h antes), reanálise (20 min antes) e resultado (30 min depois). */
    fun alarms(nextAt: Long, now: Long): List<Alarm> =
        listOf(Alarm("tactic", nextAt - 60 * MIN), Alarm("pre", nextAt - 20 * MIN), Alarm("result", nextAt + 30 * MIN))
            .filter { it.at > now + 5000L }

    /** Já está dentro da janela de 20 minutos (e o jogo ainda não começou)? Então o aviso sai agora. */
    fun dueNow(nextAt: Long, now: Long): Boolean = now >= nextAt - 20 * MIN && now < nextAt - MIN

    fun enabled(ctx: Context, key: String): Boolean = Settings.get(ctx, "notif_$key", "1") == "1"

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun ensureChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val game = NotificationChannel(CH_GAME, "Lembretes de jogo", NotificationManager.IMPORTANCE_HIGH)
        game.description = "20 minutos antes de cada jogo, tática pendente e resultado"
        val dir = NotificationChannel(CH_DIRECTOR, "Alertas do diretor", NotificationManager.IMPORTANCE_DEFAULT)
        dir.description = "Treino livre, preço do mercado, estádio e meta da temporada"
        nm.createNotificationChannel(game)
        nm.createNotificationChannel(dir)
    }

    private fun code(slot: Int, kind: String): Int = slot * 10 + (if (kind == "pre") 1 else if (kind == "tactic") 2 else 3)

    private fun pending(ctx: Context, slot: Int, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code(slot, kind), Intent(ctx, NotifyReceiver::class.java).putExtra("kind", kind).putExtra("slot", slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun setAlarm(ctx: Context, am: AlarmManager, slot: Int, kind: String, at: Long) {
        val pi = pending(ctx, slot, kind)
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notifier", Context.MODE_PRIVATE)

    /** Recalcula os alarmes de cada slot a partir do horário do jogo guardado (calendário). */
    suspend fun reschedule(ctx: Context) {
        ensureChannels(ctx)
        val repo = Repo(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        for (slot in 1..4) {
            val nextAt = repo.fieldMap(slot)[K.MATCH_AT]?.value?.toLongOrNull()
            val wanted = if (nextAt == null) emptyList() else alarms(nextAt, now)
            for (kind in listOf("tactic", "pre", "result")) {
                val key = "alarm_${slot}_$kind"
                val at = wanted.firstOrNull { it.kind == kind }?.at
                val on = enabled(ctx, kind)
                if (at == null || !on) {
                    if (p.contains(key)) {
                        am.cancel(pending(ctx, slot, kind))
                        p.edit().remove(key).apply()
                    }
                    continue
                }
                val stored = p.getLong(key, -1L)
                if (stored < 0L || kotlin.math.abs(stored - at) > 2 * MIN) {
                    setAlarm(ctx, am, slot, kind, at)
                    p.edit().putLong(key, at).apply()
                }
            }
            if (nextAt != null && enabled(ctx, "pre") && dueNow(nextAt, now)) {
                val fired = "fired_${slot}_${nextAt / MIN}_pre"
                if (!p.getBoolean(fired, false)) fire(ctx, "pre", slot)
            }
        }
    }

    private suspend fun tacticReady(repo: Repo, slot: Int, f: Map<String, StoredField>): Boolean {
        val plan = repo.dao.plan(slot, "tactic") ?: return false
        val round = f[K.ROUND]?.value?.toIntOrNull() ?: return false
        return try {
            JSONObject(plan.json).optInt("forRound", -1) == round
        } catch (e: Exception) {
            false
        }
    }

    suspend fun fire(ctx: Context, kind: String, slot: Int) {
        if (!canPost(ctx)) return
        ensureChannels(ctx)
        val repo = Repo(ctx)
        val f = repo.fieldMap(slot)
        val team = f[K.TEAM]?.value ?: f[K.HUB_TITLE]?.value ?: "Slot $slot"
        val rival = f[K.RIVAL_TEAM]?.value ?: "adversário"
        val nextAt = f[K.MATCH_AT]?.value?.toLongOrNull()
        val now = System.currentTimeMillis()
        val head = "S$slot • $team vs $rival"
        if (kind == "pre") {
            if (!enabled(ctx, "pre")) return
            if (nextAt != null) prefs(ctx).edit().putBoolean("fired_${slot}_${nextAt / MIN}_pre", true).apply()
            val mins = if (nextAt != null) ((nextAt - now) / MIN).coerceAtLeast(0L).toString() else "~20"
            post(
                ctx, CH_GAME, slot * 10 + 1, "⚽ Faltam $mins min: $head",
                "Abra o OSM e passe pelo Pré-jogo e pela análise do rival para o app reler os dados (rival humano pode mudar a tática) e revisar a tática.",
                slot, 4, true
            )
        } else if (kind == "tactic") {
            if (!enabled(ctx, "tactic") || tacticReady(repo, slot, f)) return
            post(ctx, CH_GAME, slot * 10 + 2, "🧠 Falta a tática: $head", "O jogo é em cerca de 1 hora e a tática desta rodada ainda não foi gerada. Toque para gerar.", slot, 4, false)
        } else if (kind == "result") {
            if (!enabled(ctx, "result")) return
            val pendingResult = repo.dao.tacticLogs(slot).any {
                try {
                    JSONObject(it.json).isNull("result")
                } catch (e: Exception) {
                    false
                }
            }
            if (!pendingResult) return
            post(
                ctx, CH_GAME, slot * 10 + 3, "📊 Registre o resultado: $head",
                "O jogo já deve ter terminado. Abra a análise do jogo no OSM (o app lê sozinho) ou registre o placar na aba Resultado para a IA aprender.",
                slot, 5, true
            )
        }
    }

    /** Resumo do diretor ao fim da leitura: só avisa se houver novidade desde o último aviso. */
    suspend fun directorSummary(ctx: Context) {
        if (!enabled(ctx, "director") || !canPost(ctx)) return
        val repo = Repo(ctx)
        val lines = ArrayList<String>()
        for (slot in 1..4) {
            val f = repo.fieldMap(slot)
            val plan = Director.marketPlan(repo, slot)
            if (plan != null) {
                val free = 5 - plan.trainingActive
                if (free > 0 && plan.train.isNotEmpty()) lines.add("S$slot: $free treino(s) livre(s)")
            }
            lines.addAll(Director.priceDrops(repo, slot))
            val sp = Director.stadiumPlan(f)
            if (sp != null && sp.contains("cabe no caixa")) lines.add("S$slot: um melhoramento do estádio cabe no caixa")
            val gp = f[K.MY_OBJECTIVE]?.value?.toIntOrNull()
            val np = f[K.LEAGUE_POS]?.value?.toIntOrNull()
            if (gp != null && np != null && np > gp) lines.add("S$slot: fora da meta (top $gp, hoje ${np}º)")
        }
        if (lines.isEmpty()) return
        val hash = lines.joinToString("|").hashCode().toString()
        if (prefs(ctx).getString("director_hash", "") == hash) return
        prefs(ctx).edit().putString("director_hash", hash).apply()
        ensureChannels(ctx)
        post(ctx, CH_DIRECTOR, 900, "🧠 Diretor: ${lines.size} aviso(s)", lines.joinToString("\n"), 0, 0, false)
    }

    fun test(ctx: Context) {
        ensureChannels(ctx)
        post(ctx, CH_GAME, 990, "✅ Notificações funcionando", "Você receberá o aviso 20 minutos antes de cada jogo para reler o pré-jogo e a análise do rival.", 0, 0, false)
    }

    private fun post(ctx: Context, channel: String, id: Int, title: String, text: String, slot: Int, tab: Int, withOsm: Boolean) {
        if (!canPost(ctx)) return
        val open = Intent(ctx, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("slot", slot).putExtra("tab", tab)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
        if (withOsm) {
            val osm = ctx.packageManager.getLaunchIntentForPackage(OSM_PACKAGE)
            if (osm != null) {
                val opi = PendingIntent.getActivity(ctx, id + 5000, osm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                b.addAction(Notification.Action.Builder(Icon.createWithResource(ctx, R.drawable.ic_stat_notify), "Abrir OSM", opi).build())
            }
        }
        ctx.getSystemService(NotificationManager::class.java).notify(id, b.build())
    }
}

class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        val kind = intent.getStringExtra("kind") ?: ""
        val slot = intent.getIntExtra("slot", 0)
        AppScope.scope.launch(Dispatchers.IO) {
            try {
                Notifier.fire(app, kind, slot)
                Notifier.reschedule(app)
            } catch (e: Exception) {
                Diag.lastError = "Notificação: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }
}

/** Reagenda os lembretes depois de reiniciar o celular ou atualizar o app. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext
        AppScope.scope.launch(Dispatchers.IO) {
            try {
                Notifier.reschedule(app)
            } catch (e: Exception) {
                Diag.lastError = "Notificação: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }
}
