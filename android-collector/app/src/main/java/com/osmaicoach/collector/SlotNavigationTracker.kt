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

    fun assign(session: CaptureSession, ocrByIndex: Map<Int, String>, slots: List<NativeSlotData>): Result {
        val ordered = session.frames.sortedBy { it.capturedAt }
        if (ordered.isEmpty()) return Result(List(4) { emptyList() }, emptyMap(), emptySet(), "sem telas")

        val signatures = MutableList(4) { i ->
            Signature(i + 1).apply {
                slots.getOrNull(i)?.let { s ->
                    team = s.team
                    competition = s.competition
                    addUsefulTokens(tokens, s.team)
                    addUsefulTokens(tokens, s.competition)
                }
            }
        }

        val hubFrames = linkedSetOf<Int>()
        ordered.forEach { frame ->
            val text = ocrByIndex[frame.index].orEmpty()
            if (isSlotsHub(text)) {
                hubFrames += frame.index
                learnHubSignatures(text, signatures, slots)
            }
        }

        data class Visit(
            val rows: MutableList<Int> = mutableListOf(),
            val texts: MutableList<String> = mutableListOf()
        )

        val visits = mutableListOf<Visit>()
        var current = Visit()

        fun flush() {
            if (current.rows.isNotEmpty()) visits += current
            current = Visit()
        }

        ordered.forEach { frame ->
            val text = ocrByIndex[frame.index].orEmpty()
            if (frame.index in hubFrames) {
                flush()
                return@forEach
            }
            if (isNoise(text)) return@forEach
            current.rows += frame.index
            if (text.isNotBlank()) current.texts += text
        }
        flush()

        val frameToSlot = linkedMapOf<Int, Int>()
        val slotRows = MutableList(4) { mutableListOf<Int>() }
        val used = mutableSetOf<Int>()
        var unresolved = 0

        visits.forEach { visit ->
            val joined = visit.texts.joinToString("\n").take(100000)
            var bestSlot = 0
            var bestScore = 0

            signatures.forEach { sig ->
                val score = score(joined, sig)
                if (score > bestScore) {
                    bestScore = score
                    bestSlot = sig.slotId
                }
            }

            if (bestScore < 3) {
                val n = normalize(joined)
                val direct = slots.mapIndexedNotNull { i, s ->
                    val team = normalize(s.team)
                    val comp = normalize(s.competition)
                    val strong = when {
                        team.length >= 4 && n.contains(team) -> 12
                        comp.length >= 5 && n.contains(comp) -> 7
                        else -> 0
                    }
                    if (strong > 0) (i + 1) to strong else null
                }.maxByOrNull { it.second }
                if (direct != null) {
                    bestSlot = direct.first
                    bestScore = direct.second
                }
            }

            if (bestSlot == 0 || bestScore < 3) {
                val remaining = (1..4).filter { it !in used }
                if (remaining.size == 1) {
                    bestSlot = remaining.first()
                    bestScore = 3
                }
            }

            if (bestSlot in 1..4 && bestScore >= 3) {
                used += bestSlot
                visit.rows.forEach { idx ->
                    frameToSlot[idx] = bestSlot
                    slotRows[bestSlot - 1] += idx
                }
                addUsefulTokens(signatures[bestSlot - 1].tokens, joined)
            } else {
                unresolved += visit.rows.size
            }
        }

        hubFrames.forEach { frameToSlot[it] = 0 }

        val c = slotRows.map { it.size }
        val summary = "central=${hubFrames.size} • visitas=${visits.size} • S1=${c[0]} S2=${c[1]} S3=${c[2]} S4=${c[3]} • não atribuídas=$unresolved"
        return Result(slotRows.map { it.toList() }, frameToSlot, hubFrames, summary)
    }

    private fun score(text: String, sig: Signature): Int {
        val n = normalize(text)
        if (n.isBlank()) return 0
        var score = 0
        val team = normalize(sig.team)
        val comp = normalize(sig.competition)
        if (team.length >= 4 && n.contains(team)) score += 14
        if (comp.length >= 5 && n.contains(comp)) score += 8
        val tokens = tokenize(n)
        score += minOf(sig.tokens.count { it in tokens }, 12)
        return score
    }

    private fun isSlotsHub(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false
        val slotMentions = Regex("\\bslot\\s*[1-4]\\b").findAll(n).count()
        if (slotMentions >= 3) return true
        if (n.contains("calendario") || n.contains("fixtures")) return false

        val rounds = Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").findAll(n).count()
        val leagueWords = listOf(
            "liga","league","batalha","battle","divisao","division",
            "rodada","jornada","manager","treinador"
        ).count { n.contains(it) }

        if (rounds in 3..6 && leagueWords >= 2) return true
        if (rounds >= 3 && n.contains("leandrozzy")) return true
        return false
    }

    private fun learnHubSignatures(text: String, signatures: MutableList<Signature>, slots: List<NativeSlotData>) {
        val lines = text.replace("|", "\n").lines()
            .map { it.trim() }
            .filter { it.length in 2..100 }

        val roundIndices = lines.indices.filter { idx ->
            Regex("\\b\\d{1,2}\\s*/\\s*\\d{1,2}\\b").containsMatchIn(normalize(lines[idx]))
        }

        if (roundIndices.size >= 3) {
            roundIndices.take(4).forEachIndexed { slotIndex, lineIndex ->
                val from = maxOf(0, lineIndex - 5)
                val to = minOf(lines.size - 1, lineIndex + 4)
                val chunk = lines.subList(from, to + 1).joinToString(" ")
                addUsefulTokens(signatures[slotIndex].tokens, chunk)

                if (signatures[slotIndex].team == "NI") {
                    lines.subList(from, lineIndex + 1).asReversed()
                        .firstOrNull { looksLikeName(it) && !looksLikeCompetition(it) }
                        ?.let { signatures[slotIndex].team = it }
                }

                if (signatures[slotIndex].competition == "NI") {
                    lines.subList(from, to + 1)
                        .firstOrNull { looksLikeCompetition(it) }
                        ?.let { signatures[slotIndex].competition = it }
                }
            }
        }

        signatures.forEachIndexed { i, sig ->
            slots.getOrNull(i)?.let {
                if (sig.team == "NI" && it.team != "NI") sig.team = it.team
                if (sig.competition == "NI" && it.competition != "NI") sig.competition = it.competition
                addUsefulTokens(sig.tokens, it.team)
                addUsefulTokens(sig.tokens, it.competition)
            }
        }
    }

    private fun looksLikeCompetition(v: String): Boolean {
        val n = normalize(v)
        return listOf("liga","league","divisao","division","batalha","battle","copa","cup").any { n.contains(it) }
    }

    private fun looksLikeName(v: String): Boolean {
        val n = normalize(v)
        if (n.length !in 3..45 || !n.any { it.isLetter() }) return false
        if (looksLikeCompetition(v)) return false
        return listOf(
            "proximo jogo","calendario","elenco","tatica","mercado",
            "treino","perfil","comunicacoes","diamantes","classificacao"
        ).none { n.contains(it) }
    }

    private fun isNoise(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false
        return listOf(
            "instalar agora","install now","anuncio","advertisement",
            "patrocinado","sponsored","fechar anuncio"
        ).any { n.contains(it) }
    }

    private fun addUsefulTokens(out: MutableSet<String>, raw: String) {
        tokenize(normalize(raw))
            .filter { it.length >= 4 && it !in STOP }
            .take(48)
            .forEach(out::add)
    }

    private fun tokenize(v: String): Set<String> = v.split(' ').filter { it.length >= 3 }.toSet()

    private fun normalize(v: String): String =
        Normalizer.normalize(v.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9 /.-]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val STOP = setOf(
        "jogo","jogar","clube","time","manager","treinador","proximo",
        "rodada","jornada","casa","fora","valor","pontos","slot",
        "liga","league","division","divisao","osm"
    )
}
