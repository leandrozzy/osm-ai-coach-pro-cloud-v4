package com.osmaicoach.collector

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * Peças "inteligentes" do Diretor: tudo aqui aprende com o que o app já leu (vendas feitas, evolução dos
 * jogadores, táticas que cada rival humano usou) e cai para a regra da comunidade só quando ainda não há dados.
 */

// ---------------------------------------------------------------- 1) eventos do OSM

object OsmEvents {
    /** Tipos de evento e o que muda no Diretor. */
    enum class Kind(val label: String, val tip: String) {
        TRANSFER("Loucura de transferências", "a CPU compra quase tudo pelo máximo e a lista aceita até 6: venda e revenda o máximo possível agora"),
        STAFF("Super equipe técnica", "sessões de treino mais curtas: troque os treinos assim que terminarem"),
        EXTREME("Treino extremo", "o treino rende mais: mantenha os 4 treinadores sempre ocupados"),
        OLDIES("Veteranos de ouro", "veteranos evoluem mais: treine os mais velhos que são titulares"),
        CAMP("Estágio em promoção", "estágio mais barato: vale usar antes de um jogo decisivo"),
        FACILITIES("Melhorias do estádio", "obras do estádio mais baratas/rápidas: comece a próxima obra"),
        SCOUT("Olheiro barato", "o olheiro sai mais barato: use para comprar pelo valor e revender")
    }

    data class Ev(val kind: Kind, val start: Long?, val end: Long?, val source: String)

    /** Palavras do evento em inglês (nome oficial) e em português (como o jogo e os sites costumam escrever). */
    private val WORDS = listOf(
        Kind.TRANSFER to listOf("transfer madness", "transfer rush", "loucura de transfer", "loucura das transfer", "febre de transfer"),
        Kind.STAFF to listOf("super staff", "super equipa", "super equipe", "super treinador"),
        Kind.EXTREME to listOf("extreme training", "treino extremo"),
        Kind.OLDIES to listOf("golden oldies", "veteranos", "velha guarda"),
        Kind.CAMP to listOf("training camp mania", "mania de estagio", "estagio mania"),
        Kind.FACILITIES to listOf("top facilities", "instalacoes de topo", "super instalacoes"),
        Kind.SCOUT to listOf("bargain scout", "olheiro barato", "olheiro em promocao", "pechincha")
    )

    /** Evento citado num texto (tela do jogo ou resposta da pesquisa). */
    fun kindsIn(text: String): Set<Kind> {
        val t = Txt.norm(text)
        return WORDS.filter { (_, ws) -> ws.any { t.contains(it) } }.map { it.first }.toSet()
    }

    fun toJson(list: List<Ev>): String = JSONArray().apply {
        for (e in list) put(JSONObject().put("k", e.kind.name).put("s", e.start ?: JSONObject.NULL).put("e", e.end ?: JSONObject.NULL).put("src", e.source))
    }.toString()

    fun parse(json: String?): List<Ev> {
        val a = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val k = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: return@mapNotNull null
            Ev(k, if (o.isNull("s")) null else o.optLong("s"), if (o.isNull("e")) null else o.optLong("e"), o.optString("src"))
        }
    }

    /** Ativos agora: dentro do período (evento visto na tela sem data vale 24 h). */
    fun active(list: List<Ev>, now: Long): Set<Kind> =
        list.filter { (it.start == null || it.start <= now) && (it.end == null || it.end > now) }.map { it.kind }.toSet()

    /** Junta eventos novos aos guardados (mesmo tipo: fica o mais novo) e descarta os que já acabaram. */
    fun merge(old: List<Ev>, add: List<Ev>, now: Long): List<Ev> =
        (old.filter { o -> add.none { it.kind == o.kind } } + add).filter { it.end == null || it.end > now - 86400000L }

    /** Resposta da pesquisa: [{"event":"Transfer Madness","start":"2026-10-22","end":"2026-10-24"}]. */
    fun fromAi(json: JSONObject?, now: Long): List<Ev> {
        val arr = json?.optJSONArray("events") ?: return emptyList()
        val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
        val out = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val kinds = kindsIn(o.optString("event"))
            val s = runCatching { fmt.parse(o.optString("start"))?.time }.getOrNull()
            val e = runCatching { fmt.parse(o.optString("end"))?.time?.plus(86400000L) }.getOrNull()
            if (e != null && e < now) continue
            for (k in kinds) out.add(Ev(k, s, e, "internet"))
        }
        return out
    }

    const val PROMPT = "Pesquise na internet (osmhelper.com/events, fórum oficial do Online Soccer Manager, redes do OSM) quais " +
        "EVENTOS do jogo Online Soccer Manager (OSM) estão ativos hoje ou começam nos próximos 7 dias: Transfer Madness, " +
        "Super Staff, Extreme Training, Golden Oldies, Training Camp Mania, Top Facilities, Bargain Scout ou outros. " +
        "Responda APENAS JSON: {\"events\":[{\"event\":\"nome em inglês\",\"start\":\"AAAA-MM-DD\",\"end\":\"AAAA-MM-DD\"}]}. " +
        "Se não achar nenhum evento confirmado, responda {\"events\":[]}. Não invente datas."
}

