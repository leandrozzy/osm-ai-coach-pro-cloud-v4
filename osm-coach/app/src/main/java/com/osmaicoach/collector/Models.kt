package com.osmaicoach.collector

import java.text.Normalizer
import kotlin.math.max

const val OSM_PACKAGE = "com.gamebasics.osm"
const val NI = "NI"
const val MY_NICK = "leandrozzy"

enum class ScreenType { HUB, PREGAME, SQUAD, CALENDAR, MARKET, TRAINING, TACTIC, REPORT, STADIUM, RESULT, OTHER_OSM, NOISE, NON_OSM }

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
    val forSale: Boolean?,
    val cond: Int? = null,
    val morale: Int? = null
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

data class MatchReportRead(
    val round: Int? = null,
    val homeTeam: String? = null,
    val awayTeam: String? = null,
    val homeNick: String? = null,
    val awayNick: String? = null,
    val scoreHome: Int? = null,
    val scoreAway: Int? = null,
    val referee: String? = null,
    val tip: String? = null,
    val advice: String? = null,
    val mom: String? = null,
    val stats: Map<String, Pair<String, String>> = emptyMap(),
    val zones: List<Int> = emptyList(),
    val ratingsHome: List<Pair<String, Int>> = emptyList(),
    val ratingsAway: List<Pair<String, Int>> = emptyList(),
    val events: List<String> = emptyList()
)

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
    val note: String = "",
    val matchReport: MatchReportRead? = null
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
    const val MY_STADIUM = "my.stadium"
    const val MY_STAD_CAP = "my.stadium.cap"
    const val MY_STAD_PITCH = "my.stadium.pitch"
    const val MY_STAD_TRAIN = "my.stadium.train"
    const val MY_BONUS = "my.bonus"
    const val MY_STAD_STATUS = "my.stadium.status"
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
    const val RIVAL_PRESSURE = "rival.pressure"
    const val RIVAL_MENTALITY = "rival.mentality"
    const val RIVAL_TEMPO = "rival.tempo"
    const val RIVAL_GOL = "rival.gol"
    const val RIVAL_DEF = "rival.def"
    const val RIVAL_MID = "rival.mid"
    const val RIVAL_ATK = "rival.atk"
}

/** Botões do próprio app por cima do jogo durante a captura: o OCR não pode tratá-los como texto do OSM. */
object Overlay {
    private val CHIPS = setOf("encerrar", "app", "osm", "toque de novo para encerrar", "encerrar captura", "tatica")

    /** Área dos botões/painel flutuantes na tela (0..1: esquerda, topo, direita, base); o OCR ignora o que cai dentro. */
    @Volatile
    var bounds: FloatArray? = null

    /** A foto foi tirada com os botões escondidos: nada na área deles é do app. */
    @Volatile
    var shotClean: Boolean = false

    /** Palavra que começa com o texto de um botão colado em outra ("Encerraroden", "Taticabarnes"). */
    fun glued(text: String): Boolean {
        val n = Txt.norm(text).replace(Regex("[^a-z]"), "")
        // o OCR também lê "Encerar", "Encerr", "TEncerar", "iencerrar…" quando o botão cobre o texto do jogo
        return n.length >= 6 && (Regex("encerr?ar|encerr").containsMatchIn(n) || (n.startsWith("tatica") && n.length > 10))
    }

    /** Texto que veio (todo ou em parte) dos botões do app: "App", "Encerrar", "Tática", e colagens. */
    fun polluted(text: String): Boolean = isChip(text) || glued(text) ||
        Txt.norm(text).split(" ").any { it == "app" || it == "osm" || glued(it) }

    fun covers(xc: Float, yc: Float): Boolean {
        if (shotClean) return false
        val b = bounds ?: return false
        return xc >= b[0] - 0.01f && xc <= b[2] + 0.01f && yc >= b[1] - 0.01f && yc <= b[3] + 0.01f
    }

    fun isChip(text: String): Boolean = Txt.norm(text).replace(Regex("[^a-z ]"), " ").replace(Regex("\\s+"), " ").trim() in CHIPS
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

    private val reFind = Regex("(\\d{1,3}(?:[.,]\\d{1,2})?)\\s*([MKmk])(?![A-Za-z])")

    /** Acha dinheiro dentro de um token sujo pelo ícone da moeda (ex.: "©7,6M" -> "7,6M"). */
    fun extract(s: String?): String? {
        if (s == null) return null
        val m = reFind.find(s) ?: return null
        val t = m.groupValues[1] + m.groupValues[2].uppercase()
        return if (valid(t)) t else null
    }

