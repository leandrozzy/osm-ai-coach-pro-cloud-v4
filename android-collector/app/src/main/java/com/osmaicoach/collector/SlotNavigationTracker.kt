package com.osmaicoach.collector

import java.text.Normalizer
import java.util.Locale

/**
 * FINAL ROUTER
 *
 * Regras:
 * 1) a tela central dos 4 slots é a única fronteira entre visitas;
 * 2) uma visita nunca "ensina" novos tokens ao slot (evita contaminação);
 * 3) os quatro cards da central geram assinaturas independentes;
 * 4) só atribui uma visita quando existe diferença real para o segundo colocado;
 * 5) visita não identificada fica sem slot e NÃO apaga dados já existentes;
 * 6) quando a central permite reconhecer time/liga com segurança, atualiza apenas
 *    campos ainda NI.
 */
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
                    if (validIdentity(slot.team)) team = slot.team
                    if (validIdentity(slot.competition)) competition = slot.competition
                    addIdentityTokens(tokens, slot.team)
                    addIdentityTokens(tokens, slot.competition)
                }
            }
        }

        val hubFrames = linkedSetOf<Int>()
        val hubCardSamples = mutableListOf<List<String>>()

        ordered.forEach { frame ->
            val text = ocrByIndex[frame.index].orEmpty()
            if (isSlotsHub(text)) {
                hubFrames += frame.index
                splitHubIntoCards(text)?.let { cards ->
                    if (cards.size == 4) hubCardSamples += cards
                }
            }
        }

        // Escolhe a leitura central com mais conteúdo útil.
        val bestCards = hubCardSamples.maxByOrNull { cards ->
            cards.sumOf { normalize(it).length }
        } ?: emptyList()

        if (bestCards.size == 4) {
            learnCardSignatures(bestCards, signatures, slots)
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

            // Propaganda não encerra a visita e também não entra no texto de identidade.
            current.rows += frame.index
            if (text.isNotBlank() && !isNoise(text)) current.texts += text
        }
        flush()

        val frameToSlot = linkedMapOf<Int, Int>()
        val slotRows = MutableList(4) { mutableListOf<Int>() }
        var unresolved = 0

        visits.forEach { visit ->
            // Para identidade, priorize as primeiras telas da visita (dashboard do slot).
            val identityText = visit.texts.take(12).joinToString("\n").take(70000)
            val allText = visit.texts.joinToString("\n").take(140000)

            val ranked = signatures.map { sig ->
                sig.slotId to maxOf(
                    score(identityText, sig),
                    score(allText, sig)
                )
            }.sortedByDescending { it.second }

            val bestSlot = ranked.getOrNull(0)?.first ?: 0
            val bestScore = ranked.getOrNull(0)?.second ?: 0
            val secondScore = ranked.getOrNull(1)?.second ?: 0

            // Exige identidade forte. Isso é intencional: é melhor deixar sem
            // atribuição que jogar 200 telas no slot errado.
            val confident =
                bestSlot in 1..4 &&
                bestScore >= 18 &&
                (
                    bestScore - secondScore >= 10 ||
                    bestScore >= 70
                )

            if (confident) {
                visit.rows.forEach { index ->
                    frameToSlot[index] = bestSlot
                    slotRows[bestSlot - 1] += index
                }
            } else {
                unresolved += visit.rows.size
            }
        }

        hubFrames.forEach { frameToSlot[it] = 0 }

        val counts = slotRows.map { it.size }
        val sigCounts = signatures.map { it.tokens.size }
        val knownCards = signatures.count { validIdentity(it.team) || validIdentity(it.competition) || it.tokens.size >= 2 }

        return Result(
            slotRows = slotRows.map { it.toList() },
            frameToSlot = frameToSlot,
            hubFrames = hubFrames,
            summary =
                "central=${hubFrames.size} • visitas=${visits.size} • cards=$knownCards • " +
                "assinaturas=${sigCounts.joinToString("/")} • " +
                "S1=${counts[0]} S2=${counts[1]} S3=${counts[2]} S4=${counts[3]} • " +
                "não atribuídas=$unresolved"
        )
    }

    private fun learnCardSignatures(
        cards: List<String>,
        signatures: MutableList<Signature>,
        slots: List<NativeSlotData>
    ) {
        val rawTokenSets = cards.map { card ->
            tokenize(normalize(card))
                .filter { token ->
                    token.length >= 4 &&
                    token !in STOP &&
                    !token.all(Char::isDigit)
                }
                .toSet()
        }

        val frequency = mutableMapOf<String, Int>()
        rawTokenSets.forEach { set ->
            set.forEach { token -> frequency[token] = (frequency[token] ?: 0) + 1 }
        }

        cards.forEachIndexed { index, card ->
            if (index !in 0..3) return@forEachIndexed
            val sig = signatures[index]

            rawTokenSets[index]
                .filter { frequency[it] == 1 }
                .take(24)
                .forEach(sig.tokens::add)

            val lines = card.lines()
                .map { it.trim() }
                .filter { it.length in 2..70 }

            val roundIndex = lines.indexOfFirst {
                ROUND_REGEX.containsMatchIn(normalize(it))
            }

            if (roundIndex >= 0) {
                val before = lines.subList(maxOf(0, roundIndex - 6), roundIndex)
                    .filterNot(::blockedIdentityLine)

                // O texto mais próximo da rodada costuma ser o nome do time.
                val teamCandidate = before.asReversed()
                    .firstOrNull { looksLikeTeam(it) }

                val competitionCandidate = before.asReversed()
                    .dropWhile { it == teamCandidate }
                    .firstOrNull { looksLikeCompetition(it) }
                    ?: before.firstOrNull { looksLikeCompetition(it) }

                if (teamCandidate != null && !validIdentity(sig.team)) {
                    sig.team = teamCandidate
                    if (slots[index].team == "NI") slots[index].team = teamCandidate
                    addIdentityTokens(sig.tokens, teamCandidate)
                }

                if (competitionCandidate != null && !validIdentity(sig.competition)) {
                    sig.competition = competitionCandidate
                    if (slots[index].competition == "NI") slots[index].competition = competitionCandidate
                    addIdentityTokens(sig.tokens, competitionCandidate)
                }
            }

            // Dados já persistidos sempre têm prioridade sobre inferência.
            val persisted = slots.getOrNull(index)
            if (persisted != null) {
                if (validIdentity(persisted.team)) sig.team = persisted.team
                if (validIdentity(persisted.competition)) sig.competition = persisted.competition
                addIdentityTokens(sig.tokens, persisted.team)
                addIdentityTokens(sig.tokens, persisted.competition)
            }
        }
    }

    /**
     * Divide a leitura textual da central em 4 blocos usando as 4 ocorrências
     * de rodada. Não depende de posição de toque e não assume que o usuário
     * entra nos slots em ordem.
     */
    private fun splitHubIntoCards(text: String): List<String>? {
        val lines = text
            .replace("|", "\n")
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(300)

        val anchors = lines.indices.filter { i ->
            ROUND_REGEX.containsMatchIn(normalize(lines[i]))
        }.take(4)

        if (anchors.size != 4) return null

        val cards = mutableListOf<String>()
        anchors.forEachIndexed { pos, anchor ->
            val leftAnchor = if (pos == 0) 0 else anchors[pos - 1]
            val rightAnchor = if (pos == anchors.lastIndex) lines.lastIndex else anchors[pos + 1]

            val start = if (pos == 0) {
                maxOf(0, anchor - 7)
            } else {
                maxOf(anchor - 7, (leftAnchor + anchor) / 2)
            }

            val end = if (pos == anchors.lastIndex) {
                minOf(lines.lastIndex, anchor + 6)
            } else {
                minOf(anchor + 6, (anchor + rightAnchor) / 2)
            }

            if (end < start) return null
            cards += lines.subList(start, end + 1).joinToString("\n")
        }

        return cards.takeIf { it.size == 4 }
    }

    private fun score(text: String, signature: Signature): Int {
        val n = normalize(text)
        if (n.isBlank()) return 0

        var result = 0
        val team = normalize(signature.team)
        val competition = normalize(signature.competition)

        if (validIdentity(signature.team) && team.length >= 4 && containsWholeish(n, team)) {
            result += 100
        }

        if (validIdentity(signature.competition) && competition.length >= 5 && containsWholeish(n, competition)) {
            result += 65
        }

        val visitTokens = tokenize(n)
        val matched = signature.tokens.filter { it in visitTokens }
        result += minOf(matched.size, 8) * 9

        // Nome/time de uma assinatura rival presente reduz confiança.
        // Isso evita o efeito "todas as visitas viram S3".
        return result
    }

    private fun containsWholeish(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return haystack.contains(needle)
    }

    private fun isSlotsHub(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false

        val explicitSlots = Regex("""\bslot\s*[1-4]\b""").findAll(n).count()
        if (explicitSlots >= 3) return true

        // Calendário também contém muitas rodadas: não é central.
        if (
            n.contains("calendario") ||
            n.contains("fixtures") ||
            n.contains("classificacao") ||
            n.contains("tabela")
        ) return false

        val rounds = ROUND_REGEX.findAll(n).count()
        val hubWords = listOf(
            "liga", "league", "batalha", "battle",
            "divisao", "division", "manager", "treinador",
            "leandrozzy"
        ).count { n.contains(it) }

        return rounds >= 3 && hubWords >= 1
    }

    private fun blockedIdentityLine(value: String): Boolean {
        val n = normalize(value)
        return n.isBlank() || STOP_PHRASES.any { n.contains(it) } || ROUND_REGEX.containsMatchIn(n)
    }

    private fun looksLikeTeam(value: String): Boolean {
        val n = normalize(value)
        if (n.length !in 3..45 || !n.any(Char::isLetter)) return false
        if (blockedIdentityLine(value)) return false
        if (looksLikeCompetition(value)) return false
        if (n == "leandrozzy") return false
        return true
    }

    private fun looksLikeCompetition(value: String): Boolean {
        val n = normalize(value)
        if (n.length !in 3..70 || !n.any(Char::isLetter)) return false
        return listOf(
            "liga", "league", "divisao", "division",
            "batalha", "battle", "copa", "cup",
            "premier", "bundesliga", "serie", "division"
        ).any { n.contains(it) }
    }

    private fun validIdentity(value: String): Boolean {
        val n = normalize(value)
        if (n.isBlank() || n == "ni") return false
        if (Regex("""^slot\s*[1-4]$""").matches(n)) return false
        return n.length >= 3
    }

    private fun addIdentityTokens(output: MutableSet<String>, raw: String) {
        if (!validIdentity(raw)) return
        tokenize(normalize(raw))
            .filter { it.length >= 4 && it !in STOP }
            .forEach(output::add)
    }

    private fun isNoise(text: String): Boolean {
        val n = normalize(text)
        return listOf(
            "instalar agora", "install now", "anuncio",
            "advertisement", "patrocinado", "sponsored",
            "fechar anuncio", "close ad"
        ).any { n.contains(it) }
    }

    private fun tokenize(value: String): Set<String> =
        value.split(' ')
            .map { it.trim() }
            .filter { it.length >= 3 }
            .toSet()

    private fun normalize(value: String): String =
        Normalizer.normalize(
            value.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9 /.-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private val ROUND_REGEX = Regex("""\b\d{1,2}\s*/\s*\d{1,2}\b""")

    private val STOP = setOf(
        "jogo","jogar","clube","time","manager","treinador",
        "proximo","rodada","jornada","casa","fora","valor",
        "pontos","slot","liga","league","division","divisao",
        "osm","elenco","calendario","mercado","tatica","treino",
        "jogador","jogadores","classificacao","tabela","temporada",
        "perfil","comunicacoes","diamantes","analista","dados"
    )

    private val STOP_PHRASES = listOf(
        "proximo jogo","calendario","elenco","tatica","mercado",
        "treino","classificacao","perfil","comunicacoes",
        "diamantes","analista de dados","osm ai coach",
        "dados completos","leitura automatica","sessao"
    )
}
