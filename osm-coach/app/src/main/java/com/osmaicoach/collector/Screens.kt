package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

/**
 * Telas do OSM que o app passou a ler (vistas no vídeo de 22/10): Preparação para o jogo, Médico, Advogado,
 * Especialistas, Patrocinadores, Olheiro, Negociações/Transferências, Estatísticas da liga e Missões.
 * Cada leitor devolve um JSON simples guardado por slot (ou global, no caso das missões).
 */
object Screens {
    /** Tipo da tela, se for uma destas (palavras bem específicas: anúncio não tem). */
    fun classify(t: String): ScreenType? = when {
        t.contains("novas missoes dentro de") || (t.contains("a semana reinicia") && t.contains("missoes")) -> ScreenType.MISSIONS
        t.contains("lista de afazeres") && t.contains("preparacao para o jogo") &&
            PREP_DEFS.count { d -> t.contains(d.prefix) } >= 1 -> ScreenType.PREP
        t.contains("especialistas") && (t.contains("grandes penalidades") || t.contains("capitao")) &&
            (t.contains("substituir jogador") || t.contains("livres")) -> ScreenType.SPECIALISTS
        // o menu lateral lista os dois; a tela de cada um tem só o seu nome e o aviso de "ninguém"/a lista
        t.contains("medico") && !t.contains("advogado") &&
            (t.contains("lesion") || t.contains("lesao") || t.contains("nao tens") || t.contains("tratamento") || t.contains("tratar")) -> ScreenType.DOCTOR
        t.contains("advogado") && !t.contains("medico") &&
            (t.contains("suspens") || t.contains("expuls") || t.contains("castig") || t.contains("nao tens") || t.contains("recurso") || t.contains("recorr")) -> ScreenType.LAWYER
        Regex("acordo de patrocinio").findAll(t).count() >= 2 -> ScreenType.SPONSORS
        t.contains("resultados do olheiro") ||
            (t.contains("seleciona uma posicao") && t.contains("procurar jogadores")) ||
            // olheiro já buscando: "Estou a caminho! Voltarei em: 15h 59m 53s"
            (t.contains("voltarei em") && t.contains("olheiro")) -> ScreenType.SCOUT
        (t.contains("jogador idade") && t.contains("preco") && t.contains("hora")) || t.contains("negociacoes pendentes") -> ScreenType.DEALS
        t.contains("jogadores transferidos") && t.contains("golos marcados") -> ScreenType.STATS
        else -> null
    }

    // ------------------------------------------------------------ preparação para o jogo (lista de afazeres do jogo)

    data class PrepDef(val key: String, val prefix: String, val title: String, val why: String, val tab: Int, val costsBc: Boolean = false)

    /** O que o jogo pede antes de cada partida (textos do próprio jogo) e onde fazer. */
    val PREP_DEFS = listOf(
        PrepDef("treino", "conclui", "Conclua 4 sessões de treino", "jogadores de melhor qualidade têm melhor desempenho", 2),
        PrepDef("amistoso", "joga 1 amig", "Jogue 1 amistoso", "jogadores de melhor qualidade têm melhor desempenho (gasta Boss Coins)", 6, costsBc = true),
        PrepDef("posicao", "posiciona os jog", "Posicione os jogadores", "os jogadores rendem mais na posição específica deles", 4),
        PrepDef("banco", "preenche o banco", "Preencha o banco de suplentes", "a sua hipótese de influenciar as substituições possíveis", 4),
        PrepDef("secreto", "planeia um treino", "Planeje um Treino Secreto", "esconde as suas táticas do Analista do rival (gasta Boss Coins)", 4, costsBc = true),
        PrepDef("estagio", "faz um estagio", "Faça um Estágio", "melhora a equipa em 25% no jogo (gasta Boss Coins)", 4, costsBc = true),
        PrepDef("onze", "escolhe o 11", "Escolha o 11 inicial", "equipes que não têm 11 jogadores estão enfraquecidas", 4),
        PrepDef("especialistas", "seleciona os teus esp", "Selecione os Especialistas", "os especialistas certos fortalecem a equipa", 4),
        PrepDef("analista", "analisa o advers", "Analise o adversário", "usa as informações do Analista de Dados", 4),
        PrepDef("forma", "alinha jogadores em boa", "Alinhe jogadores em boa forma", "a qualidade do jogador diminui conforme a condição física cai", 4)
    )