    /**
     * O OCR às vezes perde a vírgula ("27,5M" vira "275M"). Para times fracos (força <= 80),
     * um valor de 3 dígitos em M é implausível: reinsere a vírgula antes do último dígito.
     */
    fun fixLostComma(text: String, teamStrength: Int): String? {
        if (teamStrength > 80) return null
        val m = Regex("^(\\d{2})(\\d)M$").find(text.trim()) ?: return null
        return m.groupValues[1] + "," + m.groupValues[2] + "M"
    }
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
        // código desconhecido: só a primeira letra quando o código tem cara de posição (2-3 letras)
        if (c.length !in 2..3 || !c.all { it in 'A'..'Z' }) return null
        return when (c[0]) {
            'G' -> "GOL"
            'D', 'L', 'Z' -> "DEF"
            'M' -> "MEI"
            'P', 'E', 'A' -> "ATA"
            else -> null
        }
    }

    /**
     * Setor pela "forma" dos três números da linha (Ata, Def, Méd): só quando é inequívoca. Atacante tem Ata bem acima
     * de Def; defensor/goleiro, Def bem acima de Ata; meia, Méd acima dos dois. Casos equilibrados ficam null.
     */
    fun catFromStats(ata: Int?, def: Int?, med: Int?): String? {
        if (ata == null || def == null || med == null) return null
        // o número principal de um jogador profissional nunca fica abaixo de 50: abaixo disso é dígito perdido
        // pelo OCR ("2" no lugar de "72") e a forma da linha não diz nada
        if (maxOf(ata, def, med) < 50) return null
        return when {
            ata >= def + 20 && ata >= med + 8 -> "ATA"
            def >= ata + 20 && def >= med + 8 -> "DEF"
            med >= ata + 8 && med >= def + 8 -> "MEI"
            else -> null
        }
    }

    /**
     * Setor final do jogador: o cabeçalho da seção do jogo ("Avançados", "Médios"...) é o mais confiável; depois o
     * código da posição lido; os números da linha desempatam e corrigem um código mal lido (ex.: meia lido como "ED").
     * Goleiro e defensor têm a mesma forma: o código/seção decide entre os dois.
     */
    fun resolve(code: String?, section: String?, stats: String?): String? {
        fun same(a: String, b: String) = a == b || (a in BACK && b in BACK)
        if (section != null) {
            // goleiro x defensor (mesma família): o código lido decide
            if (code != null && section in BACK && code in BACK) return code
            if (stats == null || same(stats, section)) return section
            // seção e código concordam contra os números: dois sinais contra um
            if (code != null && same(code, section)) return section
            // código e números concordam contra a seção: vale o código (que distingue goleiro de defensor)
            return if (code != null && same(code, stats)) code else stats
        }
        if (code != null) return if (stats == null || same(stats, code)) code else stats
        return stats
    }

    private val BACK = setOf("DEF", "GOL")

    fun catFromSection(normalizedText: String): String? = when {
        normalizedText.startsWith("avancad") -> "ATA"
        normalizedText.startsWith("medio") -> "MEI"
        normalizedText.startsWith("defes") -> "DEF"
        normalizedText.startsWith("guarda") -> "GOL"
        else -> null
    }
}

/**
 * Valores reais das opções do OSM, conferidos no vídeo da tela "Define a tua tática" (OSM 26, português).
 * Tudo que vem da IA, do OCR ou de versões antigas passa por aqui para virar exatamente o texto do jogo.
 */
object Osm {
    val STYLES = listOf("Jogar pelas alas", "Jogo de passe", "Contra-ataque", "Remate à vista", "Bola longa")
    val MARKING = listOf("À zona", "Homem-a-homem")
    val TACKLES = listOf("Cuidadoso", "Normal", "Agressivo", "Extremo")
    /** Avançados, na ordem do jogo. */
    val ATTACK = listOf("Atacar apenas", "Apoiar meio-campo", "Ajudar a defender")
    val MIDFIELD = listOf("Pressionar na frente", "Manter posições", "Ajudar a defesa")
    val DEFENSE = listOf("Defender atrás", "Defesas atacantes", "Apoiar meio-campo")

    /** Rótulo que o jogo mostra para cada faixa dos controles deslizantes (0-100). */
    fun pressureLabel(v: Int): String = band(v, "Não pressionar", "Ficar atrás", "Equilibrado", "Chegar perto do adversário", "Pressionar alto")
    fun mentalityLabel(v: Int): String = band(v, "Super defensivo", "Defensivo", "Neutro", "Ofensivo", "Tudo ao ataque")
    fun tempoLabel(v: Int): String = band(v, "Jogar atrás", "Construir de trás para a frente", "Fazer posse", "Passes rápidos", "Futebol ao primeiro toque")

