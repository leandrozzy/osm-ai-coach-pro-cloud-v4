package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Tactic(
    val formation: String,
    val playStyle: String,
    val pressure: Int,
    val mentality: Int,
    val tempo: Int,
    val marking: String,
    val offside: String,
    val tackle: String,
    val advAttack: String,
    val advMid: String,
    val advDef: String,
    val notes: List<String>
)

object TacticValidator {
    val FORMATIONS = setOf(
        "4-4-2", "4-3-3", "3-5-2", "4-5-1", "5-3-2", "4-2-3-1", "3-4-3", "5-4-1", "4-1-4-1", "4-3-2-1", "4-1-3-2"
    )

    private fun slider(j: JSONObject, key: String): Int? {
        if (!j.has(key) || j.isNull(key)) return null
        val v = j.optDouble(key, Double.NaN)
        if (v.isNaN()) return null
        val n = v.toInt()
        return if (n in 0..100) n else null
    }

    private fun text(j: JSONObject, key: String): String {
        val t = j.optString(key, NI).trim()
        return if (FieldMerge.known(t) && t.length <= 40) t else NI
    }

    /** Devolve (tática, erro). Sliders precisam ser numéricos 0..100; formação precisa existir. */
    fun validate(j: JSONObject, refereeSeverity: String?): Pair<Tactic?, String?> {
        val formation = j.optString("formation").trim().uppercase().replace(" ", "")
        if (formation !in FORMATIONS) return Pair(null, "Formação inválida: '$formation'")
        val pressure = slider(j, "pressure") ?: return Pair(null, "Pressão inválida")
        val mentality = slider(j, "mentality") ?: return Pair(null, "Mentalidade/estilo inválido")
        val tempo = slider(j, "tempo") ?: return Pair(null, "Ritmo/temporização inválido")
        val notes = ArrayList<String>()
        val ra = j.optJSONArray("rationale")
        if (ra != null) for (i in 0 until minOf(ra.length(), 6)) notes.add(ra.optString(i).take(160))
        var tackle = text(j, "tackle")
        if (refereeSeverity == "Rigoroso" && Txt.norm(tackle).contains("agress")) {
            tackle = "Normal"
            notes.add("Árbitro rigoroso: desarme agressivo foi trocado por Normal para evitar cartões.")
        }
        val offside = when (Txt.norm(j.optString("offside"))) {
            "sim" -> "Sim"
            "nao" -> "Não"
            else -> NI
        }
        return Pair(
            Tactic(
                formation = formation, playStyle = text(j, "playStyle"), pressure = pressure,
                mentality = mentality, tempo = tempo, marking = text(j, "marking"), offside = offside,
                tackle = tackle, advAttack = text(j, "advAttack"), advMid = text(j, "advMid"),
                advDef = text(j, "advDef"), notes = notes
            ),
            null
        )
    }

    fun toJson(t: Tactic): JSONObject = JSONObject()
        .put("formation", t.formation).put("playStyle", t.playStyle).put("pressure", t.pressure)
        .put("mentality", t.mentality).put("tempo", t.tempo).put("marking", t.marking)
        .put("offside", t.offside).put("tackle", t.tackle).put("advAttack", t.advAttack)
        .put("advMid", t.advMid).put("advDef", t.advDef).put("rationale", JSONArray(t.notes))
}

object MarketValidator {
    /** Remove entradas inventadas: vender/treinar só jogadores do elenco; comprar só quem está na lista lida. */
    fun sanitize(j: JSONObject, squadKeys: Set<String>, listingPrices: Map<String, Double?>, cashMillions: Double?, sellSlots: Int): JSONObject {
        val out = JSONObject()
        val sell = JSONArray()
        val sa = j.optJSONArray("sell")
        if (sa != null) {
            for (i in 0 until sa.length()) {
                val o = sa.optJSONObject(i) ?: continue
                if (Txt.key(o.optString("name")) in squadKeys && sell.length() < sellSlots) sell.put(o)
            }
        }
        val buy = JSONArray()
        val ba = j.optJSONArray("buy")
        if (ba != null) {
            for (i in 0 until ba.length()) {
                val o = ba.optJSONObject(i) ?: continue
                val k = Txt.key(o.optString("name"))
                if (!listingPrices.containsKey(k)) continue
                val price = listingPrices[k]
                if (cashMillions != null && price != null && price > cashMillions) continue
                buy.put(o)
            }
        }
        val train = JSONArray()
        val ta = j.optJSONArray("train")
        if (ta != null) {
            for (i in 0 until ta.length()) {
                val o = ta.optJSONObject(i) ?: continue
                if (Txt.key(o.optString("name")) in squadKeys) train.put(o)
            }
        }
        out.put("sell", sell).put("buy", buy).put("train", train)
        out.put("summary", j.optString("summary").take(400))
        return out
    }
}

object Director {
    data class Outcome(val ok: Boolean, val json: String?, val error: String?)