    /** Itens visíveis: polegar verde = feito, cinza = falta. */
    fun prep(o: OcrResult, img: PixelProbe.Img?): JSONObject {
        val items = JSONArray()
        if (img != null) {
            for (ln in o.lines) {
                val n = Txt.norm(ln.text)
                val d = PREP_DEFS.firstOrNull { n.startsWith(it.prefix) } ?: continue
                val done = PixelProbe.greenBox(img, 0.78f, 0.84f, ln.yc - 0.01f, ln.yc + 0.05f) >= 0.12f
                items.put(JSONObject().put("k", d.key).put("done", done))
            }
        }
        return JSONObject().put("items", items)
    }

    // ------------------------------------------------------------ médico e advogado

    /** Texto das linhas (para casar com o elenco) e se o jogo disse que não há ninguém. */
    fun roster(o: OcrResult): JSONObject {
        val t = Txt.norm(o.fullText)
        val empty = t.contains("nao tens qualquer jogador") || t.contains("nao tens nenhum jogador") || t.contains("nao tens jogadores") ||
            t.contains("nenhum jogador")
        val lines = JSONArray()
        for (ln in o.lines.take(80)) {
            val s = ln.text.trim()
            if (s.length in 3..40 && Txt.letters(s) >= 3) lines.put(s)
        }
        // Advogado "A apresentar recurso..." + cronômetro: recurso em andamento
        val busy = t.contains("a apresentar recurso") || t.contains("tratamento em curso")
        val secs = if (busy) o.lines.mapNotNull { Parsers.timerSecs(it.text) }.firstOrNull() else null
        return JSONObject().put("empty", empty).put("lines", lines).put("busy", busy).put("endsIn", secs ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------ especialistas

    val SPEC_ROLES = listOf("capitao", "penalti", "falta", "escanteio")
    val SPEC_LABEL = mapOf("capitao" to "Capitão", "penalti" to "Pênaltis", "falta" to "Faltas", "escanteio" to "Escanteios")
    private val RX_CARD_NAME = Regex("^\\d{1,2}\\s+[A-ZÀ-Ý][A-ZÀ-Ý .'\\-]{2,}$")

    /** Os 4 cartões da tela (esquerda → direita): capitão, pênaltis, faltas, escanteios. */
    fun specialists(o: OcrResult): JSONObject {
        val cards = o.lines.filter { it.yc in 0.30f..0.75f && RX_CARD_NAME.matches(it.text.trim()) }.sortedBy { it.xc }
        val out = JSONObject()
        if (cards.size == 4) for (i in 0 until 4) out.put(SPEC_ROLES[i], cards[i].text.trim().replace(Regex("^\\d{1,2}\\s+"), ""))
        return out
    }

    // ------------------------------------------------------------ olheiro

    /** Contagem regressiva do olheiro (se estiver procurando); "16h" sozinho é só a duração do botão. */
    fun scout(o: OcrResult): JSONObject {
        // lista de resultados: a busca já terminou; o "29m 51s" é só o prazo das ofertas (não é o olheiro voltando)
        if (Txt.norm(o.fullText).contains("resultados do olheiro")) return JSONObject().put("endsIn", JSONObject.NULL)
        val secs = o.lines.mapNotNull { Parsers.timerSecs(it.text) }.firstOrNull()
        return JSONObject().put("endsIn", secs ?: JSONObject.NULL)
    }

    // ------------------------------------------------------------ negociações / histórico de transferências

    private val RX_TIME = Regex("(\\d{1,2}):(\\d{2})")

    /** Linhas da tabela: jogador, de, para, hora, preço. */
    fun deals(o: OcrResult): JSONObject {
        val rows = JSONArray()
        val prices = o.tokens.filter { it.xc > 0.85f && it.yc > 0.15f && Money.extract(it.text) != null }
        for (p in prices) {
            val band = o.lines.filter { abs(it.yc - p.yc) <= 0.035f }
            val name = band.filter { it.xc < 0.22f }.minByOrNull { it.l }?.text?.trim() ?: continue
            val from = band.filter { it.xc in 0.25f..0.44f }.minByOrNull { it.yc }?.text?.trim()
            val to = band.filter { it.xc in 0.45f..0.64f }.minByOrNull { it.yc }?.text?.trim()
            val time = band.firstNotNullOfOrNull { RX_TIME.find(it.text)?.takeIf { _ -> it.xc in 0.62f..0.86f } }
            val price = Money.parse(Money.extract(p.text)) ?: continue
            rows.put(
                JSONObject().put("name", name.replace(Regex("\\s*\\(\\d+\\)\\s*$"), "")).put("from", from ?: "").put("to", to ?: "").put("price", price)
                    .put("h", time?.groupValues?.get(1)?.toIntOrNull() ?: -1).put("m", time?.groupValues?.get(2)?.toIntOrNull() ?: -1)
            )
        }
        return JSONObject().put("rows", rows)
    }

    // ------------------------------------------------------------ estatísticas da liga

    private fun numberNear(o: OcrResult, label: OcrLine): Double? {
        val c = o.lines.filter { it !== label && abs(it.xc - label.xc) <= 0.09f && it.yc >= label.yc - 0.01f && it.yc <= label.yc + 0.09f }
            .mapNotNull { ln -> Regex("^\\d{1,6}(?:[.,]\\d{1,2})?$").find(ln.text.trim())?.let { ln to it.value.replace(',', '.').toDouble() } }
            .minByOrNull { it.first.yc } ?: return null
        return c.second
    }

    fun stats(o: OcrResult): JSONObject {
        fun find(vararg words: String): Double? {
            val l = o.lines.firstOrNull { ln -> val n = Txt.norm(ln.text); words.any { n == it || n.startsWith(it) } } ?: return null
            return numberNear(o, l)
        }
        val out = JSONObject()
        find("jogos")?.let { out.put("games", it.toInt()) }
        find("golos marcados")?.let { out.put("goals", it.toInt()) }
        find("media de golos")?.let { out.put("avg", it) }
        find("cartoes amarelos")?.let { out.put("yellow", it.toInt()) }
        find("cartoes vermelhos")?.let { out.put("red", it.toInt()) }
        find("vitorias em casa")?.let { out.put("homeW", it.toInt()) }
        find("vitorias fora")?.let { out.put("awayW", it.toInt()) }
        find("empates")?.let { out.put("draws", it.toInt()) }
        if (!out.has("avg") && out.has("games") && out.has("goals") && out.optInt("games") > 0) {
            out.put("avg", out.optInt("goals").toDouble() / out.optInt("games"))
        }
        return out
    }

    // ------------------------------------------------------------ missões

    private val RX_UNIT = Regex("(\\d{1,2})\\s*([dhm])\\b")

    fun unitSecs(text: String): Long? {
        val ms = RX_UNIT.findAll(text.lowercase()).toList()
        if (ms.isEmpty()) return null
        var s = 0L
        for (m in ms) s += m.groupValues[1].toLong() * when (m.groupValues[2]) { "d" -> 86400L; "h" -> 3600L; else -> 60L }
        return s
    }

    fun missions(o: OcrResult): JSONObject {
        fun after(label: String): Long? {
            val l = o.lines.firstOrNull { Txt.norm(it.text).contains(label) } ?: return null
            // o relógio mais perto do fim do rótulo (as duas frases ficam na mesma linha da tela)
            return o.lines.filter { abs(it.yc - l.yc) <= 0.04f && it.l >= l.l - 0.01f && unitSecs(it.text) != null }
                .minByOrNull { if (it === l) 0f else abs(it.l - l.r) }?.let { unitSecs(it.text) }
        }
        val out = JSONObject()
        after("novas missoes")?.let { out.put("newIn", it) }
        after("semana reinicia")?.let { out.put("weekIn", it) }
        return out
    }

    // ------------------------------------------------------------ elenco: especialistas recomendados

    data class Pick(val role: String, val player: PlayerEntity, val why: String)

    /**
     * Regras da comunidade: capitão = o mais velho entre os titulares (empate: o mais forte); pênaltis = melhor
     * atacante; faltas = melhor meia; escanteios = 2º melhor meia. Cada jogador acumula no máximo 2 funções.
     */
    fun recommendSpecialists(xi: List<PlayerEntity>): List<Pick> {
        if (xi.size < 6) return emptyList()
        val used = HashMap<String, Int>()
        fun take(role: String, list: List<PlayerEntity>, why: (PlayerEntity) -> String): Pick? {
            val p = list.firstOrNull { (used[it.nameKey] ?: 0) < 2 } ?: return null
            used[p.nameKey] = (used[p.nameKey] ?: 0) + 1
            return Pick(role, p, why(p))
        }
        val out = ArrayList<Pick>()
        take("capitao", xi.sortedWith(compareByDescending<PlayerEntity> { it.age ?: 0 }.thenByDescending { it.strength ?: 0 })) { "o mais experiente do XI (${it.age} anos)" }?.let { out.add(it) }
        take("penalti", xi.filter { it.cat == "ATA" }.sortedByDescending { it.strength ?: 0 }) { "melhor atacante (${it.strength})" }?.let { out.add(it) }
        val mids = xi.filter { it.cat == "MEI" }.sortedByDescending { it.strength ?: 0 }
        take("falta", mids) { "melhor meia (${it.strength})" }?.let { out.add(it) }
        take("escanteio", mids.drop(1).ifEmpty { mids }) { "2º melhor meia (${it.strength})" }?.let { out.add(it) }
        return out
    }
}
