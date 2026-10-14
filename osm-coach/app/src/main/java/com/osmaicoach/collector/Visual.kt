package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Dados prontos para os desenhos da tela (sem Compose, para poder testar): o quadro dos 5 treinadores e o cartão
 * de fim de competição.
 */
object TrainBoard {
    /** Um lugar de treinador. state: active (treinando), done (concluído, falta reclamar), empty (livre), unknown (tela não lida). */
    data class Seat(val role: String, val title: String, val name: String?, val state: String, val fresh: Boolean)

    val ROLES = listOf("GOL" to "Goleiros", "DEF" to "Defesas", "MEI" to "Médios", "ATA" to "Avançados", "UNI" to "Universal")

    fun seats(screen: JSONObject?, players: List<PlayerEntity>, now: Long): List<Seat> {
        val out = ArrayList<Seat>()
        val used = HashSet<String>()
        for ((role, title) in ROLES) {
            val o = screen?.optJSONObject(role)
            if (o == null) {
                out.add(Seat(role, title, null, "unknown", false))
                continue
            }
            val fresh = now - o.optLong("at") <= 24L * 3600000L
            val name = if (o.isNull("name")) null else o.optString("name").ifBlank { null }
            val state = when (o.optString("state")) {
                "active" -> "active"
                "done" -> "done"
                else -> "empty"
            }
            name?.let { used.add(Txt.key(it)) }
            out.add(Seat(role, title, if (state == "empty") null else name, state, fresh))
        }
        // tela de Treinamento ainda não lida: mostra quem o app marcou à mão como treinando, no lugar da posição dele
        val marked = players.filter { it.owner == "MY" && it.training == true && Txt.key(it.name) !in used }
        for (p in marked) {
            val i = out.indexOfFirst { it.state == "unknown" && it.role == p.cat }.takeIf { it >= 0 }
                ?: out.indexOfFirst { it.state == "unknown" && it.role == "UNI" }.takeIf { it >= 0 } ?: continue
            out[i] = out[i].copy(name = p.name, state = "active", fresh = true)
        }
        return out
    }
}

object SeasonCard {
    data class Info(
        val rank: Int?, val points: Int?, val goals: String?, val champion: String?, val value: String?,
        val valueRank: Int?, val board: String?, val fired: Boolean
    )

    /** Junta o resumo estruturado (plano "season") com as frases guardadas (leituras antigas só têm o texto). */
    fun from(season: JSONObject?, board: List<String>, fields: Map<String, StoredField>): Info? {
        var rank = season?.optInt("rank", 0)?.takeIf { it > 0 }
        var points = season?.optInt("points", -1)?.takeIf { it >= 0 }
        var goals = season?.optString("goals")?.ifBlank { null }
        var champion = season?.optString("champion")?.ifBlank { null }
        var value = season?.optString("value")?.ifBlank { null }
        var valueRank = season?.optInt("valueRank", 0)?.takeIf { it > 0 }
        var said: String? = null
        for (t in board) {
            Regex("terminou em (\\d{1,2})º(?: com (\\d{1,3}) pts)?(?: \\(gols ([^)]+)\\))?").find(t)?.let { m ->
                if (rank == null) rank = m.groupValues[1].toIntOrNull()
                if (points == null) points = m.groupValues[2].toIntOrNull()
                if (goals == null) goals = m.groupValues[3].ifBlank { null }
            }
            Regex("Campeão da liga: (.+?)\\.?$").find(t)?.let { m -> if (champion == null) champion = m.groupValues[1].trim() }
            Regex("Valor do plantel no fim: ([^(]+?)(?: \\((\\d{1,2})º da liga\\))?\\.?$").find(t)?.let { m ->
                if (value == null) value = m.groupValues[1].trim()
                if (valueRank == null) valueRank = m.groupValues[2].toIntOrNull()
            }
            if (said == null && t.startsWith("Direção:")) said = t.removePrefix("Direção:").trim().trim('"')
        }
        if (rank == null) rank = fields[K.LEAGUE_POS]?.value?.toIntOrNull()
        if (points == null) points = fields[K.POINTS]?.value?.toIntOrNull()
        if (rank == null && champion == null && value == null && said == null) return null
        val fired = said?.let { Txt.norm(it).contains("demiss") } == true
        return Info(rank, points, goals, champion, value, valueRank, said, fired)
    }

    /** Humor da direção pela frase: -1 bravo, 0 neutro, 1 satisfeito. */
    fun mood(said: String?): Int {
        val n = Txt.norm(said ?: return 0)
        return when {
            listOf("demiss", "desilud", "inaceit", "decepc", "vergonh", "pessim").any { n.contains(it) } -> -1
            listOf("parab", "orgulh", "excelent", "fantast", "satisfeit", "cumprid", "otimo", "superou").any { n.contains(it) } -> 1
            else -> 0
        }
    }
}