    suspend fun context(repo: Repo, slot: Int, forMarket: Boolean = false): JSONObject {
        val dao = repo.dao
        val f = repo.fieldMap(slot)
        val ctx = JSONObject()
        val fields = JSONObject()
        for ((k, v) in f) if (FieldMerge.known(v.value)) fields.put(k, v.value)
        ctx.put("campos", fields)

        val players = dao.playersOf(slot)
        val mine = players.filter { it.owner == "MY" }.sortedWith(compareBy<PlayerEntity>({ it.cat ?: "Z" }, { -(it.strength ?: 0) }))
        val arr = JSONArray()
        for (p in mine) {
            arr.put(
                JSONObject().put("nome", p.name).put("pos", p.posCode ?: NI).put("cat", p.cat ?: NI)
                    .put("idade", p.age ?: NI).put("forca", p.strength ?: NI).put("valor", p.valueText ?: NI)
                    .put("treinando", p.training ?: NI).put("avenda", p.forSale ?: NI)
            )
        }
        ctx.put("meu_elenco", arr)
        val rival = JSONArray()
        for (p in players.filter { it.owner == "RIVAL" }.sortedByDescending { it.strength ?: 0 }.take(18)) {
            rival.put(JSONObject().put("nome", p.name).put("cat", p.cat ?: NI).put("forca", p.strength ?: NI))
        }
        ctx.put("elenco_rival_lido", rival)

        val matches = dao.matchesOf(slot).sortedBy { it.round ?: 999 }.let { all ->
            all.filter { it.result != null }.takeLast(8) + all.filter { it.result == null }.take(3)
        }
        val played = JSONArray()
        val future = JSONArray()
        for (m in matches) {
            val o = JSONObject().put("rodada", m.label).put("adversario", m.opponent ?: NI)
                .put("humano", m.opponentNick ?: NI).put("casa", m.home ?: NI)
            if (m.result != null) {
                played.put(o.put("resultado", m.result).put("placar", "${m.scoreMine}x${m.scoreOpp}"))
            } else future.put(o.put("horario", m.time ?: NI))
        }
        ctx.put("jogos_feitos", played)
        ctx.put("proximos_jogos", future)

        val counts = HashMap<String, Int>()
        for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val needs = JSONObject()
        for (n in MarketPlanner.needs(counts)) needs.put(n.cat, JSONObject().put("tenho", n.have).put("meta", n.target))
        ctx.put("necessidades_elenco", needs)
        ctx.put("vagas_para_vender", MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value))

