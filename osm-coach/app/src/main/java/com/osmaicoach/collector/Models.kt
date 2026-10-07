package com.osmaicoach.collector

import java.text.Normalizer
import kotlin.math.max

const val OSM_PACKAGE = "com.gamebasics.osm"
const val NI = "NI"
const val MY_NICK = "leandrozzy"

enum class ScreenType { HUB, PREGAME, SQUAD, CALENDAR, MARKET, TRAINING, TACTIC, REPORT, OTHER_OSM, NOISE, NON_OSM }

/** Coordenadas sempre normalizadas (0..1) em relação à largura/altura da captura. */
data class OcrToken(val text: String, val l: Float, val t: Float, val r: Float, val b: Float) {
    val xc: Float get() = (l + r) / 2f
    val yc: Float get() = (t + b) / 2f
    val h: Float get() = b - t
}

data class OcrLine(
    val text: String,
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float,
    val tokens: List<OcrToken> = emptyList()
) {
    val xc: Float get() = (l + r) / 2f
    val yc: Float get() = (t + b) / 2f
    val h: Float get() = b - t
}

data class OcrResult(val lines: List<OcrLine>, val tokens: List<OcrToken>) {
    val fullText: String get() = lines.joinToString("\n") { it.text }
}

data class Reading(val value: String, val conf: Double)

data class PlayerRead(
    val name: String,
    val age: Int?,
    val posCode: String?,
    val cat: String?,
    val strength: Int?,
    val valueText: String?,
    val training: Boolean?,
    val forSale: Boolean?
)

data class MatchRead(
    val key: String,
    val label: String,
    val round: Int?,
    val date: String?,
    val time: String?,
    val home: Boolean?,
    val scoreMine: Int?,
    val scoreOpp: Int?,
    val result: String?,
    val opponent: String?,
    val opponentNick: String?
)

data class ListingRead(
    val name: String,
    val age: Int?,
    val posCode: String?,
    val cat: String?,
    val strength: Int?,
    val priceText: String?,
    val club: String?,
    val sellerNick: String?
)

data class HubCard(val slot: Int, val team: String, val subtitle: String, val roundDone: Int?, val roundTotal: Int?)

data class Extraction(
    val type: ScreenType,
    val fields: Map<String, Reading> = emptyMap(),
    val players: List<PlayerRead> = emptyList(),
    val matches: List<MatchRead> = emptyList(),
    val listings: List<ListingRead> = emptyList(),
    val hubCards: List<HubCard> = emptyList(),
    val humans: Map<String, String?> = emptyMap(),
    val ownerTeam: String? = null,
    val ownerNick: String? = null,
    val teamCandidates: List<String> = emptyList(),
    val roundRead: Int? = null,
    val needsAi: Boolean = false,
    val note: String = ""
)

/** Chaves dos campos escalares guardados por slot. */
object K {
    const val TEAM = "team"
    const val HUB_TITLE = "hubTitle"
    const val COMPETITION = "competition"
    const val COMP_TYPE = "competitionType"
    const val ROUND = "round"
    const val ROUND_DONE = "roundDone"
    const val ROUND_TOTAL = "roundTotal"
    const val MATCH_AT = "matchAt"
    const val HOME = "home"
    const val CASH = "cash"
    const val LEAGUE_POS = "leaguePos"
    const val POINTS = "points"
    const val REFEREE = "referee"
    const val REFEREE_RAW = "refereeRaw"
    const val STADIUM = "stadium"
    const val STADIUM_BONUS = "stadiumBonus"
    const val SELLING = "market.selling"
    const val MY_STRENGTH = "my.strength"
    const val MY_VALUE = "my.value"
    const val MY_FORMATION = "my.formation"
    const val MY_GOL = "my.gol"
    const val MY_DEF = "my.def"
    const val MY_MID = "my.mid"
    const val MY_ATK = "my.atk"
    const val MY_OBJECTIVE = "my.objective"
    const val RIVAL_TEAM = "rival.team"
    const val RIVAL_NICK = "rival.nick"
    const val RIVAL_HUMAN = "rival.human"
    const val RIVAL_STRENGTH = "rival.strength"
    const val RIVAL_VALUE = "rival.value"
    const val RIVAL_FORMATION = "rival.formation"
    const val RIVAL_PLAN = "rival.plan"
    const val RIVAL_MARKING = "rival.marking"
    const val RIVAL_OFFSIDE = "rival.offside"
    const val RIVAL_TACKLE = "rival.tackle"
    const val RIVAL_SECRET = "rival.secretTraining"
    const val RIVAL_CAMP = "rival.trainingCamp"
    const val RIVAL_LOGIN_BONUS = "rival.loginBonus"
    const val RIVAL_GOL = "rival.gol"
    const val RIVAL_DEF = "rival.def"
    const val RIVAL_MID = "rival.mid"
    const val RIVAL_ATK = "rival.atk"
}

object Txt {
    fun norm(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("[^a-z0-9/:.,\\- ]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    /** Chave só com letras/dígitos, para comparar nomes. */
    fun key(s: String): String = norm(s).replace(Regex("[^a-z0-9]"), "")

    fun letters(s: String): Int = s.count { it.isLetter() }

    fun sim(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + cost)
                prev = tmp
            }
        }
        return 1.0 - dp[b.length].toDouble() / max(a.length, b.length).toDouble()
    }

    fun titleCase(s: String): String =
        s.lowercase().split(" ").filter { it.isNotEmpty() }
            .joinToString(" ") { w -> w.replaceFirstChar { c -> c.uppercase() } }
}

object Money {
    private val re = Regex("^(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*([MKmk])$")

    /** Retorna o valor em milhões, ou null se o texto não for dinheiro válido (precisa de M ou K). */
    fun parse(s: String?): Double? {
        if (s == null) return null
        val m = re.find(s.trim()) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return if (m.groupValues[2].uppercase() == "M") n else n / 1000.0
    }

    fun valid(s: String?): Boolean = parse(s) != null
}

object Pos {
    private val gol = setOf("GR", "GK", "G")
    private val def = setOf("DC", "DD", "DE", "LD", "LE", "ZC", "DCE", "DCD")
    private val mei = setOf("MC", "MCO", "MCD", "MD", "ME", "MOC", "MDC", "MCE", "MCDI")
    private val ata = setOf("PL", "ED", "EE", "SA", "AC", "CA", "AT")

    /** ATA, MEI, DEF ou GOL; null se não der para saber. */
    fun cat(code: String?): String? {
        if (code == null) return null
        val c = code.trim().uppercase()
        if (c.isEmpty() || c.length > 4) return null
        if (c in gol) return "GOL"
        if (c in def) return "DEF"
        if (c in mei) return "MEI"
        if (c in ata) return "ATA"
        return when (c[0]) {
            'G' -> "GOL"
            'D' -> "DEF"
            'M' -> "MEI"
            'P', 'E', 'S', 'A' -> "ATA"
            else -> null
        }
    }

    fun catFromSection(normalizedText: String): String? = when {
        normalizedText.startsWith("avancad") -> "ATA"
        normalizedText.startsWith("medio") -> "MEI"
        normalizedText.startsWith("defes") -> "DEF"
        normalizedText.startsWith("guarda") -> "GOL"
        else -> null
    }
}
