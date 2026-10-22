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
    /** Tipos de evento e o que muda no Diretor (só o que as fontes confirmam; o resto manda ver no jogo). */
    enum class Kind(val label: String, val tip: String) {
        TRANSFER("Loucura de transferências", "a CPU compra quase tudo pelo máximo e a lista aceita até 6 jogadores: venda e revenda o máximo possível"),
        STAFF("Super equipe técnica", "sessões de treino mais curtas (4 h): troque os treinos assim que terminarem"),
        EXTREME("Treino extremo", "o treino rende mais: mantenha os 4 treinadores sempre ocupados"),
        OLDIES("Veteranos de ouro × Lendas", "veteranos evoluem mais: treine os mais velhos que são titulares"),
        CAMP("Mania de estágio", "estágios em promoção: veja o preço no jogo antes de um jogo decisivo"),
        FACILITIES("Instalações de topo", "pode baratear ou acelerar obras do estádio: confira no jogo e comece a próxima obra"),
        SCOUT("Olheiro barato", "o relatório do olheiro leva 2 h (normalmente 16 h) e os jogadores saem mais baratos: compre pelo valor e revenda"),
        LEGENDS("Lendas", "evento de jogadores lendários: confira as ofertas no jogo"),
        BLITZ("Blitz do estádio", "evento do estádio: confira as condições no jogo"),
        FRIENDLY("Amistosos intensos", "amistosos rendem mais: confira as condições no jogo"),
        OTHER("Evento do OSM", "confira os detalhes no jogo")
    }

    /** [kind] + nome original do calendário; [start, end) em milissegundos. */
    data class Ev(val kind: Kind, val start: Long?, val end: Long?, val source: String, val name: String = kind.label)

    const val SOURCE = "calendario"
    const val URL = "https://www.osmnextlevel.com/en/osm-events/"
    /** O calendário da fonte conta os dias no fuso de Istambul; a hora exata do jogo não é conhecida. */
    private val TZ = java.util.TimeZone.getTimeZone("Europe/Istanbul")

    fun kindOf(name: String): Kind {
        val t = Txt.norm(name)
        return when {
            t.contains("transfer") -> Kind.TRANSFER
            t.contains("super staff") -> Kind.STAFF
            t.contains("extreme training") -> Kind.EXTREME
            t.contains("golden oldies") -> Kind.OLDIES
            t.contains("training camp") -> Kind.CAMP
            t.contains("top facilities") -> Kind.FACILITIES
            t.contains("bargain scout") -> Kind.SCOUT
            t.contains("stadium blitz") -> Kind.BLITZ
            t.contains("friendl") -> Kind.FRIENDLY
            t.contains("legends") -> Kind.LEGENDS
            else -> Kind.OTHER
        }
    }

    fun toJson(list: List<Ev>): String = JSONArray().apply {
        for (e in list) put(JSONObject().put("k", e.kind.name).put("n", e.name).put("s", e.start ?: JSONObject.NULL).put("e", e.end ?: JSONObject.NULL).put("src", e.source))
    }.toString()

    fun parse(json: String?): List<Ev> {
        val a = runCatching { JSONArray(json ?: "[]") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val k = runCatching { Kind.valueOf(o.optString("k")) }.getOrNull() ?: return@mapNotNull null
            Ev(k, if (o.isNull("s")) null else o.optLong("s"), if (o.isNull("e")) null else o.optLong("e"), o.optString("src"), o.optString("n").ifBlank { k.label })
        }
    }

    /** Só vale o que veio do calendário e tem datas: texto de tela ou resposta de IA já deu evento falso. */
    private fun trusted(e: Ev) = e.source == SOURCE && e.start != null && e.end != null

    /** Ativos agora (início ≤ agora < fim). */
    fun active(list: List<Ev>, now: Long): List<Ev> = list.filter { trusted(it) && it.start!! <= now && now < it.end!! }

    /** Começam nos próximos [days] dias. */
    fun upcoming(list: List<Ev>, now: Long, days: Int = 3): List<Ev> =
        list.filter { trusted(it) && it.start!! > now && it.start - now <= days * 86400000L }.sortedBy { it.start }

    /**
     * Lê o calendário do mês embutido na página: `var D={"y":2026,"m":9,"e":[{"d":[10,11],"n":"Transfer Madness"}…]}`
     * (m começa em 0). Devolve null se a estrutura mudou ou se a página é de outro mês (aí ninguém é dado como ativo).
     */
    fun fromCalendarHtml(html: String, now: Long): List<Ev>? {
        val m = Regex("var D=(\\{.*?\\});\\s*var p=", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        val j = runCatching { JSONObject(m.groupValues[1]) }.getOrNull() ?: return null
        val y = j.optInt("y", -1)
        val mo = j.optInt("m", -1)
        val arr = j.optJSONArray("e") ?: return null
        if (y < 2000 || mo !in 0..11) return null
        val cur = java.util.Calendar.getInstance(TZ).apply { timeInMillis = now }
        // página do mês passado/seguinte: não dá para dizer o que está ativo
        if (cur.get(java.util.Calendar.YEAR) != y || cur.get(java.util.Calendar.MONTH) != mo) return null
        fun dayStart(d: Int): Long = java.util.Calendar.getInstance(TZ).apply {
            clear(); set(y, mo, d, 0, 0, 0)
        }.timeInMillis
        val out = ArrayList<Ev>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = o.optString("n")
            if (name.isBlank()) continue
            val days = o.optJSONArray("d") ?: continue
            val ds = (0 until days.length()).map { days.optInt(it) }.filter { it in 1..31 }.sorted()
            if (ds.isEmpty()) continue
            // dias seguidos viram um período só
            var runStart = ds[0]
            var prev = ds[0]
            fun flush(a: Int, b: Int) { out.add(Ev(kindOf(name), dayStart(a), dayStart(b) + 86400000L, SOURCE, name)) }
            for (d in ds.drop(1)) {
                if (d != prev + 1) { flush(runStart, prev); runStart = d }
                prev = d
            }
            flush(runStart, prev)
        }
        return out
    }

    /** Texto curto de um evento para o Diretor/bolha. */
    fun describe(e: Ev, now: Long): String {
        val fmt = java.text.SimpleDateFormat("dd/MM", java.util.Locale.getDefault()).apply { timeZone = TZ }
        val last = fmt.format(java.util.Date(e.end!! - 1))
        return if (e.start!! <= now) "até $last" else "começa em ${fmt.format(java.util.Date(e.start))}"
    }
}

