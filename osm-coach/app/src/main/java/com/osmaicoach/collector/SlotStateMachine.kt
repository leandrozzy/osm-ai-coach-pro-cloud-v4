package com.osmaicoach.collector

data class SlotIdentity(val slot: Int, val names: Set<String>, val roundNext: Int?)

data class Assignment(val slot: Int?, val confidence: Double, val reason: String)

object SlotMatcher {
    data class Match(val slot: Int, val conf: Double)

    /**
     * Casa o time lido (e a rodada) com um dos slots conhecidos.
     * Nome forte (>= 0.85) manda; rodada sozinha só vale se for única; senão devolve null (não inventa).
     */
    fun match(candidates: List<String>, round: Int?, ids: List<SlotIdentity>): Match? {
        val keys = candidates.map { Txt.key(it) }.filter { it.length >= 3 }
        val scored = ids.map { id ->
            val s = keys.maxOfOrNull { k -> id.names.maxOfOrNull { n -> Txt.sim(k, n) } ?: 0.0 } ?: 0.0
            Pair(id, s)
        }
        val strong = scored.filter { it.second >= 0.85 }.sortedByDescending { it.second }
        if (strong.isNotEmpty()) {
            if (strong.size == 1 || strong[0].second - strong[1].second >= 0.10) {
                val roundOk = round != null && strong[0].first.roundNext == round
                return Match(strong[0].first.slot, if (roundOk) 0.95 else 0.85)
            }
            if (round != null) {
                val tie = strong.filter { it.first.roundNext == round }
                if (tie.size == 1) return Match(tie[0].first.slot, 0.80)
            }
            return null
        }
        if (round != null) {
            val byRound = ids.filter { it.roundNext == round }
            if (byRound.size == 1) return Match(byRound[0].slot, 0.65)
        }
        return null
    }
}

/**
 * Âncora = tela central. Central -> (entrada no slot = pré-jogo) -> telas herdam o slot até voltar à central.
 * Anúncio, SystemUI, launcher e transições (NOISE/NON_OSM) nunca mudam o slot.
 */
class SlotStateMachine {
    var current: Int? = null
        private set
    var awaitingEntry: Boolean = false
        private set
    var hubIdentities: List<SlotIdentity> = emptyList()
        private set

    fun restore(slot: Int?, awaiting: Boolean, hub: List<SlotIdentity>) {
        current = slot
        awaitingEntry = awaiting
        hubIdentities = hub
    }

    fun onHub(cards: List<HubCard>): Assignment {
        hubIdentities = cards.map {
            SlotIdentity(it.slot, setOf(Txt.key(it.team)), it.roundDone?.plus(1))
        }
        current = null
        awaitingEntry = true
        return Assignment(null, 1.0, "central")
    }

    private fun merged(known: List<SlotIdentity>): List<SlotIdentity> {
        val out = LinkedHashMap<Int, SlotIdentity>()
        for (k in known) out[k.slot] = k
        for (h in hubIdentities) {
            val old = out[h.slot]
            out[h.slot] = if (old == null) h else SlotIdentity(h.slot, old.names + h.names, h.roundNext ?: old.roundNext)
        }
        return out.values.toList()
    }

    fun onScreen(
        type: ScreenType,
        teamCandidates: List<String>,
        round: Int?,
        ownerTeam: String?,
        known: List<SlotIdentity>
    ): Assignment {
        when (type) {
            ScreenType.NOISE, ScreenType.NON_OSM ->
                return Assignment(current, if (current != null) 0.5 else 0.0, "ignorada")
            ScreenType.HUB -> return Assignment(null, 1.0, "central")
            ScreenType.PREGAME -> {
                val m = SlotMatcher.match(teamCandidates, round, merged(known))
                if (m != null) {
                    val switched = current != null && current != m.slot
                    current = m.slot
                    awaitingEntry = false
                    return Assignment(m.slot, m.conf, if (switched) "troca sem central" else "pré-jogo")
                }
                val c = current
                return if (c != null) Assignment(c, 0.6, "herdado (pré-jogo ambíguo)")
                else Assignment(null, 0.0, "pré-jogo sem identidade")
            }
            ScreenType.SQUAD, ScreenType.CALENDAR -> {
                val c = current
                if (c != null) return Assignment(c, 0.8, "herdado")
                if (ownerTeam != null) {
                    val m = SlotMatcher.match(listOf(ownerTeam), null, merged(known))
                    if (m != null && m.conf >= 0.85) {
                        current = m.slot
                        awaitingEntry = false
                        return Assignment(m.slot, 0.8, "dono do cabeçalho")
                    }
                }
                return Assignment(null, 0.0, "sem slot")
            }
            else -> {
                val c = current
                return if (c != null) Assignment(c, 0.8, "herdado") else Assignment(null, 0.0, "sem slot")
            }
        }
    }
}
