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
