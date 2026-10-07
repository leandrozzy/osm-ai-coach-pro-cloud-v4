package com.osmaicoach.collector

data class StoredField(val value: String, val conf: Double, val updatedAt: Long)

object FieldMerge {
    private val VOLATILE = setOf(
        K.MY_STRENGTH, K.RIVAL_STRENGTH, K.MY_VALUE, K.RIVAL_VALUE, K.CASH, K.ROUND, K.MATCH_AT,
        K.LEAGUE_POS, K.POINTS, K.SELLING, K.ROUND_DONE, K.HOME, K.MY_GOL, K.MY_DEF, K.MY_MID, K.MY_ATK,
        K.RIVAL_GOL, K.RIVAL_DEF, K.RIVAL_MID, K.RIVAL_ATK, K.REFEREE
    )

    fun known(v: String?): Boolean {
        if (v == null) return false
        val t = v.trim()
        return t.isNotEmpty() && !t.equals("NI", ignoreCase = true) && t != "-" && t != "--"
    }

    /**
     * Devolve o novo valor a gravar, ou null se nada deve mudar.
     * - NI/vazio nunca sobrescreve nada.
     * - Valor igual com mesma confiança não gera escrita (idempotente).
     * - Valor diferente só vence com confiança maior (ou, em campos que mudam com o tempo, confiança boa).
     */
    fun merge(key: String, old: StoredField?, incoming: Reading, now: Long): StoredField? {
        if (!known(incoming.value)) return null
        val v = incoming.value.trim()
        if (old == null || !known(old.value)) return StoredField(v, incoming.conf, now)
        if (old.value == v) return if (incoming.conf > old.conf) StoredField(v, incoming.conf, now) else null
        val replace = if (key in VOLATILE) {
            incoming.conf >= 0.7 && incoming.conf >= old.conf - 0.1
        } else {
            incoming.conf > old.conf
        }
        return if (replace) StoredField(v, incoming.conf, now) else null
    }
}

object Completeness {
    data class Item(val key: String, val label: String)

    val ITEMS = listOf(
        Item(K.TEAM, "Meu time"),
        Item(K.COMPETITION, "Competição"),
        Item(K.ROUND, "Rodada"),
        Item(K.MATCH_AT, "Data/hora do jogo"),
        Item(K.HOME, "Casa/fora"),
        Item(K.RIVAL_TEAM, "Próximo adversário"),
        Item(K.RIVAL_HUMAN, "Humano/CPU"),
        Item(K.MY_STRENGTH, "Minha força"),
        Item(K.RIVAL_STRENGTH, "Força do rival"),
        Item(K.MY_VALUE, "Valor do meu elenco"),
        Item(K.RIVAL_VALUE, "Valor do elenco rival"),
        Item(K.RIVAL_FORMATION, "Formação rival"),
        Item(K.RIVAL_PLAN, "Plano de jogo rival"),
        Item(K.RIVAL_MARKING, "Marcação rival"),
        Item(K.RIVAL_OFFSIDE, "Impedimento rival"),
        Item(K.RIVAL_TACKLE, "Desarme rival"),
        Item(K.RIVAL_SECRET, "Treino secreto rival"),
        Item(K.RIVAL_CAMP, "Campo de treinamento rival"),
        Item(K.REFEREE, "Árbitro"),
        Item(K.STADIUM, "Nível do estádio rival"),
        Item(K.MY_STADIUM, "Meu estádio"),
        Item(K.MY_GOL, "Meu setor GOL"),
        Item(K.MY_DEF, "Meu setor DEF"),
        Item(K.MY_MID, "Meu setor MEI"),
        Item(K.MY_ATK, "Meu setor ATA"),
        Item(K.RIVAL_GOL, "Setor GOL do rival"),
        Item(K.RIVAL_DEF, "Setor DEF do rival"),
        Item(K.RIVAL_MID, "Setor MEI do rival"),
        Item(K.RIVAL_ATK, "Setor ATA do rival")
    )

    /** Só existem quando o rival é humano (time de CPU não tem apelido nem bônus de login). */
    val HUMAN_ONLY = listOf(
        Item(K.RIVAL_NICK, "Apelido do rival (humano)"),
        Item(K.RIVAL_LOGIN_BONUS, "Bônus de login do rival (humano)")
    )

    data class Result(val known: List<String>, val missing: List<String>, val percent: Int, val missingItems: List<Item> = emptyList())

    /** Calculado somente por campos realmente preenchidos; nunca por quantidade de frames. */
    fun compute(fields: Map<String, StoredField>, squadCount: Int, calendarCount: Int, marketSeen: Boolean, calendarTotal: Int? = null): Result {
        val known = ArrayList<String>()
        val missing = ArrayList<String>()
        val missingItems = ArrayList<Item>()
        val human = fields[K.RIVAL_HUMAN]?.value == "Sim"
        for (it in (if (human) ITEMS + HUMAN_ONLY else ITEMS)) {
            if (FieldMerge.known(fields[it.key]?.value)) known.add(it.label) else {
                missing.add(it.label)
                missingItems.add(it)
            }
        }
        if (squadCount >= 16) known.add("Meu elenco (16+ jogadores)") else missing.add("Meu elenco (16+ jogadores)")
        val need = calendarTotal ?: 6
        if (calendarCount >= need) known.add("Calendário completo")
        else missing.add("Calendário completo ($calendarCount de ${calendarTotal ?: "?"} rodadas)")
        if (marketSeen) known.add("Mercado") else missing.add("Mercado")
        val total = known.size + missing.size
        val pct = if (total == 0) 0 else (known.size * 100) / total
        return Result(known, missing, pct, missingItems)
    }
}

/** Plano de mercado determinístico (funciona sem IA). Metas: 4 ATA, 6 MEI, 6 DEF, 2 GOL; máx. 4 à venda. */
object MarketPlanner {
    val TARGET = linkedMapOf("ATA" to 4, "MEI" to 6, "DEF" to 6, "GOL" to 2)
    const val MAX_SELLING = 4

    data class Need(val cat: String, val have: Int, val target: Int) {
        val missing: Int get() = (target - have).coerceAtLeast(0)
        val surplus: Int get() = (have - target).coerceAtLeast(0)
    }

    fun needs(catCounts: Map<String, Int>): List<Need> =
        TARGET.map { (cat, target) -> Need(cat, catCounts[cat] ?: 0, target) }

    /** Quantos jogadores ainda posso colocar à venda, dado "x/4" lido na tela. */
    fun sellSlotsLeft(selling: String?): Int {
        val m = Regex("^(\\d)\\s*/\\s*(\\d)$").find(selling?.trim() ?: "") ?: return MAX_SELLING
        val used = m.groupValues[1].toInt()
        val max = m.groupValues[2].toInt()
        return (max - used).coerceAtLeast(0)
    }
}