    private fun band(v: Int, vararg names: String): String = names[(v.coerceIn(0, 100) / 20).coerceAtMost(names.size - 1)]

    fun style(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("alas") || n.contains("wing") -> "Jogar pelas alas"
            n.contains("passe") || n.contains("passing") || n.contains("pass game") -> "Jogo de passe"
            n.contains("contra") || n.contains("counter") -> "Contra-ataque"
            n.contains("remate") || n.contains("shoot") -> "Remate à vista"
            n.contains("bola") || n.contains("long") -> "Bola longa"
            else -> null
        }
    }

    fun marking(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("zona") -> "À zona"
            n.contains("individ") || n.contains("homem") || Regex("\\bman\\b").containsMatchIn(n) -> "Homem-a-homem"
            else -> null
        }
    }

    /**
     * Desarme permitido pelo árbitro (termômetro do pré-jogo): Extremo nunca (expulsão quase certa);
     * Agressivo só com árbitro brando (azul); médio (laranja), rigoroso (vermelho) ou desconhecido: até Normal.
     */
    fun allowedTackles(referee: String?): List<String> =
        if (referee == "Brando") listOf("Cuidadoso", "Normal", "Agressivo") else listOf("Cuidadoso", "Normal")

    /** Rebaixa o desarme para o máximo que o árbitro permite. */
    fun clampTackle(t: String?, referee: String?): String {
        val v = tackle(t) ?: "Normal"
        val ok = allowedTackles(referee)
        return if (v in ok) v else ok.last()
    }

    fun tackle(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("cuidad") || n.contains("cautel") || n.contains("careful") -> "Cuidadoso"
            n.contains("extrem") || n.contains("imprud") || n.contains("reckless") -> "Extremo"
            n.contains("agress") || n.contains("aggress") -> "Agressivo"
            n.contains("normal") -> "Normal"
            else -> null
        }
    }

    fun attack(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("atacar") || n.contains("attack only") || n.contains("stay forward") -> "Atacar apenas"
            n.contains("meio") || n.contains("midfield") -> "Apoiar meio-campo"
            n.contains("defe") || n.contains("help") -> "Ajudar a defender"
            else -> null
        }
    }

    fun midfield(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("pression") || n.contains("press") -> "Pressionar na frente"
            n.contains("manter") || n.contains("position") || n.contains("hold") -> "Manter posições"
            n.contains("ajudar") || n.contains("defesa") || n.contains("protect") || n.contains("defen") -> "Ajudar a defesa"
            else -> null
        }
    }

    fun defense(s: String?): String? {
        val n = Txt.norm(s ?: "")
        return when {
            n.contains("atacante") || n.contains("lateral") || n.contains("attacking") || n.contains("full") || n.contains("wing back") -> "Defesas atacantes"
            n.contains("apoiar") || n.contains("meio") || n.contains("midfield") -> "Apoiar meio-campo"
            n.contains("defender") || n.contains("atras") || n.contains("deep") || n.contains("defend") || n.contains("stay back") -> "Defender atrás"
            else -> null
        }
    }
}

/** Mesmo jogador lido com nomes diferentes pelo OCR (para não virar dois jogadores no elenco). */
object SquadDedupe {
    data class P(val key: String, val name: String, val age: Int?, val strength: Int?, val cat: String?, val value: String?, val seen: Long)

    private fun garbled(name: String) = Regex("^[a-z][A-Z]").containsMatchIn(name) || Txt.letters(name) < 3

    /** Iguais, quase iguais, ou um é o outro com a inicial na frente ("jtimber" x "timber"). */
    fun similarNames(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 4 || b.length < 4) return false
        val s = if (a.length <= b.length) a else b
        val l = if (a.length <= b.length) b else a
        if (l.length - s.length <= 2 && (l.endsWith(s) || l.startsWith(s))) return true
        return Txt.sim(a, b) >= 0.8
    }

    /**
     * Mesma idade e nome muito parecido (como antes), ou nome parecido + mesma força (diferença de até 2, por treino).
     * Força bem diferente nunca é o mesmo jogador (gêmeos com o mesmo sobrenome continuam separados).
     */
    fun samePlayer(k1: String, age1: Int?, str1: Int?, k2: String, age2: Int?, str2: Int?): Boolean {
        if (age1 == null || age2 == null || age1 != age2) return false
        if (str1 != null && str2 != null && kotlin.math.abs(str1 - str2) > 2) return false
        if (Txt.sim(k1, k2) >= 0.88) return true
        return str1 != null && str2 != null && similarNames(k1, k2)
    }

    /** Pares (repetido, o que fica). Fica o nome legível; empate: o visto por último. */
    fun duplicates(list: List<P>): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val dropped = HashSet<String>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]
            val b = list[j]
            if (a.key in dropped || b.key in dropped) continue
            if (a.cat != null && b.cat != null && a.cat != b.cat) continue
            if (!samePlayer(a.key, a.age, a.strength, b.key, b.age, b.strength)) continue
            val keepA = when {
                garbled(a.name) != garbled(b.name) -> !garbled(a.name)
                a.seen != b.seen -> a.seen > b.seen
                else -> a.name.length >= b.name.length
            }
            val (bad, keep) = if (keepA) Pair(b.key, a.key) else Pair(a.key, b.key)
            dropped.add(bad)
            out.add(Pair(bad, keep))
        }
        return out
    }
}

