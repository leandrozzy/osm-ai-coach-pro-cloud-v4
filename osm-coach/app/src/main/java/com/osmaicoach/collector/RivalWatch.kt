package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject

/**
 * Vigia o rival depois que a tática foi gerada: guarda o "retrato" do que foi usado (formação, estilo, marcação,
 * fora-de-jogo, desarme, treino secreto, força e árbitro) e, quando uma leitura nova do OSM muda algo disso,
 * diz o que mudou, de quanto para quanto e por que vale gerar a tática de novo.
 */
object RivalWatch {
    data class Change(val key: String, val label: String, val from: String, val to: String, val why: String)

    val KEYS = listOf(
        K.RIVAL_FORMATION, K.RIVAL_PLAN, K.RIVAL_MARKING, K.RIVAL_OFFSIDE, K.RIVAL_TACKLE,
        K.RIVAL_SECRET, K.RIVAL_STRENGTH, K.REFEREE
    )

    private val LABEL = mapOf(
        K.RIVAL_FORMATION to "Formação do rival", K.RIVAL_PLAN to "Estilo de jogo do rival",
        K.RIVAL_MARKING to "Marcação do rival", K.RIVAL_OFFSIDE to "Fora-de-jogo do rival",
        K.RIVAL_TACKLE to "Desarme do rival", K.RIVAL_SECRET to "Treino secreto do rival",
        K.RIVAL_STRENGTH to "Força do rival", K.REFEREE to "Árbitro"
    )

    /** Retrato dos dados do rival na hora de gerar a tática. */
    fun basis(f: Map<String, StoredField>): JSONObject {
        val j = JSONObject()
        for (k in KEYS) f[k]?.value?.takeIf { FieldMerge.known(it) }?.let { j.put(k, it) }
        return j
    }

    private fun why(k: String, from: String, to: String): String = when (k) {
        K.RIVAL_FORMATION -> {
            val a = Formations.lines(from)
            val b = Formations.lines(to)
            val fa = a.lastOrNull()
            val fb = b.lastOrNull()
            when {
                fa != null && fb != null && fb > fa -> "agora ele joga com $fb atacante(s) (antes $fa): sua defesa e a marcação precisam de reforço"
                fa != null && fb != null && fb < fa -> "agora ele tem menos atacantes ($fb) e mais gente atrás: dá para atacar com mais gente"
                a.isNotEmpty() && b.isNotEmpty() && a.first() != b.first() -> "mudou a linha de defesa dele (${a.first()} → ${b.first()} defensores): muda o caminho do seu ataque"
                else -> "a distribuição dele no campo mudou: o meio-campo e a marcação foram pensados para a formação antiga"
            }
        }
        K.RIVAL_PLAN -> when (Osm.style(to)) {
            "Contra-ataque" -> "agora ele espera e sai rápido: cuidado com estilo muito ofensivo e com o contra-ataque contra contra-ataque"
            "Bola longa" -> "agora ele lança por cima: linha de impedimento e defesa alta ficam arriscadas"
            "Jogar pelas alas" -> "agora ele ataca pelos lados: os laterais e a marcação precisam cobrir as alas"
            "Remate à vista" -> "agora ele chuta de longe: não deixe espaço na entrada da área"
            else -> "o jeito dele de atacar mudou: a sua resposta foi montada para o estilo anterior"
        }
        K.RIVAL_MARKING -> if (Osm.marking(to) == "Homem-a-homem") "ele vai colar nos seus jogadores: passes rápidos e movimentação rendem mais"
        else "ele passou a marcar por zona: há mais espaço entre as linhas dele"
        K.RIVAL_OFFSIDE -> if (to == "Sim") "ele adiantou a linha (armadilha de impedimento): bola longa e contra-ataque nas costas dele ficam fortes"
        else "ele deixou de fazer a linha de impedimento: a defesa dele fica mais recuada"
        K.RIVAL_TACKLE -> "ele mudou a força nas divididas: mexe em faltas, cartões e no seu desarme ideal"
        K.RIVAL_SECRET -> if (to == "Sim") "ele fez treino secreto: a tática dele está escondida e a tática segura é a melhor resposta"
        else "o treino secreto acabou: dá para montar a tática sob medida de novo"
        K.RIVAL_STRENGTH -> {
            val d = (to.toIntOrNull() ?: 0) - (from.toIntOrNull() ?: 0)
            if (d > 0) "ele ficou $d ponto(s) mais forte (escalou melhor ou treinou): a diferença de força mudou"
            else "ele ficou ${-d} ponto(s) mais fraco: dá para arriscar mais"
        }
        K.REFEREE -> "mudou o árbitro do jogo: o desarme permitido é outro (${Referee.tackle(to) ?: "?"})"
        else -> "dado do rival mudou"
    }

    /** O que mudou entre o retrato da tática e os dados de agora (força só conta a partir de 3 pontos). */
    fun diff(basis: JSONObject?, f: Map<String, StoredField>): List<Change> {
        if (basis == null) return emptyList()
        val out = ArrayList<Change>()
        for (k in KEYS) {
            if (!basis.has(k)) continue
            val a = basis.optString(k)
            val b = f[k]?.value?.takeIf { FieldMerge.known(it) } ?: continue
            val same = when (k) {
                K.RIVAL_FORMATION -> Formations.canonical(a) == Formations.canonical(b)
                K.RIVAL_PLAN -> Osm.style(a) == Osm.style(b)
                K.RIVAL_MARKING -> Osm.marking(a) == Osm.marking(b)
                K.RIVAL_TACKLE -> Osm.tackle(a) == Osm.tackle(b)
                K.REFEREE -> Referee.norm(a) == Referee.norm(b)
                K.RIVAL_STRENGTH -> Math.abs((a.toIntOrNull() ?: 0) - (b.toIntOrNull() ?: 0)) < 3
                else -> a == b
            }
            if (!same) out.add(Change(k, LABEL[k] ?: k, a, b, why(k, a, b)))
        }
        return out
    }

    fun toJson(list: List<Change>, at: Long): JSONObject {
        val arr = JSONArray()
        for (c in list) arr.put(JSONObject().put("k", c.key).put("label", c.label).put("from", c.from).put("to", c.to).put("why", c.why))
        return JSONObject().put("at", at).put("changes", arr)
    }

    fun fromJson(j: JSONObject?): List<Change> {
        val arr = j?.optJSONArray("changes") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Change(it.optString("k"), it.optString("label"), it.optString("from"), it.optString("to"), it.optString("why")) }
        }
    }

    /** Uma linha para notificação e aviso por cima do OSM. */
    fun headline(list: List<Change>): String =
        list.take(3).joinToString(" • ") { it.label.removeSuffix(" do rival") + ": " + it.from + " → " + it.to }
}
