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
    val FORMATIONS: Set<String> = Formations.ALL.toSet()

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

    private val STYLES = listOf("Jogo de passe", "Jogar pelas alas", "Remate à vista", "Contra-ataque", "Bolas longas")

    private fun pickKnown(value: String, options: List<String>, fallback: String): String {
        val n = Txt.norm(value)
        for (o in options) if (Txt.norm(o) == n) return o
        for (o in options) if (n.length >= 5 && (n.contains(Txt.norm(o)) || Txt.norm(o).contains(n))) return o
        return fallback
    }

    /** Só aceita nomes de opção que existem no jogo; o resto volta para a escolha calculada. */
    fun canonical(t: Tactic, base: Tactic): Tactic = t.copy(
        playStyle = pickKnown(t.playStyle, STYLES, base.playStyle),
        marking = pickKnown(t.marking, listOf("À zona", "Homem a homem"), base.marking),
        tackle = pickKnown(t.tackle, listOf("Normal", "Agressivo"), "Normal"),
        advAttack = pickKnown(t.advAttack, listOf("Atacar apenas", "Ajudar a defender"), base.advAttack),
        advMid = pickKnown(t.advMid, listOf("Manter posições", "Pressionar à frente", "Ajudar a defesa"), base.advMid),
        advDef = pickKnown(t.advDef, listOf("Defender atrás"), base.advDef),
        offside = if (t.offside == "Sim" || t.offside == "Não") t.offside else base.offside
    )

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

    // ------------------------------------------------------------ tática local (instantânea) + aprendizado

    private fun boolOf(v: String?, yes: String, no: String): Boolean? = when (v) {
        yes -> true
        no -> false
        else -> null
    }

    suspend fun history(repo: Repo, slot: Int): List<HistRow> =
        repo.dao.tacticLogs(slot).mapNotNull { p ->
            runCatching {
                val j = JSONObject(p.json)
                HistRow(
                    j.getString("formation"), j.optString("playStyle"), if (j.isNull("result")) null else j.optString("result"),
                    if (j.isNull("human")) null else j.optBoolean("human"), if (j.isNull("home")) null else j.optBoolean("home")
                )
            }.getOrNull()
        }

    suspend fun tacticInput(repo: Repo, slot: Int): TacticEngine.Input {
        val f = repo.fieldMap(slot)
        val players = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        return TacticEngine.Input(
            players = players,
            myStrength = f[K.MY_STRENGTH]?.value?.toIntOrNull(),
            rivalStrength = f[K.RIVAL_STRENGTH]?.value?.toIntOrNull(),
            rivalHuman = boolOf(f[K.RIVAL_HUMAN]?.value, "Sim", "Não"),
            rivalFormation = f[K.RIVAL_FORMATION]?.value,
            myDef = f[K.MY_DEF]?.value?.toIntOrNull(),
            rivalAtk = f[K.RIVAL_ATK]?.value?.toIntOrNull(),
            referee = f[K.REFEREE]?.value,
            home = boolOf(f[K.HOME]?.value, "Casa", "Fora"),
            history = history(repo, slot),
            myAtk = f[K.MY_ATK]?.value?.toIntOrNull(),
            myMid = f[K.MY_MID]?.value?.toIntOrNull(),
            rivalMid = f[K.RIVAL_MID]?.value?.toIntOrNull(),
            rivalDef = f[K.RIVAL_DEF]?.value?.toIntOrNull()
        )
    }

    /** Preenche o resultado das táticas já usadas quando o jogo daquela rodada aparece no calendário. */
    suspend fun resolveTacticLogs(repo: Repo, slot: Int) {
        val logs = repo.dao.tacticLogs(slot)
        if (logs.isEmpty()) return
        val matches = repo.dao.matchesOf(slot)
        for (p in logs) {
            val j = try { JSONObject(p.json) } catch (e: Exception) { continue }
            if (!j.isNull("result")) continue
            val r = j.optInt("round", -1)
            if (r < 0) continue
            val m = matches.firstOrNull { it.round == r && it.result != null } ?: continue
            j.put("result", m.result)
            j.put("scoreMine", m.scoreMine ?: JSONObject.NULL)
            j.put("scoreOpp", m.scoreOpp ?: JSONObject.NULL)
            repo.dao.putPlan(PlanEntity(slot, p.kind, j.toString(), p.at))
            repo.learn(
                slot, "resultado",
                "Tática ${j.optString("formation")} (${j.optString("playStyle")}) na J$r contra ${j.optString("rival")} → ${m.result} ${m.scoreMine}-${m.scoreOpp}"
            )
        }
    }

    /** Resultado informado à mão para uma tática ainda sem resultado (ex.: o calendário não foi lido). */
    suspend fun setLogResult(repo: Repo, slot: Int, kind: String, mine: Int, opp: Int) {
        val p = repo.dao.tacticLogs(slot).firstOrNull { it.kind == kind } ?: return
        val j = try { JSONObject(p.json) } catch (e: Exception) { return }
        val res = if (mine > opp) "V" else if (mine == opp) "E" else "D"
        j.put("result", res)
        j.put("scoreMine", mine)
        j.put("scoreOpp", opp)
        repo.dao.putPlan(PlanEntity(slot, p.kind, j.toString(), p.at))
        repo.learn(slot, "resultado", "Tática ${j.optString("formation")} (${j.optString("playStyle")}) contra ${j.optString("rival")} → $res $mine-$opp (informado)")
    }

    private fun lineupJson(rows: List<List<PlayerEntity?>>): JSONArray {
        val out = JSONArray()
        for (row in rows) {
            val r = JSONArray()
            for (p in row) {
                r.put(JSONObject().put("n", p?.name?.take(14) ?: "?").put("s", p?.strength ?: 0).put("p", p?.posCode ?: ""))
            }
            out.put(r)
        }
        return out
    }

    fun tacticPlanJson(res: TacticEngine.Result, round: Int?, rival: String?, refined: Boolean): JSONObject {
        val j = TacticValidator.toJson(res.tactic)
        j.put("lineup", lineupJson(res.rows))
        j.put("forRound", round ?: -1)
        j.put("rival", rival ?: NI)
        j.put("diff", res.diff ?: JSONObject.NULL)
        j.put("refined", refined)
        val rk = JSONArray()
        for ((f, sc) in res.ranking) rk.put(JSONArray().put(f).put(Math.round(sc)))
        j.put("ranking", rk)
        return j
    }

    private fun logJson(t: Tactic, round: Int?, rival: String?, inp: TacticEngine.Input): JSONObject =
        JSONObject().put("round", round ?: -1).put("rival", rival ?: NI).put("formation", t.formation)
            .put("playStyle", t.playStyle).put("pressure", t.pressure).put("mentality", t.mentality).put("tempo", t.tempo)
            .put("myStrength", inp.myStrength ?: JSONObject.NULL).put("rivalStrength", inp.rivalStrength ?: JSONObject.NULL)
            .put("human", inp.rivalHuman ?: JSONObject.NULL).put("home", inp.home ?: JSONObject.NULL)
            .put("result", JSONObject.NULL)

    /** Tática calculada por regras e números: instantânea, não depende de IA nem de internet. */
    suspend fun generateTacticLocal(repo: Repo, slot: Int): Outcome {
        resolveTacticLogs(repo, slot)
        val f = repo.fieldMap(slot)
        val inp = tacticInput(repo, slot)
        val squad = inp.players.count { it.strength != null }
        if (squad < 8) {
            return Outcome(false, null, "Dados insuficientes: só $squad jogadores do seu elenco foram lidos. Abra o Plantel do SEU time e role a lista inteira.")
        }
        val res = TacticEngine.recommend(inp) ?: return Outcome(false, null, "Não consegui montar um XI com o elenco lido.")
        val round = f[K.ROUND]?.value?.toIntOrNull()
        val rival = f[K.RIVAL_TEAM]?.value
        val now = System.currentTimeMillis()
        val json = tacticPlanJson(res, round, rival, false)
        repo.dao.putPlan(PlanEntity(slot, "tactic", json.toString(), now))
        repo.dao.putPlan(PlanEntity(slot, "tlog_R${round ?: 0}", logJson(res.tactic, round, rival, inp).toString(), now))
        return Outcome(true, json.toString(), null)
    }

    private fun tacticRefinePrompt(context: JSONObject, draft: JSONObject, statsText: String, allowed: List<String>): String =
        "Você é o analista tático do OSM 26. O rascunho abaixo foi calculado por regras e números (força do XI, confronto de forças, " +
            "árbitro, histórico). Revise com critério e devolva a tática final em JSON. " +
            "REGRAS OBRIGATÓRIAS: formation deve ser uma de $allowed; pressure, mentality e tempo (0 a 100) devem ficar a no máximo 15 pontos " +
            "do rascunho; contra rival bem mais fraco use formação ofensiva; árbitro Rigoroso => tackle Normal. " +
            "Responda APENAS JSON com as chaves: formation, playStyle, pressure, mentality, tempo, marking, offside, tackle, " +
            "advAttack, advMid, advDef, rationale (até 4 frases curtas citando números dos dados).\n\n" +
            "HISTÓRICO DAS SUAS TÁTICAS (resultado real):\n$statsText\n\nRASCUNHO:\n$draft\n\nDADOS:\n$context"

    /** Refinamento opcional por IA, dentro de limites: se sair deles, a tática local é mantida. */
    suspend fun refineTactic(ctx: Context, repo: Repo, slot: Int): Outcome {
        val curPlan = repo.dao.plan(slot, "tactic")
        val curRound = repo.fieldMap(slot)[K.ROUND]?.value?.toIntOrNull()
        val current = curPlan != null && curRound != null &&
            runCatching { JSONObject(curPlan.json).optInt("forRound", -1) == curRound }.getOrDefault(false)
        if (!current) return Outcome(false, null, "Gere a tática desta rodada primeiro: a IA só refina uma tática que você já pediu.")
        val inp = tacticInput(repo, slot)
        val res = TacticEngine.recommend(inp) ?: return Outcome(false, null, "Gere a tática local primeiro (faltam dados do elenco).")
        val f = repo.fieldMap(slot)
        val round = f[K.ROUND]?.value?.toIntOrNull()
        val rival = f[K.RIVAL_TEAM]?.value
        val draft = tacticPlanJson(res, round, rival, false)
        val statLines = ArrayList<String>()
        for (x in Learning.stats(inp.history)) statLines.add("formação ${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        for (x in Learning.byStyle(inp.history)) statLines.add("estilo ${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        for (x in Learning.byContext(inp.history)) statLines.add("${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        val stats = statLines.joinToString("\n").ifBlank { "sem jogos registrados ainda" }
        val allowed = res.ranking.take(3).map { it.first }
        val reply = AiClient.ask(ctx, tacticRefinePrompt(context(repo, slot), draft, stats, allowed), null)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) return Outcome(false, null, reply.error ?: "IA devolveu JSON inválido. Mantive a tática local.")
        val (validated, err) = TacticValidator.validate(json, inp.referee)
        if (validated == null) return Outcome(false, null, "$err. Mantive a tática local.")
        val base = res.tactic
        val tactic = TacticValidator.canonical(validated, base)
        if (tactic.formation !in allowed) return Outcome(false, null, "IA sugeriu ${tactic.formation}, fora das 3 melhores opções do cálculo. Mantive a tática local.")
        if (Math.abs(tactic.pressure - base.pressure) > 15 || Math.abs(tactic.mentality - base.mentality) > 15 || Math.abs(tactic.tempo - base.tempo) > 15) {
            return Outcome(false, null, "IA fugiu dos limites de pressão/mentalidade/ritmo. Mantive a tática local.")
        }
        val lineup = TacticEngine.lineup(tactic.formation, inp.players) ?: return Outcome(false, null, "Sem XI para ${tactic.formation}.")
        val notes = ArrayList<String>(tactic.notes)
        notes.addAll(base.notes)
        val merged = res.copy(tactic = tactic.copy(notes = notes), rows = lineup.first)
        val out = tacticPlanJson(merged, round, rival, true)
        val now = System.currentTimeMillis()
        repo.dao.putPlan(PlanEntity(slot, "tactic", out.toString(), now))
        repo.dao.putPlan(PlanEntity(slot, "tlog_R${round ?: 0}", logJson(tactic, round, rival, inp).toString(), now))
        return Outcome(true, out.toString(), null)
    }

    // ------------------------------------------------------------ mercado / treino

    suspend fun marketPlan(repo: Repo, slot: Int): MarketEngine.Plan? {
        val f = repo.fieldMap(slot)
        val mine = repo.dao.playersOf(slot).filter { it.owner == "MY" }
        if (mine.none { it.strength != null }) return null
        return MarketEngine.plan(
            mine, repo.dao.listingsOf(slot), Money.parse(f[K.CASH]?.value), MarketPlanner.sellSlotsLeft(f[K.SELLING]?.value)
        )
    }

    /** IA só comenta o plano calculado (não troca jogadores). */
    suspend fun marketNote(ctx: Context, repo: Repo, slot: Int): Outcome {
        val plan = marketPlan(repo, slot) ?: return Outcome(false, null, "Leia o elenco do SEU time antes de pedir análise.")
        val prompt = "Você é o diretor de mercado do OSM 26. O plano abaixo foi calculado por regras (metas 4 ATA, 6 MEI, 6 DEF, 2 GOL; " +
            "máximo 4 à venda). Comente em até 5 frases curtas: riscos, ordem de execução e o que priorizar para evoluir rápido. " +
            "NÃO invente jogadores nem preços. Responda APENAS JSON {\"commentary\": \"...\"}.\n\nPLANO:\n" + plan.steps.joinToString("\n") +
            "\n\nRESUMO: " + plan.summary
        val reply = AiClient.ask(ctx, prompt, null)
        val json = AiClient.parseJson(reply.text)
        if (!reply.ok || json == null) return Outcome(false, null, reply.error ?: "IA devolveu JSON inválido.")
        val text = json.optString("commentary").trim().take(700)
        if (text.isBlank()) return Outcome(false, null, "IA não devolveu comentário.")
        repo.dao.putPlan(PlanEntity(slot, "market", JSONObject().put("aiNote", text).toString(), System.currentTimeMillis()))
        return Outcome(true, text, null)
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