/**
 * Nome de jogador lido pelo OCR: tira textos de botões/rótulos do OSM que grudam no nome ("Atribuir número",
 * "MAIS EM BREVE", "JOGO RÁPIDO", "Ata", "189GOL") e recusa lixo ("MdLK", "AUUIKIIUIKUy", "ohl", "RAbdikhoikov").
 */
object PlayerName {
    private val PHRASES = listOf(
        Regex("atribuir\\s+n[uú]mero", RegexOption.IGNORE_CASE), Regex("mais\\s+em\\s+breve", RegexOption.IGNORE_CASE),
        Regex("jogo\\s+r[aá]pido", RegexOption.IGNORE_CASE), Regex("em\\s+breve", RegexOption.IGNORE_CASE)
    )
    private val LABELS = setOf("ata", "def", "med", "mei", "gr", "gol", "idade", "pos", "valor", "nota", "treinando", "fora", "condicao", "moral")

    private fun garbled(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        if (letters.length < 2) return false
        // maiúsculas depois de uma minúscula ("MdLK", "sARY") ou várias maiúsculas no meio ("AUUIKIIUIKUy")
        if (Regex("[a-z][A-Z]").findAll(letters).count() >= 1 && letters.drop(1).count { it.isUpperCase() } >= 2) return true
        if (letters.length >= 5 && letters.count { it.isUpperCase() } >= 3 && letters.any { it.isLowerCase() }) return true
        // duas maiúsculas e depois minúsculas, colado ("RAbdikhoikov")
        if (Regex("^[A-Z]{2}[a-z]{3,}").containsMatchIn(letters)) return true
        return false
    }

    /** Nome limpo, ou null se o que sobrou não é um nome de jogador. */
    fun clean(raw: String): String? {
        var t = raw.trim()
        for (p in PHRASES) t = p.replace(t, " ")
        val out = ArrayList<String>()
        for (w0 in t.split(Regex("\\s+")).filter { it.isNotBlank() }) {
            // "189GOLAubameyang": número e rótulo em maiúsculas colados na frente do nome
            val w = Regex("^\\d+[A-Z]{2,}(?=[A-Z][a-z])").replace(w0, "")
            if (w.any { it.isDigit() }) continue
            val letters = w.filter { it.isLetter() }
            if (letters.isEmpty()) continue
            if (Txt.norm(w) in LABELS) continue
            // palavra inteira em maiúsculas (BREVE, JOGO, RAPIDO): é texto de botão, não sobrenome
            if (letters.length >= 3 && letters.all { it.isUpperCase() }) continue
            out.add(w)
        }
        if (out.isEmpty()) return null
        if (out.any { garbled(it) }) return null
        val name = out.joinToString(" ")
        // todo nome do OSM tem pelo menos uma maiúscula ("de Paula" inclusive); "ohl" é pedaço de leitura
        if (Txt.letters(name) < 3 || name.none { it.isUpperCase() }) return null
        return name
    }
}

/** Telas do fim da competição e da diretoria (resumo da época, classificação final, valor do plantel, direção). */
object SeasonScreens {
    private val WORDS = listOf(
        "a epoca acabou", "epoca acabou", "fim da epoca", "fim de epoca", "final da epoca", "classificacao final", "direcao",
        "diretoria", "administracao", "presidente", "demiss", "despedid", "objetivo da epoca", "lista de treinadores",
        "campeao da liga", "melhor marcador", "nova epoca", "proxima epoca", "esta liga termina", "promovid", "despromovid", "a gozar"
    )

    fun isSeasonScreen(fullText: String): Boolean {
        val t = Txt.norm(fullText)
        return WORDS.any { t.contains(it) }
    }

    fun seasonOver(text: String): Boolean {
        val t = Txt.norm(text)
        return t.contains("epoca acabou") || t.contains("fim da epoca") || t.contains("classificacao final") ||
            t.contains("nova epoca") || t.contains("proxima epoca") || t.contains("esta liga termina") ||
            (t.contains("a gozar") && t.contains("jogadores"))
    }
}