/** Lê o calendário de eventos da comunidade (a cada 6 h, em segundo plano). Nada de IA: ela misturava os dias. */
object EventsRefresh {
    @Volatile private var running = false

    fun ifStale(ctx: android.content.Context) {
        if (running) return
        val last = Settings.get(ctx, "events_try_at", "0").toLongOrNull() ?: 0L
        if (System.currentTimeMillis() - last < 6L * 3600000L) return
        running = true
        Settings.put(ctx, "events_try_at", System.currentTimeMillis().toString())
        AppScope.scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val html = fetch(OsmEvents.URL)
                val ev = html?.let { OsmEvents.fromCalendarHtml(it, now) }
                val repo = Repo(ctx)
                // falhou ou a página é de outro mês: apaga (melhor não dizer nada do que dizer errado)
                repo.dao.putPlan(PlanEntity(0, "events", OsmEvents.toJson(ev ?: emptyList()), now))
                if (ev != null) Settings.put(ctx, "events_ok_at", now.toString())
                Diag.log("Eventos do OSM (calendário): " + (ev?.let { l -> OsmEvents.active(l, now).joinToString { it.name }.ifBlank { "nenhum hoje" } } ?: "não confirmado"))
            } catch (e: Exception) {
                Diag.log("Eventos do OSM: " + (e.message ?: e.javaClass.simpleName))
            } finally {
                running = false
            }
        }
    }

    private fun fetch(url: String): String? = runCatching {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) OSMCoach")
        if (c.responseCode !in 200..299) null else c.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
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

    /**
     * Tela "Definir um preço para <jogador>": o preço do controle deslizante (começa no máximo) dividido pelo valor
     * ("Ganhar imediatamente 4,4M"). Só conta se cair num teto que o OSM usa (2×, 2,25× ou 2,5×): o jogador pode ter
     * mexido no controle, e aí a razão não diz nada.
     */
    fun fromScreen(o: OcrResult): Double? {
        fun money(t: String): Double? = Money.extract(t)?.let { Money.parse(it) }
        val quick = o.lines.firstOrNull { Txt.norm(it.text).contains("ganhar imediatamente") } ?: return null
        val value = (money(quick.text) ?: o.tokens.filter { abs(it.yc - quick.yc) <= 0.03f && it.xc > quick.xc }.sortedBy { it.xc }
            .firstNotNullOfOrNull { money(it.text) }) ?: return null
        val head = o.lines.firstOrNull { Txt.norm(it.text).startsWith("definir um preco") } ?: return null
        val price = o.tokens.filter { it.xc > 0.7f && it.yc > head.yc && it.yc < head.yc + 0.14f }.mapNotNull { money(it.text) }.maxOrNull() ?: return null
        if (value <= 0) return null
        val r = price / value
        return listOf(2.0, 2.25, 2.5).minByOrNull { abs(it - r) }?.takeIf { abs(it - r) <= 0.06 }
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

// ---------------------------------------------------------------- patrocínio

object Sponsors {
    /** Contrato de patrocínio: jogos em casa que faltam e quanto paga por jogo em casa (milhões). */
    data class Deal(val games: Int, val perGameM: Double)

    private val RX_GAMES = Regex("falt(?:a|am)\\s+(\\d{1,2})\\s+jog")
    private val RX_PAY = Regex("(\\d{1,3}(?:[.,]\\d{1,2})?\\s*[km])\\s*por cada jogo")

    /** Tela de patrocinadores: 4 cartões "Acordo de patrocínio / falta N jogo(s) em casa / + 92K por cada jogo em casa". */
    fun parse(o: OcrResult): List<Deal> {
        val heads = o.lines.filter { Txt.norm(it.text).contains("acordo de patrocinio") }
        val out = ArrayList<Deal>()
        for (h in heads) {
            fun near(pred: (OcrLine) -> Boolean) = o.lines.filter { it !== h && abs(it.xc - h.xc) <= 0.10f && it.yc > h.yc && it.yc - h.yc <= 0.32f && pred(it) }
                .minByOrNull { it.yc }
            val g = near { RX_GAMES.containsMatchIn(Txt.norm(it.text)) }?.let { RX_GAMES.find(Txt.norm(it.text))?.groupValues?.get(1)?.toIntOrNull() }
            val p = near { RX_PAY.containsMatchIn(Txt.norm(it.text)) }?.let {
                RX_PAY.find(Txt.norm(it.text))?.groupValues?.get(1)?.replace(" ", "")?.uppercase()?.let { m -> Money.parse(m) }
            }
            if (g != null && p != null) out.add(Deal(g, p))
        }
        return out
    }

    fun toJson(deals: List<Deal>, at: Long): String = JSONObject().put("at", at).put("deals", JSONArray().apply {
        for (d in deals) put(JSONObject().put("g", d.games).put("p", d.perGameM))
    }).toString()

    fun fromJson(json: String?): Pair<List<Deal>, Long>? {
        val j = json?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val a = j.optJSONArray("deals") ?: return null
        return Pair((0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Deal(it.optInt("g"), it.optDouble("p")) }, j.optLong("at"))
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
