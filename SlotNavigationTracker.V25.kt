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

    fun assign(
        session: CaptureSession,
        ocrByIndex: Map<Int, String>,
        slots: List<NativeSlotData>
    ): Result {
        val ordered = session.frames.sortedBy { it.capturedAt }
        if (ordered.isEmpty()) {
            return Result(List(4) { emptyList() }, emptyMap(), emptySet(), "sem telas")
        }

        val signatures = MutableList(4) { i ->
            Signature(i + 1).apply {
                slots.getOrNull(i)?.let { slot ->
                    if (validIdentity(slot.team)) {
                        team = slot.team
                        addIdentityTokens(tokens, slot.team)
                    }
                    if (validIdentity(slot.competition)) {
                        competition = slot.competition
                        addIdentityTokens(tokens, slot.competition)
                    }
                }
            }
        }

        val hubFrames = linkedSetOf<Int>()

        ordered.forEach { frame ->
            val text = ocrByIndex[frame.index].orEmpty()
            if (isSlotsHub(text)) {
                hubFrames += frame.index
                learnHubSignatures(text, signatures)
            }
        }

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
        var unresolved = 0

        visits.forEach { visit ->
            val joined = visit.texts.joinToString("\n").take(120000)
            val ranked = signatures
                .map { it.slotId to score(joined, it) }
                .sortedByDescending { it.second }

            val bestSlot = ranked.getOrNull(0)?.first ?: 0
            val bestScore = ranked.getOrNull(0)?.second ?: 0
            val secondScore = ranked.getOrNull(1)?.second ?: 0

            val confident =
                bestSlot in 1..4 &&
                bestScore >= 8 &&
                (bestScore - secondScore >= 4 || bestScore >= 18)

            if (confident) {
                visit.rows.forEach { idx ->
                    frameToSlot[idx] = bestSlot
                    slotRows[bestSlot - 1] += idx
                }
            } else {
                unresolved += visit.rows.size
            }
        }

        hubFrames.forEach { frameToSlot[it] = 0 }

        val counts = slotRows.map { it.size }
        val summary =
            "central=${hubFrames.size} • visitas=${visits.size} • " +
            "S1=${counts[0]} S2=${counts[1]} S3=${counts[2]} S4=${counts[3]} • " +
            "não atribuídas=$unresolved"

        return Result(
            slotRows = slotRows.map { it.toList() },
            frameToSlot = frameToSlot,
            hubFrames = hubFrames,
            summary = summary
        )
    }

    private fun score(text: String, signature: Signature): Int {
        val normalized = normalize(text)
        if (normalized.isBlank()) return 0

        var score = 0

        val team = normalize(signature.team)
        val competition = normalize(signature.competition)

        if (validIdentity(signature.team) && team.length >= 4 && normalized.contains(team)) score += 30
        if (validIdentity(signature.competition) && competition.length >= 5 && normalized.contains(competition)) score += 20

        val visitTokens = tokenize(normalized)
        val overlap = signature.tokens.count { it in visitTokens }
        score += minOf(overlap, 10) * 3

        return score
    }

    private fun isSlotsHub(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false

        val explicitSlots = Regex("""\bslot\s*[1-4]\b""").findAll(n).count()
        if (explicitSlots >= 3) return true

        val rounds = Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""").findAll(n).count()
        if (
            rounds >= 3 &&
            listOf("liga", "league", "batalha", "battle", "manager", "treinador", "leandrozzy")
                .any { n.contains(it) }
        ) return true

        return false
    }

    private fun learnHubSignatures(
        text: String,
        signatures: MutableList<Signature>
    ) {
        val lines = text
            .replace("|", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.length in 2..120 }

        val roundLines = lines.indices.filter { i ->
            Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""").containsMatchIn(normalize(lines[i]))
        }.take(4)

        if (roundLines.size < 3) return

        roundLines.forEachIndexed { slotIndex, anchor ->
            if (slotIndex !in 0..3) return@forEachIndexed

            val from = maxOf(0, anchor - 5)
            val nextAnchor = roundLines.getOrNull(slotIndex + 1) ?: lines.size
            val toExclusive = minOf(lines.size, maxOf(anchor + 1, nextAnchor))
            val chunk = lines.subList(from, toExclusive).joinToString(" ")

            addIdentityTokens(signatures[slotIndex].tokens, chunk)

            if (signatures[slotIndex].team == "NI") {
                lines.subList(from, minOf(lines.size, anchor + 1))
                    .asReversed()
                    .firstOrNull { looksLikeName(it) }
                    ?.let { signatures[slotIndex].team = it }
            }

            if (signatures[slotIndex].competition == "NI") {
                lines.subList(from, toExclusive)
                    .firstOrNull { looksLikeCompetition(it) }
                    ?.let { signatures[slotIndex].competition = it }
            }
        }
    }

    private fun addIdentityTokens(out: MutableSet<String>, raw: String) {
        tokenize(normalize(raw))
            .filter { it.length >= 4 && it !in STOP }
            .take(36)
            .forEach(out::add)
    }

    private fun validIdentity(v: String): Boolean {
        val n = normalize(v)
        if (n == "ni" || n.isBlank()) return false
        if (Regex("""^slot\s*[1-4]$""").matches(n)) return false
        return n.length >= 3
    }

    private fun looksLikeCompetition(value: String): Boolean {
        val n = normalize(value)
        return listOf("liga", "league", "divisao", "division", "batalha", "battle", "copa", "cup")
            .any { n.contains(it) }
    }

    private fun looksLikeName(value: String): Boolean {
        val n = normalize(value)
        if (n.length !in 3..45 || !n.any { it.isLetter() }) return false
        if (looksLikeCompetition(value)) return false
        if (Regex("""^\d+\s*/\s*\d+$""").matches(n)) return false

        return listOf(
            "proximo jogo", "calendario", "elenco", "tatica", "mercado", "treino",
            "perfil", "comunicacoes", "diamantes", "classificacao", "analista de dados"
        ).none { n.contains(it) }
    }

    private fun isNoise(text: String): Boolean {
        val n = normalize(text)
        return listOf(
            "instalar agora", "install now", "anuncio", "advertisement",
            "patrocinado", "sponsored", "fechar anuncio"
        ).any { n.contains(it) }
    }

    private fun tokenize(v: String): Set<String> =
        v.split(' ').filter { it.length >= 3 }.toSet()

    private fun normalize(v: String): String =
        Normalizer.normalize(v.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9 /.-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val STOP = setOf(
        "jogo", "jogar", "clube", "time", "manager", "treinador", "proximo",
        "rodada", "jornada", "casa", "fora", "valor", "pontos", "slot", "liga",
        "league", "division", "divisao", "osm", "elenco", "calendario", "mercado",
        "tatica", "treino", "jogador", "jogadores"
    )
}