        val listings = JSONArray()
        for (l in (if (forMarket) dao.listingsOf(slot).sortedByDescending { it.strength ?: 0 }.take(40) else emptyList())) {
            listings.put(
                JSONObject().put("nome", l.name).put("cat", l.cat ?: NI).put("idade", l.age ?: NI)
                    .put("forca", l.strength ?: NI).put("preco", l.priceText ?: NI)
            )
        }
        ctx.put("mercado_lido", listings)
        return ctx
    }

    fun tacticPrompt(context: JSONObject): String =
        "Você é o diretor técnico do OSM 26 (Online Soccer Manager). Monte a tática COMPLETA para o próximo jogo " +
            "usando SOMENTE os dados abaixo. \"NI\" significa desconhecido: não presuma. " +
            "Regras: não escolha sempre 4-3-3; use a composição do elenco e o adversário (4-5-1, 5-3-2, 4-2-3-1, 4-4-2, 3-5-2 etc.); " +
            "árbitro \"Rigoroso\" => desarme NÃO agressivo; sliders são números inteiros de 0 a 100. " +
            "Responda APENAS JSON com as chaves: formation (ex.: \"4-5-1\"), playStyle (ex.: \"Jogo de passe\", \"Jogar pelas alas\", \"Remate à vista\"), " +
            "pressure (0-100), mentality (0-100, estilo ofensivo/defensivo), tempo (0-100, temporização), " +
            "marking (\"À zona\" ou \"Homem a homem\"), offside (\"Sim\"/\"Não\"), tackle (\"Normal\", \"Agressivo\" ou o mais cuidadoso disponível), " +
            "advAttack (ex.: \"Atacar apenas\" ou \"Ajudar a defender\"), advMid (ex.: \"Manter posições\", \"Pressionar à frente\", \"Ajudar a defesa\"), " +
            "advDef (ex.: \"Defender atrás\"), rationale (lista de até 5 frases curtas citando os dados usados).\n\nDADOS:\n" +
            context.toString()

    fun marketPrompt(context: JSONObject): String =
        "Você é o diretor de mercado do OSM 26. Objetivo: evoluir o elenco o mais rápido possível mantendo 4 ATA, 6 MEI, 6 DEF e 2 GOL, " +
            "com no máximo 4 jogadores à venda ao mesmo tempo (veja vagas_para_vender). " +
            "Use SOMENTE os dados abaixo; \"NI\" é desconhecido. Só sugira vender/treinar jogadores de meu_elenco e só sugira comprar jogadores de mercado_lido " +
            "cujo preço caiba no caixa (campos.cash). Priorize cobrir posições faltantes, depois trocar o mais fraco de cada posição por alguém mais forte e jovem. " +
            "Responda APENAS JSON: sell (lista de {name, reason}), buy (lista de {name, reason}), " +
            "train (lista de {name, trainer: \"avançados\"|\"médios\"|\"defesas\"|\"guarda-redes\", reason}), summary (texto curto).\n\nDADOS:\n" +
            context.toString()

    suspend fun generateTactic(ctx: Context, repo: Repo, slot: Int): Outcome {
        val c = context(repo, slot)
        val f = repo.fieldMap(slot)
        val squadCount = c.getJSONArray("meu_elenco").length()
        if (f[K.TEAM] == null || squadCount < 8) {
            val faltam = ArrayList<String>()
            if (f[K.TEAM] == null) faltam.add("pré-jogo (time do slot)")
            if (squadCount < 8) faltam.add("meu elenco ($squadCount de pelo menos 8 jogadores lidos — abra o elenco do SEU time e role a lista)")
            return Outcome(false, null, "Dados insuficientes. Falta ler: " + faltam.joinToString("; ") + ".")
        }
        val reply = AiClient.ask(ctx, tacticPrompt(c), null)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) return Outcome(false, null, reply.error ?: "IA devolveu JSON inválido.")
        val (tactic, err) = TacticValidator.validate(json, f[K.REFEREE]?.value)
        if (tactic == null) return Outcome(false, null, err)
        val out = TacticValidator.toJson(tactic).toString()
        repo.dao.putPlan(PlanEntity(slot, "tactic", out, System.currentTimeMillis()))
        return Outcome(true, out, null)
    }

    suspend fun generateMarket(ctx: Context, repo: Repo, slot: Int): Outcome {
        val c = context(repo, slot, true)
        val f = repo.fieldMap(slot)
        val squadCount = c.getJSONArray("meu_elenco").length()
        if (squadCount < 8) {
            return Outcome(false, null, "Dados insuficientes: só $squadCount jogadores do seu elenco foram lidos. Abra o elenco do SEU time e role a lista inteira.")
        }
        val reply = AiClient.ask(ctx, marketPrompt(c), null)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) return Outcome(false, null, reply.error ?: "IA devolveu JSON inválido.")
        val players = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        val prices = HashMap<String, Double?>()
        for (l in repo.dao.listingsOf(slot)) prices[l.nameKey] = Money.parse(l.priceText)
        val clean = MarketValidator.sanitize(
            json, players.map { it.nameKey }.toSet(), prices, Money.parse(f[K.CASH]?.value),
            MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value)
        )
        val out = clean.toString()
        repo.dao.putPlan(PlanEntity(slot, "market", out, System.currentTimeMillis()))
        return Outcome(true, out, null)
    }

    /** Plano local, sem IA: o que falta por posição e quem comprar com o caixa atual. */
    suspend fun marketBaseline(repo: Repo, slot: Int): List<String> {
        val f = repo.fieldMap(slot)
        val mine = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        if (mine.isEmpty()) return listOf("Leia o elenco do SEU time neste slot (menu do jogo → Plantel) e role a lista para ver o plano.")
        val counts = HashMap<String, Int>()
        for (p in mine) p.cat?.let { counts[it] = (counts[it] ?: 0) + 1 }
        val cashTxt = f[K.CASH]?.value
        val cash = Money.parse(cashTxt)
        val listings = repo.dao.listingsOf(slot)
        val out = ArrayList<String>()
        out.add("Caixa: ${cashTxt ?: NI} • vagas para vender: ${MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value)} (máx. ${MarketPlanner.MAX_SELLING}) • jogadores do mercado lidos: ${listings.size}")
        for (n in MarketPlanner.needs(counts)) {
            if (n.missing > 0) {
                if (listings.isEmpty()) {
                    out.add("Falta ${n.missing} ${n.cat} (tenho ${n.have}/${n.target}): abra a Lista de transferências e role para eu ler o mercado.")
                    continue
                }
                val cand = listings.filter { it.cat == n.cat && it.strength != null }.sortedByDescending { it.strength }.take(3)
                if (cand.isEmpty()) {
                    out.add("Falta ${n.missing} ${n.cat} (tenho ${n.have}/${n.target}): nenhum ${n.cat} lido ainda na lista.")
                } else {
                    val txt = cand.joinToString("; ") {
                        val price = Money.parse(it.priceText)
                        val short = if (cash != null && price != null && price > cash) " — falta caixa" else ""
                        "${it.name} (${it.strength}, ${it.priceText}, ${it.age ?: "?"} anos)$short"
                    }
                    out.add("Falta ${n.missing} ${n.cat} (tenho ${n.have}/${n.target}): $txt")
                }
            } else if (n.surplus > 0) {
                val weak = mine.filter { it.cat == n.cat }.sortedBy { it.strength ?: 999 }.take(n.surplus)
                out.add("Excesso de ${n.surplus} ${n.cat}: vender " + weak.joinToString { "${it.name} (${it.strength ?: "?"})" })
            } else {
                out.add("${n.cat}: ${n.have}/${n.target} ok")
            }
        }
        return out
    }
}
