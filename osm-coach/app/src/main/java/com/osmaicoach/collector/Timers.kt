package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Cronômetros lidos no OSM: treino de cada treinador (universal, avançados, médios, defesas, guarda-redes) e obra de
 * cada card do estádio (Capacidade, Relvado, Treino), por slot. O fim de cada um vira um alarme do Android, que
 * avisa mesmo com o app fechado.
 */
object Timers {
    /** Ordem fixa: o índice entra no código do alarme (um alarme por cronômetro e por slot). */
    val KEYS = listOf(
        "train_UNI", "train_ATA", "train_MEI", "train_DEF", "train_GOL",
        "stad_Capacidade", "stad_Relvado", "stad_Treino"
    )

    data class Entry(val slot: Int, val key: String, val at: Long, val label: String) {
        val training: Boolean get() = key.startsWith("train_")
    }

    fun roleLabel(role: String): String = when (role) {
        "UNI" -> "universal"
        "ATA" -> "avançados"
        "MEI" -> "médios"
        "DEF" -> "defesas"
        "GOL" -> "guarda-redes"
        else -> role
    }

    fun parse(slot: Int, json: String?): List<Entry> {
        val j = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        return KEYS.mapNotNull { k ->
            val o = j.optJSONObject(k) ?: return@mapNotNull null
            val at = o.optLong("at", 0L).takeIf { it > 0 } ?: return@mapNotNull null
            Entry(slot, k, at, o.optString("label", k))
        }.sortedBy { it.at }
    }

    /** Contador igual ao do jogo: "3h 09m 14s", "05m 48s", "1d 04h 10m". */
    fun clock(at: Long, now: Long): String {
        val s = ((at - now) / 1000).coerceAtLeast(0)
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return when {
            d > 0 -> "%dd %02dh %02dm".format(d, h, m)
            h > 0 -> "%dh %02dm %02ds".format(h, m, sec)
            else -> "%02dm %02ds".format(m, sec)
        }
    }

    /** "em 3h 09m" / "em 12 min" / "terminou". */
    fun left(at: Long, now: Long): String {
        val s = (at - now) / 1000
        if (s <= 0) return "terminou"
        val d = s / 86400
        val h = (s % 86400) / 3600
        val m = (s % 3600) / 60
        return when {
            d > 0 -> "em ${d}d ${h}h"
            h > 0 -> "em ${h}h %02dm".format(m)
            else -> "em ${maxOf(m, 1)} min"
        }
    }
}

/** Um treinador do slot como o app viu na última leitura (com o fim do cronômetro, se houver). */
data class WorkSeat(val role: String, val name: String?, val state: String, val endAt: Long?)

/** Um card do estádio: nível, obra em andamento (fim) e o próximo melhoramento ("760K + 18h"). */
data class WorkCard(val label: String, val level: Int?, val endAt: Long?, val next: String?)

data class SlotWork(val slot: Int, val team: String, val seats: List<WorkSeat>, val cards: List<WorkCard>) {
    val hasData: Boolean get() = seats.isNotEmpty() || cards.any { it.level != null || it.endAt != null || it.next != null }
    /** Treinadores de posição livres ou com treino para reclamar: é onde o usuário perde tempo de treino. */
    fun needsAction(now: Long): Int = seats.count { it.role != "UNI" && (it.state == "empty" || it.state == "done" || (it.endAt != null && it.endAt <= now)) }
    fun nextEnd(now: Long): Long? = (seats.mapNotNull { it.endAt } + cards.mapNotNull { it.endAt }).filter { it > now }.minOrNull()
}

object Work {
    private val ROLES = listOf("UNI", "ATA", "MEI", "DEF", "GOL")
    private val CARDS = listOf(Triple("Capacidade", K.MY_STAD_CAP, "🏟"), Triple("Relvado", K.MY_STAD_PITCH, "🌱"), Triple("Treino", K.MY_STAD_TRAIN, "🎯"))

    fun icon(label: String): String = CARDS.firstOrNull { it.first == label }?.third ?: "🏟"

    fun of(slot: Int, team: String, screen: JSONObject?, timers: List<Timers.Entry>, f: Map<String, StoredField>): SlotWork {
        val byKey = timers.associateBy { it.key }
        val seats = ROLES.mapNotNull { r ->
            val o = screen?.optJSONObject(r) ?: return@mapNotNull null
            val state = o.optString("state")
            // universal bloqueado/livre não interessa (é pago); só aparece quando está em uso
            if (r == "UNI" && state != "active" && state != "done") return@mapNotNull null
            val name = if (o.isNull("name")) null else o.optString("name").ifBlank { null }?.let { TrainBoard.pretty(it) }
            WorkSeat(r, name, state, if (state == "active") byKey["train_$r"]?.at else null)
        }
        val status = f[K.MY_STAD_STATUS]?.value.orEmpty().split(" • ").mapNotNull { p ->
            val i = p.indexOf(": ")
            if (i <= 0) null else Pair(p.substring(0, i), p.substring(i + 2))
        }.toMap()
        val cards = CARDS.map { (label, key, _) ->
            WorkCard(label, f[key]?.value?.toIntOrNull(), byKey["stad_$label"]?.at, status[label])
        }
        return SlotWork(slot, team, seats, cards)
    }
}
