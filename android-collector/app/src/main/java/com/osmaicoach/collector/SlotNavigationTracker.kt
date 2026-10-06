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

    fun assign(
        session: CaptureSession,
        ocrByIndex: Map<Int, String>,
        slots: List<NativeSlotData>
    ): Result {
        val ordered = session.frames.sortedBy { frame -> frame.capturedAt }
        if (ordered.isEmpty()) {
            return Result(List(4) { emptyList() }, emptyMap(), emptySet(), "sem telas")
        }

        val signatures = MutableList(4) { i ->
            Signature(i + 1).apply {
                slots.getOrNull(i)?.let { slot ->
                    team = slot.team
                    competition = slot.competition
                    addUsefulTokens(tokens, slot.team)
                    addUsefulTokens(tokens, slot.competition)
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
        var unresolved = 0

        visits.forEach { visit ->
            val joined = visit.texts.joinToString("\n").take(100000)

            var bestSlot = 0
            var bestScore = 0

            signatures.forEach { signature ->
                val currentScore = score(joined, signature)
                if (currentScore > bestScore) {
                    bestScore = currentScore
                    bestSlot = signature.slotId
                }
            }

            if (bestScore < 3) {
                val normalized = normalize(joined)

                val direct = slots.mapIndexedNotNull { index, slot ->
                    val team = normalize(slot.team)
                    val competition = normalize(slot.competition)

                    val strongScore = when {
                        team.length >= 3 && normalized.contains(team) -> 16
                        competition.length >= 4 && normalized.contains(competition) -> 10
                        else -> 0
                    }

                    if (strongScore > 0) (index + 1) to strongScore else null
                }.maxByOrNull { pair -> pair.second }

                if (direct != null) {
                    bestSlot = direct.first
                    bestScore = direct.second
                }
            }

            val ranked = signatures
                .map { signature -> signature.slotId to score(joined, signature) }
                .sortedByDescending { pair -> pair.second }

            val secondScore = ranked.getOrNull(1)?.second ?: 0
            val confident =
                bestSlot in 1..4 &&
                bestScore >= 2 &&
                (bestScore - secondScore >= 1 || bestScore >= 10)

            if (confident) {
                visit.rows.forEach { index ->
                    frameToSlot[index] = bestSlot
                    slotRows[bestSlot - 1] += index
                }

                addUsefulTokens(signatures[bestSlot - 1].tokens, joined)
            } else {
                unresolved += visit.rows.size
            }
        }

        hubFrames.forEach { frameIndex ->
            frameToSlot[frameIndex] = 0
        }

        val counts = slotRows.map { rows -> rows.size }

        val summary =
            "central=${hubFrames.size} • visitas=${visits.size} • " +
            "S1=${counts[0]} S2=${counts[1]} S3=${counts[2]} S4=${counts[3]} • " +
            "não atribuídas=$unresolved"

        return Result(
            slotRows = slotRows.map { rows -> rows.toList() },
            frameToSlot = frameToSlot,
            hubFrames = hubFrames,
            summary = summary
        )
    }

    private fun score(text: String, signature: Signature): Int {
        val normalized = normalize(text)
        if (normalized.isBlank()) return 0

        var result = 0

        val team = normalize(signature.team)
        val competition = normalize(signature.competition)

        if (team.length >= 4 && normalized.contains(team)) result += 14
        if (competition.length >= 5 && normalized.contains(competition)) result += 8

        val tokens = tokenize(normalized)
        result += minOf(signature.tokens.count { token -> token in tokens }, 12) * 2

        return result
    }

    private fun isSlotsHub(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return false

        val slotMentions =
            Regex("""\bslot\s*[1-4]\b""")
                .findAll(normalized)
                .count()

        if (slotMentions >= 3) return true

        if (
            normalized.contains("calendario") ||
            normalized.contains("fixtures")
        ) {
            return false
        }

        val rounds =
            Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")
                .findAll(normalized)
                .count()

        val leagueWords = listOf(
            "liga",
            "league",
            "batalha",
            "battle",
            "divisao",
            "division",
            "rodada",
            "jornada",
            "manager",
            "treinador"
        ).count { word -> normalized.contains(word) }

        if (rounds in 3..8 && leagueWords >= 1) return true
        if (rounds >= 3 && normalized.contains("leandrozzy")) return true

        if (
            rounds >= 3 &&
            listOf("slot", "manager", "treinador", "liga", "batalha")
                .any { word -> normalized.contains(word) }
        ) {
            return true
        }

        return false
    }

    private fun learnHubSignatures(
        text: String,
        signatures: MutableList<Signature>,
        slots: List<NativeSlotData>
    ) {
        val normalized = normalize(text)

        val anchors =
            Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")
                .findAll(normalized)
                .take(4)
                .toList()

        if (anchors.size >= 3) {
            anchors.forEachIndexed { slotIndex, anchor ->
                if (slotIndex > 3) return@forEachIndexed

                val left =
                    if (slotIndex == 0) {
                        0
                    } else {
                        (
                            (
                                anchors[slotIndex - 1].range.last +
                                anchor.range.first
                            ) / 2
                        ).coerceAtLeast(0)
                    }

                val right =
                    if (slotIndex == anchors.lastIndex) {
                        normalized.length
                    } else {
                        (
                            (
                                anchor.range.last +
                                anchors[slotIndex + 1].range.first
                            ) / 2
                        ).coerceAtMost(normalized.length)
                    }

                if (right > left) {
                    val chunk = normalized.substring(left, right)
                    addUsefulTokens(
                        signatures[slotIndex].tokens,
                        chunk
                    )
                }
            }
        }

        val lines =
            text
                .replace("|", "\n")
                .lines()
                .map { line -> line.trim() }
                .filter { line -> line.length in 2..100 }

        val roundIndices =
            lines.indices.filter { index ->
                Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")
                    .containsMatchIn(
                        normalize(lines[index])
                    )
            }

        if (roundIndices.size >= 3) {
            roundIndices
                .take(4)
                .forEachIndexed { slotIndex, lineIndex ->
                    val from = maxOf(0, lineIndex - 5)
                    val to = minOf(lines.size - 1, lineIndex + 4)

                    val chunk =
                        lines
                            .subList(from, to + 1)
                            .joinToString(" ")

                    addUsefulTokens(
                        signatures[slotIndex].tokens,
                        chunk
                    )

                    if (signatures[slotIndex].team == "NI") {
                        lines
                            .subList(from, lineIndex + 1)
                            .asReversed()
                            .firstOrNull { line ->
                                looksLikeName(line) &&
                                !looksLikeCompetition(line)
                            }
                            ?.let { teamName ->
                                signatures[slotIndex].team = teamName
                            }
                    }

                    if (signatures[slotIndex].competition == "NI") {
                        lines
                            .subList(from, to + 1)
                            .firstOrNull { line ->
                                looksLikeCompetition(line)
                            }
                            ?.let { competitionName ->
                                signatures[slotIndex].competition =
                                    competitionName
                            }
                    }
                }
        }

        signatures.forEachIndexed { index, signature ->
            slots.getOrNull(index)?.let { slot ->
                if (
                    signature.team == "NI" &&
                    slot.team != "NI"
                ) {
                    signature.team = slot.team
                }

                if (
                    signature.competition == "NI" &&
                    slot.competition != "NI"
                ) {
                    signature.competition =
                        slot.competition
                }

                addUsefulTokens(
                    signature.tokens,
                    slot.team
                )

                addUsefulTokens(
                    signature.tokens,
                    slot.competition
                )
            }
        }
    }

    private fun looksLikeCompetition(value: String): Boolean {
        val normalized = normalize(value)

        return listOf(
            "liga",
            "league",
            "divisao",
            "division",
            "batalha",
            "battle",
            "copa",
            "cup"
        ).any { word ->
            normalized.contains(word)
        }
    }

    private fun looksLikeName(value: String): Boolean {
        val normalized = normalize(value)

        if (
            normalized.length !in 3..45 ||
            !normalized.any { char -> char.isLetter() }
        ) {
            return false
        }

        if (looksLikeCompetition(value)) return false

        return listOf(
            "proximo jogo",
            "calendario",
            "elenco",
            "tatica",
            "mercado",
            "treino",
            "perfil",
            "comunicacoes",
            "diamantes",
            "classificacao"
        ).none { blocked ->
            normalized.contains(blocked)
        }
    }

    private fun isNoise(text: String): Boolean {
        val normalized = normalize(text)
        if (normalized.isBlank()) return false

        return listOf(
            "instalar agora",
            "install now",
            "anuncio",
            "advertisement",
            "patrocinado",
            "sponsored",
            "fechar anuncio"
        ).any { noise ->
            normalized.contains(noise)
        }
    }

    private fun addUsefulTokens(
        output: MutableSet<String>,
        raw: String
    ) {
        tokenize(normalize(raw))
            .filter { token ->
                token.length >= 4 &&
                token !in STOP
            }
            .take(48)
            .forEach { token ->
                output.add(token)
            }
    }

    private fun tokenize(value: String): Set<String> =
        value
            .split(' ')
            .filter { token -> token.length >= 3 }
            .toSet()

    private fun normalize(value: String): String =
        Normalizer
            .normalize(
                value.lowercase(Locale.ROOT),
                Normalizer.Form.NFD
            )
            .replace(
                Regex("""\p{Mn}+"""),
                ""
            )
            .replace(
                Regex("""[^a-z0-9 /.-]+"""),
                " "
            )
            .replace(
                Regex("""\s+"""),
                " "
            )
            .trim()

    private val STOP = setOf(
        "jogo",
        "jogar",
        "clube",
        "time",
        "manager",
        "treinador",
        "proximo",
        "rodada",
        "jornada",
        "casa",
        "fora",
        "valor",
        "pontos",
        "slot",
        "liga",
        "league",
        "division",
        "divisao",
        "osm"
    )
}
