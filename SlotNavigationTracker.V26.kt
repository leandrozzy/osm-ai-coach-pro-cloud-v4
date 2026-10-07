package com.osmaicoach.collector

import java.text.Normalizer
import java.util.Locale

object SlotNavigationTracker {
    data class Result(
        val slotRows: List<List<Int>>,
        val frameToSlot: Map<Int, Int>,
        val hubFrames: Set<Int>,
        val summary: String
    )

    private data class Signature(
        val slotId: Int,
        val tokens: MutableSet<String> = linkedSetOf(),
        var team: String = "NI",
        var competition: String = "NI"
    )

    private data class Visit(
        val rows: MutableList<Int> = mutableListOf(),
        val texts: MutableList<String> = mutableListOf()
    )

    fun looksLikeHub(text: String): Boolean = isSlotsHub(text)

    fun assign(
        session: CaptureSession,
        ocrByIndex: Map<Int, String>,
        slots: List<NativeSlotData>,
        hubCards: List<String> = emptyList()
    ): Result {
        val ordered = session.frames.sortedBy { it.capturedAt }
        if (ordered.isEmpty()) return Result(List(4) { emptyList() }, emptyMap(), emptySet(), "sem telas")

        val signatures = MutableList(4) { i ->
            Signature(i + 1).apply {
                slots.getOrNull(i)?.let { slot ->
                    if (validIdentity(slot.team)) team = slot.team
                    if (validIdentity(slot.competition)) competition = slot.competition
                }
            }
        }

        val usableCards = hubCards.take(4)
        if (usableCards.count { it.isNotBlank() } >= 3) {
            val cardTokens = usableCards.map { raw ->
                tokenize(normalize(raw)).filter { it.length >= 4 && it !in STOP && !it.all(Char::isDigit) }.toSet()
            }
            val frequency = mutableMapOf<String, Int>()
            cardTokens.forEach { set -> set.forEach { token -> frequency[token] = (frequency[token] ?: 0) + 1 } }
            cardTokens.forEachIndexed { index, set ->
                if (index !in 0..3) return@forEachIndexed
                set.filter { frequency[it] == 1 }.take(30).forEach { signatures[index].tokens += it }
            }
        }

        signatures.forEachIndexed { i, sig ->
            slots.getOrNull(i)?.let { slot ->
                if (validIdentity(slot.team)) addIdentityTokens(sig.tokens, slot.team)
                if (validIdentity(slot.competition)) addIdentityTokens(sig.tokens, slot.competition)
            }
        }

        val hubFrames = linkedSetOf<Int>()
        ordered.forEach { frame -> if (isSlotsHub(ocrByIndex[frame.index].orEmpty())) hubFrames += frame.index }

        val visits = mutableListOf<Visit>()
        var current = Visit()
        fun flush() { if (current.rows.isNotEmpty()) visits += current; current = Visit() }

        ordered.forEach { frame ->
            val text = ocrByIndex[frame.index].orEmpty()
            if (frame.index in hubFrames) { flush(); return@forEach }
            if (isNoise(text)) return@forEach
            current.rows += frame.index
            if (text.isNotBlank()) current.texts += text
        }
        flush()

        val frameToSlot = linkedMapOf<Int, Int>()
        val slotRows = MutableList(4) { mutableListOf<Int>() }
        var unresolved = 0

        visits.forEach { visit ->
            val joined = visit.texts.joinToString("\n").take(120000)
            val ranked = signatures.map { it.slotId to score(joined, it) }.sortedByDescending { it.second }
            val bestSlot = ranked.getOrNull(0)?.first ?: 0
            val bestScore = ranked.getOrNull(0)?.second ?: 0
            val secondScore = ranked.getOrNull(1)?.second ?: 0
            val confident = bestSlot in 1..4 && bestScore >= 12 && (bestScore - secondScore >= 6 || bestScore >= 40)

            if (confident) {
                visit.rows.forEach { idx -> frameToSlot[idx] = bestSlot; slotRows[bestSlot - 1] += idx }
            } else unresolved += visit.rows.size
        }

        hubFrames.forEach { frameToSlot[it] = 0 }
        val counts = slotRows.map { it.size }
        val sigCounts = signatures.map { it.tokens.size }

        return Result(
            slotRows = slotRows.map { it.toList() },
            frameToSlot = frameToSlot,
            hubFrames = hubFrames,
            summary = "central=${hubFrames.size} • visitas=${visits.size} • cards=${usableCards.count { it.isNotBlank() }} • assinaturas=${sigCounts.joinToString("/")} • S1=${counts[0]} S2=${counts[1]} S3=${counts[2]} S4=${counts[3]} • não atribuídas=$unresolved"
        )
    }

    private fun score(text: String, signature: Signature): Int {
        val n = normalize(text)
        if (n.isBlank()) return 0
        var result = 0
        val team = normalize(signature.team)
        val competition = normalize(signature.competition)
        if (validIdentity(signature.team) && team.length >= 4 && n.contains(team)) result += 60
        if (validIdentity(signature.competition) && competition.length >= 5 && n.contains(competition)) result += 45
        val visitTokens = tokenize(n)
        result += minOf(signature.tokens.count { it in visitTokens }, 8) * 6
        return result
    }

    private fun isSlotsHub(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false
        if (Regex("""\bslot\s*[1-4]\b""").findAll(n).count() >= 3) return true
        if (n.contains("calendario") || n.contains("fixtures")) return false
        val rounds = Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""").findAll(n).count()
        val hubWords = listOf("liga","league","batalha","battle","divisao","division","manager","treinador","leandrozzy").count { n.contains(it) }
        return rounds >= 3 && hubWords >= 1
    }

    private fun validIdentity(v: String): Boolean {
        val n = normalize(v)
        if (n.isBlank() || n == "ni") return false
        if (Regex("""^slot\s*[1-4]$""").matches(n)) return false
        return n.length >= 3
    }

    private fun addIdentityTokens(out: MutableSet<String>, raw: String) {
        tokenize(normalize(raw)).filter { it.length >= 4 && it !in STOP }.forEach(out::add)
    }

    private fun isNoise(text: String): Boolean {
        val n = normalize(text)
        return listOf("instalar agora","install now","anuncio","advertisement","patrocinado","sponsored","fechar anuncio").any { n.contains(it) }
    }

    private fun tokenize(v: String): Set<String> = v.split(' ').filter { it.length >= 3 }.toSet()

    private fun normalize(v: String): String =
        Normalizer.normalize(v.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9 /.-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val STOP = setOf(
        "jogo","jogar","clube","time","manager","treinador","proximo","rodada","jornada","casa","fora","valor","pontos","slot","liga","league","division","divisao","osm","elenco","calendario","mercado","tatica","treino","jogador","jogadores","classificacao","tabela","temporada"
    )
}
