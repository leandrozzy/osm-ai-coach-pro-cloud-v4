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
        // Sem data, o card mostra a hora do PRÓXIMO jogo (ainda sem placar): se essa hora já passou no momento da
        // leitura, o jogo é amanhã. (Antes: "hoje" até 12 h depois — fazia pedir resultado de jogo que não aconteceu.)
        if (!dated && t < now - 5L * 60000L) t += 24L * 3600000L
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

    private fun code(slot: Int, kind: String): Int =
        slot * 10 + (if (kind == "pre") 1 else if (kind == "tactic") 2 else if (kind == "test") 4 else 3)

    /** Teste real: agenda um aviso para daqui a 1 minuto (feche o app e espere). */
    fun testLater(ctx: Context) {
        ensureChannels(ctx)
        setAlarm(ctx, ctx.getSystemService(AlarmManager::class.java), 0, "test", System.currentTimeMillis() + 60000L)
    }

    private fun pending(ctx: Context, slot: Int, kind: String): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, code(slot, kind), Intent(ctx, NotifyReceiver::class.java).putExtra("kind", kind).putExtra("slot", slot),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    /** O Android deixa este app agendar alarmes no minuto exato? (Android 12: permissão "Alarmes e lembretes".) */
    fun canExact(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

    /**
     * Alarme tipo despertador: dispara no minuto certo mesmo com o app fechado e o celular em economia de
     * bateria (o Android não adia esse tipo). Sem permissão de alarme exato, cai para o modo comum.
     */
    private fun setAlarm(ctx: Context, am: AlarmManager, slot: Int, kind: String, at: Long) {
        val pi = pending(ctx, slot, kind)
        try {
            if (canExact(ctx)) {
                val show = PendingIntent.getActivity(
                    ctx, 7000 + code(slot, kind), Intent(ctx, MainActivity::class.java).putExtra("slot", slot).putExtra("tab", 4),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    /**
     * Verificação periódica (≈ a cada hora), com o app fechado: rearma os lembretes, dispara o aviso de
     * 20 min se a janela já chegou e manda o resumo do diretor quando há novidade.
     */
    private fun armTick(ctx: Context, am: AlarmManager) {
        val pi = PendingIntent.getBroadcast(
            ctx, 9001, Intent(ctx, NotifyReceiver::class.java).putExtra("kind", "tick").putExtra("slot", 0),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_HOUR, AlarmManager.INTERVAL_HOUR, pi)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notifier", Context.MODE_PRIVATE)

    /** Um registro por slot (o jogo do último aviso "pre"), em vez de uma chave nova a cada jogo. */
    private fun firedKey(slot: Int) = "fired_pre_$slot"

    /**
     * Recalcula os alarmes de cada slot a partir do horário do jogo guardado (calendário).
     * [force]: o Android apaga os alarmes ao reiniciar o celular, mas as preferências continuam dizendo
     * que eles existem; depois do boot é preciso armar tudo de novo.
     */
    suspend fun reschedule(ctx: Context, force: Boolean = false) {
        ensureChannels(ctx)
        val repo = Repo(ctx)
        val am = ctx.getSystemService(AlarmManager::class.java)
        val now = System.currentTimeMillis()
        val p = prefs(ctx)
        if (force || !p.getBoolean("tick_armed", false)) {
            armTick(ctx, am)
            p.edit().putBoolean("tick_armed", true).apply()
        }
        // Limpa as chaves "fired_<slot>_<minuto>_pre" da versão anterior (uma por jogo, nunca apagadas).
        val legacy = p.all.keys.filter { it.startsWith("fired_") && !it.startsWith("fired_pre_") }
        if (legacy.isNotEmpty()) {
            val e = p.edit()
            legacy.forEach { e.remove(it) }
            e.apply()
        }
        for (slot in 1..4) {
            val nextAt = try { repo.syncRound(slot, now).at } catch (e: Exception) { repo.fieldMap(slot)[K.MATCH_AT]?.value?.toLongOrNull() }
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
                if (force || stored < 0L || kotlin.math.abs(stored - at) > 2 * MIN) {
                    setAlarm(ctx, am, slot, kind, at)
                    p.edit().putLong(key, at).apply()
                }
            }
            if (nextAt != null && enabled(ctx, "pre") && dueNow(nextAt, now)) {
                if (p.getLong(firedKey(slot), -1L) != nextAt / MIN) fire(ctx, "pre", slot)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun tacticReady(repo: Repo, slot: Int, f: Map<String, StoredField>): Boolean = Director.tacticReady(repo, slot)

    suspend fun fire(ctx: Context, kind: String, slot: Int) {
        if (!canPost(ctx)) return
        if (kind == "test") {
            ensureChannels(ctx)
            post(ctx, CH_GAME, 991, "✅ Aviso com o app fechado funcionando", "Os lembretes de jogo vão chegar mesmo com o OSM AI Coach fechado.", 0, 0, false)
            return
        }
        ensureChannels(ctx)
        val repo = Repo(ctx)
        val clk = try { repo.syncRound(slot) } catch (e: Exception) { null }
        val f = repo.fieldMap(slot)
        val team = f[K.TEAM]?.value ?: f[K.HUB_TITLE]?.value ?: "Slot $slot"
        val rival = f[K.RIVAL_TEAM]?.value ?: "adversário"
        val nextAt = f[K.MATCH_AT]?.value?.toLongOrNull()
        val now = System.currentTimeMillis()
        val head = "S$slot • $team vs $rival"
        if (kind == "pre") {
            if (!enabled(ctx, "pre")) return
            if (nextAt != null) prefs(ctx).edit().putLong(firedKey(slot), nextAt / MIN).apply()
            val mins = if (nextAt != null) ((nextAt - now) / MIN).coerceAtLeast(0L).toString() else "~20"
            val (sw, chk) = try { repo.rivalSwitches(slot) } catch (e: Exception) { Pair(0, 0) }
            val extra = if (sw > 0) " ⚠ Este usuário já trocou a tática na última hora ($sw de $chk): releia a análise dele AGORA e gere de novo." else ""
            post(
                ctx, CH_GAME, slot * 10 + 1, "⚽ Faltam $mins min: $head",
                "Abra o OSM e passe pelo Pré-jogo e pela análise do rival para o app reler os dados (rival humano pode mudar a tática) e revisar a tática.$extra",
                slot, 4, true
            )
        } else if (kind == "tactic") {
            if (!enabled(ctx, "tactic") || tacticReady(repo, slot, f)) return
            post(ctx, CH_GAME, slot * 10 + 2, "🧠 Falta a tática: $head", "O jogo é em cerca de 1 hora e a tática desta rodada ainda não foi gerada. Toque para gerar.", slot, 4, false)
        } else if (kind == "result") {
            if (!enabled(ctx, "result")) return
            // mesma regra da tela Hoje: só o jogo que já passou do horário e ainda não tem placar
            val pending = Fixtures.resultDue(repo.dao.matchesOf(slot), repo.scoredRounds(slot), now, clk?.round, clk?.at)
            if (pending == null) return
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
        Health.install(app)
        val kind = intent.getStringExtra("kind") ?: ""
        val slot = intent.getIntExtra("slot", 0)
        AppScope.scope.launch(Dispatchers.IO) {
            try {
                if (kind == "tick") {
                    Notifier.reschedule(app)
                    Notifier.directorSummary(app)
                } else {
                    Notifier.fire(app, kind, slot)
                    Notifier.reschedule(app)
                }
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
                Notifier.reschedule(app, force = true)
            } catch (e: Exception) {
                Diag.lastError = "Notificação: " + (e.message ?: e.javaClass.simpleName)
            } finally {
                pending.finish()
            }
        }
    }
}
