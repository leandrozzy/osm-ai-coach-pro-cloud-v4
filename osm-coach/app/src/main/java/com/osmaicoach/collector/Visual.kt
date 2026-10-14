package com.osmaicoach.collector

import org.json.JSONObject

/**
 * Dados prontos para os desenhos da tela (sem Compose, para poder testar): o quadro dos 5 treinadores e o cartão
 * de fim de competição.
 */
object TrainBoard {
    /**
     * Um lugar de treinador. state: active (treinando), done (concluído, falta reclamar), empty (livre), unknown (tela não
     * lida). key = jogador do meu elenco que está nele (quando o nome bate).
     */
    data class Seat(
        val role: String, val title: String, val name: String?, val state: String, val fresh: Boolean,
        val key: String? = null, val at: Long = 0L
    )

    val ROLES = listOf("GOL" to "Goleiros", "DEF" to "Defesas", "MEI" to "Médios", "ATA" to "Avançados", "UNI" to "Universal")

    /**
     * Nome como a tela de Treinamento escreve: em MAIÚSCULAS ("19 BUKAYO SAKA"). Nome em letra normal ("Guéhi") vem de
     * outra coisa na mesma posição (lista para escolher o jogador, cartão de outra tela) e não vale.
     */
    fun plausible(name: String?): Boolean {
        val t = name?.trim() ?: return false
        if (t.firstOrNull()?.isDigit() == true) return true
        val letters = t.filter { it.isLetter() }
        if (letters.length < 3) return false
        return letters.count { it.isUpperCase() } >= letters.length * 0.8
    }

    /** Jogador do meu elenco pelo nome da tela ("FERMÍN LÓPEZ" -> "F. López", "BUKAYO SAKA" -> "Saka"). */
    fun match(mine: List<PlayerEntity>, name: String, role: String): PlayerEntity? {
        val words = name.trim().replace(Regex("^\\d+\\s*"), "").split(Regex("[\\s]+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val full = Txt.key(words.joinToString(" "))
        val last = Txt.key(words.last())
        val first = Txt.key(words.first()).take(1)
        fun cat(p: PlayerEntity) = role == "UNI" || p.cat == null || p.cat == role
        val hits = mine.filter { p ->
            val k = Txt.key(p.name)
            val pw = p.name.trim().split(Regex("[\\s]+")).filter { it.isNotBlank() }
            val pLast = Txt.key(pw.lastOrNull() ?: "")
            // "F. López": inicial + sobrenome
            val pInit = if (pw.size >= 2 && Txt.key(pw.first()).length == 1) Txt.key(pw.first()) else null
            k.length >= 3 && (
                k == full || k == last || (k.length >= 4 && full.endsWith(k)) ||
                    (pInit != null && pInit == first && pLast.length >= 3 && (pLast == last || Txt.sim(pLast, last) >= 0.8)) ||
                    (pInit == null && Txt.sim(k, last) >= 0.85) ||
                    (full.length >= 6 && Txt.sim(k, full) >= 0.85)
                )
        }
        return hits.firstOrNull { cat(it) } ?: hits.singleOrNull()
    }

    private fun pretty(n: String): String =
        n.trim().replace(Regex("^\\d+\\s*"), "").lowercase().split(" ").joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    fun seats(screen: JSONObject?, players: List<PlayerEntity>, now: Long): List<Seat> {
        val mine = players.filter { it.owner == "MY" }
        val out = ArrayList<Seat>()
        for ((role, title) in ROLES) {
            val o = screen?.optJSONObject(role)
            if (o == null) {
                out.add(Seat(role, title, null, "unknown", false))
                continue
            }
            val at = o.optLong("at")
            val fresh = now - at <= 24L * 3600000L
            val raw = if (o.isNull("name")) null else o.optString("name").ifBlank { null }
            val state = when (o.optString("state")) {
                "active" -> "active"
                "done" -> "done"
                else -> "empty"
            }
            if (state != "empty" && !plausible(raw)) {
                // nome que não é da tela de Treinamento: não dá para saber quem está nesse treinador
                out.add(Seat(role, title, null, "unknown", false, null, at))
                continue
            }
            val p = if (state == "empty" || raw == null) null else match(mine, raw, role)
            out.add(Seat(role, title, if (state == "empty") null else (p?.name ?: raw?.let { pretty(it) }), state, fresh, p?.nameKey, at))
        }
        // o mesmo jogador em dois treinadores: vale a leitura mais nova (a outra é de antes de ele trocar)
        for (i in out.indices) {
            val a = out[i]
            val ka = a.key ?: a.name?.let { Txt.key(it) } ?: continue
            if (a.state == "unknown" || a.state == "empty") continue
            for (j in out.indices) {
                if (i == j) continue
                val b = out[j]
                val kb = b.key ?: b.name?.let { Txt.key(it) } ?: continue
                if (b.state == "unknown" || b.state == "empty") continue
                if (ka == kb && b.at > a.at) {
                    out[i] = a.copy(name = null, state = "unknown", fresh = false, key = null)
                    break
                }
            }
        }
        // tela de Treinamento ainda não lida: mostra quem o app marcou à mão como treinando, no lugar da posição dele
        val used = out.mapNotNull { it.key }.toSet()
        val marked = mine.filter { it.training == true && it.nameKey !in used }
        if (screen == null) {
            for (p in marked) {
                val i = out.indexOfFirst { it.state == "unknown" && it.role == p.cat }.takeIf { it >= 0 }
                    ?: out.indexOfFirst { it.state == "unknown" && it.role == "UNI" }.takeIf { it >= 0 } ?: continue
                out[i] = out[i].copy(name = p.name, state = "active", fresh = true, key = p.nameKey)
            }
        }
        return out
    }

    /** Jogadores treinando agora (cronômetro lido nas últimas 24 h). */
    fun activeKeys(seats: List<Seat>): Set<String> = seats.filter { it.state == "active" && it.fresh }.mapNotNull { it.key }.toSet()
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