/** Pesquisa os eventos do OSM uma vez por dia (em segundo plano) e guarda no plano 0 "events". */
object EventsRefresh {
    @Volatile private var running = false

    fun ifStale(ctx: android.content.Context) {
        if (running || !AiClient.hasAnyKey(ctx)) return
        val last = Settings.get(ctx, "events_try_at", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 24L * 3600000L) return
        running = true
        Settings.put(ctx, "events_try_at", System.currentTimeMillis().toString())
        AppScope.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val r = AiClient.askGrounded(ctx, OsmEvents.PROMPT)
                val now = System.currentTimeMillis()
                if (r.ok) {
                    val add = OsmEvents.fromAi(AiClient.parseJson(r.text), now)
                    val repo = Repo(ctx)
                    val old = OsmEvents.parse(repo.dao.plan(0, "events")?.json)
                    repo.dao.putPlan(PlanEntity(0, "events", OsmEvents.toJson(OsmEvents.merge(old.filter { it.source != "internet" }, add, now)), now))
                    Diag.log("Eventos do OSM: " + (add.joinToString { it.kind.label }.ifBlank { "nenhum ativo" }))
                }
            } catch (e: Exception) {
                Diag.log("Eventos do OSM: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                running = false
            }
        }
    }
}

// ---------------------------------------------------------------- 2/7) preço que realmente vende

object SellPrices {
    /**
     * Quanto a CPU pagou, em múltiplos do valor, nas vendas reais deste slot (livro de caixa × valor do jogador
     * no histórico). Com 3+ vendas vira a sugestão de preço; senão vale o teto da liga e a tabela da comunidade.
     */
    fun learnedRatio(ledger: List<Ledger.Entry>, hist: JSONObject): Pair<Double, Int>? {
        val ratios = ledger.filter { it.type == "sell" && !it.estimated && it.price != null }.mapNotNull { e ->
            val v = Growth.points(hist, e.key).lastOrNull { it.at <= e.at + 3600000L && it.value != null }?.value ?: return@mapNotNull null
            if (v <= 0.0) null else e.price!! / v
        }.filter { it in 0.5..3.0 }
        if (ratios.size < 3) return null
        val med = ratios.sorted()[ratios.size / 2]
        return Pair(med, ratios.size)
    }

    /** Teto lido na tela de venda: "Preço máximo 25M" com o valor 10M ao lado → 2,5. */
    fun ceilingFrom(text: String): Double? {
        val t = Txt.norm(text)
        val max = Regex("(?:preco )?max[a-z]*\\.?\\s*:?\\s*(\\d{1,3}(?:[.,]\\d{1,2})?\\s*[mk])\\b").find(t)?.groupValues?.get(1)
        val value = Regex("valor\\s*:?\\s*(\\d{1,3}(?:[.,]\\d{1,2})?\\s*[mk])\\b").find(t)?.groupValues?.get(1)
        val m = Money.parse(max?.replace(" ", "")?.uppercase()) ?: return null
        val v = Money.parse(value?.replace(" ", "")?.uppercase()) ?: return null
        if (v <= 0) return null
        val r = m / v
        // o OSM usa 2×, 2,25× ou 2,5×: arredonda para o mais perto
        return listOf(2.0, 2.25, 2.5).minByOrNull { abs(it - r) }?.takeIf { abs(it - r) <= 0.15 }
    }
}

// ---------------------------------------------------------------- 8) quem treinar

object TrainLearn {
    /** Faixas de idade usadas no aprendizado. */
    fun bucket(age: Int?): String = when {
        age == null -> "?"
        age <= 21 -> "até 21"
        age <= 24 -> "22–24"
        age <= 27 -> "25–27"
        age <= 30 -> "28–30"
        else -> "31+"
    }

    /** Pontos de força ganhos por dia de treino, por faixa de idade, medidos no histórico dos 4 slots. */
    fun rates(hists: List<JSONObject>, ages: Map<String, Int?>): Map<String, Pair<Double, Int>> {
        val gain = HashMap<String, Double>()
        val days = HashMap<String, Double>()
        val n = HashMap<String, Int>()
        for (h in hists) for (k in h.keys()) {
            val pts = Growth.points(h, k).filter { it.strength != null }
            val b = bucket(ages[k])
            for (i in 0 until pts.size - 1) {
                val a = pts[i]
                val c = pts[i + 1]
                if (!a.training) continue
                val d = (c.at - a.at) / 86400000.0
                if (d < 0.2 || d > 4.0) continue
                val g = (c.strength!! - a.strength!!).toDouble()
                if (g < 0 || g > 4) continue
                gain[b] = (gain[b] ?: 0.0) + g
                days[b] = (days[b] ?: 0.0) + d
                n[b] = (n[b] ?: 0) + 1
            }
        }
        return days.keys.filter { (days[it] ?: 0.0) >= 1.0 }.associateWith { Pair((gain[it] ?: 0.0) / days[it]!!, n[it] ?: 0) }
    }

    /**
     * Melhor jogador do setor para o treinador livre: maior ganho esperado (taxa aprendida da faixa de idade);
     * sem dados, o mais jovem (a comunidade confirma que jovens sobem mais rápido).
     */
    fun pick(cands: List<PlayerEntity>, rates: Map<String, Pair<Double, Int>>): Pair<PlayerEntity, Double?>? {
        if (cands.isEmpty()) return null
        val known = rates.filterValues { it.second >= 3 }
        if (known.size >= 2) {
            val best = cands.maxWithOrNull(compareBy<PlayerEntity> { known[bucket(it.age)]?.first ?: -1.0 }.thenBy { -(it.age ?: 99) }) ?: return null
            return Pair(best, known[bucket(best.age)]?.first)
        }
        return cands.sortedWith(compareBy<PlayerEntity> { it.age ?: 99 }.thenByDescending { it.strength ?: 0 }).firstOrNull()?.let { Pair(it, null) }
    }
}

// ---------------------------------------------------------------- 9) previsão da tática do rival humano

object RivalPredict {
    data class Guess(val formation: String, val style: String?, val share: Int, val seen: Int)
    data class View(val guesses: List<Guess>, val switches: Int, val checked: Int) {
        val switchRate: Int? get() = if (checked >= 2) (switches * 100 / checked) else null
    }

    /** Táticas que o apelido já usou (mais recentes pesam mais): a mais provável primeiro. */
    fun of(profile: JSONObject?): View? {
        val arr = profile?.optJSONArray("entries") ?: return null
        val list = (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.sortedBy { it.optLong("at") }
        if (list.isEmpty()) return null
        val w = HashMap<Pair<String, String?>, Double>()
        val c = HashMap<Pair<String, String?>, Int>()
        list.forEachIndexed { i, e ->
            val f = Formations.canonical(e.optString("formation")) ?: e.optString("formation").ifBlank { return@forEachIndexed }
            val key = Pair(f, Osm.style(e.optString("plan")))
            val weight = 1.0 + i * 0.5
            w[key] = (w[key] ?: 0.0) + weight
            c[key] = (c[key] ?: 0) + 1
        }
        val tot = w.values.sum().takeIf { it > 0 } ?: return null
        val g = w.entries.sortedByDescending { it.value }.map { (k, v) -> Guess(k.first, k.second, (v * 100 / tot).roundToInt(), c[k] ?: 0) }
        return View(g, profile.optInt("switches"), profile.optInt("checked"))
    }
}

// ---------------------------------------------------------------- 4) bônus de nacionalidade

object Nations {
    /** Titulares por país (a comunidade fala em 6 titulares do mesmo país; algumas fontes dizem 5). */
    fun best(xi: Set<String>, nation: Map<String, String>): Pair<String, Int>? =
        xi.mapNotNull { nation[it] }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair()
}
