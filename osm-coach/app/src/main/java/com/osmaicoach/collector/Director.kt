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
    val FORMATIONS: Set<String> get() = Formations.ALL.toSet()

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
        val raw = j.optString("formation").trim()
        val formation = Formations.canonical(raw)?.takeIf { Formations.base(it) in Formations.BASES }
            ?: return Pair(null, "Formação inválida: '$raw'")
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


    private fun pickKnown(value: String, options: List<String>, fallback: String): String {
        val n = Txt.norm(value)
        for (o in options) if (Txt.norm(o) == n) return o
        for (o in options) if (n.length >= 5 && (n.contains(Txt.norm(o)) || Txt.norm(o).contains(n))) return o
        return fallback
    }

    /** Só aceita nomes de opção que existem no jogo; o resto volta para a escolha calculada. */
    fun canonical(t: Tactic, base: Tactic): Tactic = t.copy(
        formation = Formations.canonical(t.formation)?.let { c -> if (c.contains(" ")) c else Formations.variant(c, Osm.style(t.playStyle)) } ?: base.formation,
        playStyle = pickKnown(t.playStyle, Osm.STYLES, base.playStyle),
        marking = pickKnown(t.marking, Osm.MARKING, base.marking),
        tackle = pickKnown(t.tackle, Osm.TACKLES, "Normal"),
        advAttack = pickKnown(t.advAttack, Osm.ATTACK, base.advAttack),
        advMid = pickKnown(t.advMid, Osm.MIDFIELD, base.advMid),
        advDef = pickKnown(t.advDef, Osm.DEFENSE, base.advDef),
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
            "Regras: não escolha sempre 4-3-3; use a composição do elenco e o adversário. Formações do OSM (nome exato, com a letra): ${Formations.ALL}. " +
            "Valores exatos do jogo: playStyle ${Osm.STYLES}; marking ${Osm.MARKING}; tackle ${Osm.TACKLES}; advAttack ${Osm.ATTACK}; " +
            "advMid ${Osm.MIDFIELD}; advDef ${Osm.DEFENSE}. " +
            "árbitro \"Rigoroso\" => desarme NÃO agressivo; sliders são números inteiros de 0 a 100. " +
            "Responda APENAS JSON com as chaves: formation (ex.: \"4-5-1\"), playStyle (ex.: \"Jogo de passe\", \"Jogar pelas alas\", \"Remate à vista\"), " +
            "pressure (0-100), mentality (0-100, estilo ofensivo/defensivo), tempo (0-100, temporização), " +
            "marking (\"À zona\" ou \"Homem-a-homem\"), offside (\"Sim\"/\"Não\"), tackle (\"Normal\", \"Agressivo\" ou o mais cuidadoso disponível), " +
            "advAttack (ex.: \"Atacar apenas\" ou \"Ajudar a defesa\"), advMid (ex.: \"Manter posição\", \"Pressionar na frente\", \"Ajudar a defesa\"), " +
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
                    if (j.isNull("human")) null else j.optBoolean("human"), if (j.isNull("home")) null else j.optBoolean("home"),
                    if (j.has("myFouls")) j.optInt("myFouls") else null, if (j.has("myPossession")) j.optInt("myPossession") else null
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
            rivalFormation = f[K.RIVAL_FORMATION]?.value?.takeIf { FieldMerge.known(it) } ?: repo.rivalProfileFormation(slot),
            myDef = f[K.MY_DEF]?.value?.toIntOrNull(),
            rivalAtk = f[K.RIVAL_ATK]?.value?.toIntOrNull(),
            referee = f[K.REFEREE]?.value,
            home = boolOf(f[K.HOME]?.value, "Casa", "Fora"),
            history = history(repo, slot),
            myAtk = f[K.MY_ATK]?.value?.toIntOrNull(),
            myMid = f[K.MY_MID]?.value?.toIntOrNull(),
            rivalMid = f[K.RIVAL_MID]?.value?.toIntOrNull(),
            rivalDef = f[K.RIVAL_DEF]?.value?.toIntOrNull(),
            fitness = repo.fitness(slot)
        )
    }

    /** Força média de cada setor do XI (linha 0 = goleiro, 1 = defesa, última = ataque, o resto = meio). */
    private fun xi(rows: List<List<Int>>): List<Double?> {
        fun avg(l: List<Int>): Double? = l.filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
        if (rows.size < 3) return listOf(null, null, null, null)
        return listOf(avg(rows[0]), avg(rows[1]), avg(rows.subList(2, rows.size - 1).flatten()), avg(rows.last()))
    }

    /** Tática guardada (JSON do plano) no formato do simulador. */
    fun simPlan(j: JSONObject): WinModel.Plan? {
        val f = j.optString("formation").ifBlank { return null }
        val rows = ArrayList<List<Int>>()
        val arr = j.optJSONArray("lineup")
        if (arr != null) for (i in 0 until arr.length()) {
            val r = arr.optJSONArray(i) ?: continue
            rows.add((0 until r.length()).map { r.optJSONObject(it)?.optInt("s") ?: 0 })
        }
        val x = xi(rows)
        return WinModel.Plan(
            f, j.optString("playStyle"), j.optInt("pressure", 50), j.optInt("mentality", 50), j.optInt("tempo", 50),
            j.optString("marking"), j.optString("offside"), j.optString("tackle"), j.optString("advAttack"), j.optString("advMid"),
            x[0], x[1], x[2], x[3]
        )
    }

    fun simPlan(t: Tactic, rows: List<List<PlayerEntity?>>): WinModel.Plan {
        val x = xi(rows.map { r -> r.map { it?.strength ?: 0 } })
        return WinModel.Plan(
            t.formation, t.playStyle, t.pressure, t.mentality, t.tempo, t.marking, t.offside, t.tackle, t.advAttack, t.advMid,
            x[0], x[1], x[2], x[3]
        )
    }

    /** Tudo o que se sabe do jogo, sem a tática (ela entra no Plan). */
    suspend fun simInput(repo: Repo, slot: Int): WinModel.Input {
        val f = repo.fieldMap(slot)
        fun str(k: String) = f[k]?.value?.takeIf { FieldMerge.known(it) }
        fun int(k: String) = str(k)?.toIntOrNull()
        fun bonus(k: String) = str(k)?.let { Regex("(\\d{1,3})").find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val matches = repo.dao.matchesOf(slot)
        val played = matches.filter { it.result != null && it.round != null }.sortedByDescending { it.round }
        val rival = str(K.RIVAL_TEAM)?.let { Txt.key(it) }
        val h2h = if (rival != null && rival.length >= 3) {
            played.filter { m -> Fixtures.opponent(m)?.let { Txt.sim(Txt.key(it), rival) >= 0.85 } == true }.mapNotNull { it.result }
        } else emptyList()
        return WinModel.Input(
            myStrength = int(K.MY_STRENGTH), rivalStrength = int(K.RIVAL_STRENGTH),
            myAtk = int(K.MY_ATK), myMid = int(K.MY_MID), myDef = int(K.MY_DEF), myGol = int(K.MY_GOL),
            rivalAtk = int(K.RIVAL_ATK), rivalMid = int(K.RIVAL_MID), rivalDef = int(K.RIVAL_DEF), rivalGol = int(K.RIVAL_GOL),
            rivalFormation = str(K.RIVAL_FORMATION) ?: repo.rivalProfileFormation(slot),
            rivalStyle = str(K.RIVAL_PLAN), rivalMarking = str(K.RIVAL_MARKING), rivalOffside = str(K.RIVAL_OFFSIDE),
            rivalTackle = str(K.RIVAL_TACKLE),
            rivalSecret = boolOf(str(K.RIVAL_SECRET), "Sim", "Não"), rivalCamp = boolOf(str(K.RIVAL_CAMP), "Sim", "Não"),
            home = boolOf(str(K.HOME), "Casa", "Fora"),
            rivalHuman = boolOf(str(K.RIVAL_HUMAN), "Sim", "Não"),
            referee = str(K.REFEREE),
            myBonus = bonus(K.MY_BONUS), rivalBonus = bonus(K.RIVAL_LOGIN_BONUS),
            recent = played.filter { !Fixtures.isCup(it) }.mapNotNull { it.result }.take(5),
            headToHead = h2h
        )
    }

    private fun record(hist: List<HistRow>, plan: WinModel.Plan?): List<String> {
        if (plan == null) return emptyList()
        val f0 = plan.formation.split(" ")[0]
        val done = hist.filter { it.result != null && it.formation.split(" ")[0] == f0 }
        return done.filter { it.playStyle == plan.style }.mapNotNull { it.result }.ifEmpty { done.mapNotNull { it.result } }
    }

    /** Previsão do próximo jogo com a tática gerada (ou só com as forças, se ainda não há tática). */
    suspend fun winProb(repo: Repo, slot: Int, plan: JSONObject? = null): WinModel.Prob? {
        val sp = plan?.let { simPlan(it) }
        val base = simInput(repo, slot)
        return WinModel.predict(base.copy(plan = sp, tacticRecord = record(history(repo, slot), sp)))
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

    private fun fmtM(v: Double): String = if (v >= 1.0) "%.1fM".format(v).replace('.', ',') else "%.0fK".format(v * 1000)

    /** Vigia de mercado: avisa (por 24 h) quando um jogador da lista baixa 10% ou mais de preço. */
    suspend fun priceDrops(repo: Repo, slot: Int): List<String> {
        val listings = repo.dao.listingsOf(slot)
        if (listings.isEmpty()) return emptyList()
        val p = repo.dao.plan(slot, "watch")
        val j = try { if (p != null) JSONObject(p.json) else JSONObject() } catch (e: Exception) { JSONObject() }
        val now = System.currentTimeMillis()
        val drops = ArrayList<JSONObject>()
        val oldDrops = j.optJSONArray("drops")
        if (oldDrops != null) {
            for (i in 0 until oldDrops.length()) {
                val d = oldDrops.optJSONObject(i) ?: continue
                if (now - d.optLong("at") < 86400000L) drops.add(d)
            }
        }
        val prices = j.optJSONObject("prices") ?: JSONObject()
        var changed = false
        for (l in listings.sortedByDescending { it.strength ?: 0 }.take(30)) {
            val price = Money.parse(l.priceText) ?: continue
            val prev = if (prices.has(l.nameKey)) prices.optDouble(l.nameKey) else null
            if (prev == null) {
                prices.put(l.nameKey, price)
                changed = true
            } else if (price <= prev * 0.9) {
                drops.add(JSONObject().put("at", now).put("msg", "📉 S$slot: ${l.name} (${l.cat ?: "?"} ${l.strength ?: "?"}) baixou de ${fmtM(prev)} para ${fmtM(price)}."))
                prices.put(l.nameKey, price)
                changed = true
            } else if (price > prev) {
                prices.put(l.nameKey, price)
                changed = true
            }
        }
        if (changed) {
            val arr = JSONArray()
            for (d in drops) arr.put(d)
            j.put("prices", prices)
            j.put("drops", arr)
            repo.dao.putPlan(PlanEntity(slot, "watch", j.toString(), now))
        }
        return drops.map { it.optString("msg") }
    }

    /** Plano de caixa do estádio: custo do próximo melhoramento contra o caixa atual. */
    fun stadiumPlan(f: Map<String, StoredField>): String? {
        val st = f[K.MY_STAD_STATUS]?.value ?: return null
        val cash = Money.parse(f[K.CASH]?.value)
        val lines = ArrayList<String>()
        for (part in st.split(" • ")) {
            val name = part.substringBefore(":").trim()
            val body = part.substringAfter(":", "").trim()
            if (body.isBlank() || body.contains("máximo")) continue
            if (body.contains("concluir")) {
                lines.add("$name: melhoria pronta, toque em Concluir no jogo.")
                continue
            }
            val cost = Regex("(\\d{1,3}(?:[.,]\\d)?\\s*[KkMm])").find(body)?.groupValues?.get(1)?.replace(" ", "")?.uppercase()?.let { Money.parse(it) }
            if (cost != null && cash != null) {
                lines.add(if (cash >= cost) "$name: $body — cabe no caixa agora." else "$name: $body — faltam ${fmtM(cost - cash)}.")
            } else {
                lines.add("$name: $body")
            }
        }
        return if (lines.isEmpty()) null else lines.joinToString(" ")
    }

    /** Resumo curto dos últimos jogos analisados (estatísticas reais) — alimenta o prompt da IA. */
    suspend fun recentReports(repo: Repo, slot: Int): List<String> {
        val out = ArrayList<String>()
        for (p in repo.dao.matchReports(slot).sortedByDescending { it.at }.take(3)) {
            try {
                val j = JSONObject(p.json)
                val mineHome = j.optBoolean("mineHome", true)
                val st = j.optJSONObject("stats")
                fun mine(label: String): String {
                    val a = st?.optJSONArray(label) ?: return NI
                    return a.optString(if (mineHome) 0 else 1)
                }
                fun opp(label: String): String {
                    val a = st?.optJSONArray(label) ?: return NI
                    return a.optString(if (mineHome) 1 else 0)
                }
                out.add(
                    "J${j.optInt("round")}: ${j.optInt("sh")}-${j.optInt("sa")} (${if (mineHome) "casa" else "fora"}); posse ${mine("posse de bola")} vs ${opp("posse de bola")}; " +
                        "remates ${mine("remates")} vs ${opp("remates")}; faltas ${mine("faltas")} vs ${opp("faltas")}; formação rival ${opp("formacao")}; homem do jogo ${j.optString("mom")}"
                )
            } catch (e: Exception) {
                continue
            }
        }
        return out
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

    private val SIM_STYLES = Osm.STYLES

    data class Cand(val tactic: Tactic, val rows: List<List<PlayerEntity?>>, val points: Double)

    private fun pct(p: WinModel.Prob?): String = if (p == null) "?" else "V ${p.win}% / E ${p.draw}% / D ${p.loss}%"

    private fun same(a: Tactic, b: Tactic): Boolean =
        a.formation == b.formation && a.playStyle == b.playStyle && a.marking == b.marking && a.offside == b.offside &&
            a.tackle == b.tackle && Math.abs(a.mentality - b.mentality) < 5 && Math.abs(a.pressure - b.pressure) < 5

    /**
     * Simula alternativas: 3 melhores formações do cálculo x 5 estilos x variações de mentalidade, desarme,
     * impedimento e marcação. Devolve da melhor para a pior (pontos esperados).
     */
    private fun candidates(res: TacticEngine.Result, inp: TacticEngine.Input, base: WinModel.Input, hist: List<HistRow>): List<Cand> {
        val out = ArrayList<Cand>()
        val t0 = res.tactic
        for (f in res.ranking.take(3).map { it.first }.ifEmpty { listOf(t0.formation) }) {
            val rows = TacticEngine.lineup(f, inp.players, inp.fitness)?.first ?: continue
            for (st in SIM_STYLES) for (dm in listOf(-12, 0, 12)) for (tk in listOf("Normal", "Agressivo"))
                for (off in listOf("Não", "Sim")) for (mk in listOf("À zona", "Homem-a-homem")) {
                    if (tk == "Agressivo" && inp.referee == "Rigoroso") continue
                    val t = t0.copy(
                        formation = Formations.variant(f, st), playStyle = st, mentality = (t0.mentality + dm).coerceIn(0, 100),
                        tackle = tk, offside = off, marking = mk, notes = emptyList()
                    )
                    val sp = simPlan(t, rows)
                    val ep = WinModel.expectedPoints(base.copy(plan = sp, tacticRecord = record(hist, sp))) ?: continue
                    out.add(Cand(t, rows, ep))
                }
        }
        return out.sortedByDescending { it.points }
    }

    private fun tacticRefinePrompt(context: JSONObject, draft: JSONObject, draftSim: String, table: String, statsText: String, allowed: List<String>): String =
        "Você é o analista tático do OSM 26. Abaixo: o RASCUNHO calculado por regras, uma SIMULAÇÃO de táticas alternativas " +
            "(chance V/E/D e pontos esperados contra este rival, considerando setores, formação, estilo, marcação, impedimento, " +
            "desarme, árbitro e histórico) e os DADOS do jogo. Sua tarefa: escolher a tática que dá MAIS pontos neste jogo. " +
            "NÃO repita o rascunho por comodidade: só mantenha o rascunho se ele for de fato o melhor; se a simulação mostrar algo " +
            "melhor, troque. Você pode divergir da simulação só com um motivo concreto dos dados (ex.: perfil do rival humano, " +
            "resultado real de jogos anteriores). REGRAS: formation deve ser uma de $allowed; pressure, mentality e tempo de 0 a 100 " +
            "(até 15 pontos de distância do rascunho); árbitro Rigoroso => tackle Normal ou Cuidadoso. " +
            "Use os nomes EXATOS do OSM: playStyle ${Osm.STYLES}; marking ${Osm.MARKING}; tackle ${Osm.TACKLES}; " +
            "advAttack ${Osm.ATTACK}; advMid ${Osm.MIDFIELD}; advDef ${Osm.DEFENSE}. " +
            "Responda APENAS JSON com: formation, playStyle, pressure, mentality, tempo, marking, offside, tackle, advAttack, " +
            "advMid, advDef, keptDraft (true/false), rationale (até 4 frases curtas citando números).\n\n" +
            "RASCUNHO ($draftSim):\n$draft\n\nSIMULAÇÃO (melhores primeiro):\n$table\n\n" +
            "HISTÓRICO DAS SUAS TÁTICAS (resultado real):\n$statsText\n\nDADOS:\n$context"

    /**
     * Refino: a IA escolhe olhando a simulação das alternativas. Se a IA repetir o rascunho quando há opção
     * claramente melhor, ou falhar, aplica a melhor tática simulada (com a explicação).
     */
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
        val hist = history(repo, slot)
        val base = simInput(repo, slot)
        fun prob(t: Tactic, rows: List<List<PlayerEntity?>>): WinModel.Prob? {
            val sp = simPlan(t, rows)
            return WinModel.predict(base.copy(plan = sp, tacticRecord = record(hist, sp)))
        }
        AiStatus.set("Simulando táticas alternativas…")
        val cands = candidates(res, inp, base, hist)
        val draftProb = prob(res.tactic, res.rows)
        val draftPts = draftProb?.points ?: 0.0
        val best = cands.firstOrNull()
        val table = cands.distinctBy { it.tactic.formation + it.tactic.playStyle }.take(8).joinToString("\n") { c ->
            val p = prob(c.tactic, c.rows)
            "${c.tactic.formation} • ${c.tactic.playStyle} • mentalidade ${c.tactic.mentality} • ${c.tactic.marking} • impedimento ${c.tactic.offside} " +
                "• desarme ${c.tactic.tackle} => ${pct(p)} (pontos esperados ${"%.2f".format(c.points)})"
        }
        val statLines = ArrayList<String>()
        for (x in Learning.stats(inp.history)) statLines.add("formação ${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        for (x in Learning.byStyle(inp.history)) statLines.add("estilo ${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        for (x in Learning.byContext(inp.history)) statLines.add("${x.formation}: ${x.v}V ${x.e}E ${x.d}D")
        for (r in recentReports(repo, slot)) statLines.add("jogo analisado: $r")
        val stats = statLines.joinToString("\n").ifBlank { "sem jogos registrados ainda" }
        val allowed = res.ranking.take(3).map { it.first }
        val allowedNames = allowed.flatMap { Formations.variants(it).ifEmpty { listOf(it) } }

        val notes = ArrayList<String>()
        var chosen: Cand? = null
        val reply = AiClient.ask(ctx, tacticRefinePrompt(context(repo, slot), draft, pct(draftProb), table, stats, allowedNames), null)
        val json = AiClient.parseJson(reply.text)
        if (reply.ok && json != null) {
            val (validated, err) = TacticValidator.validate(json, inp.referee)
            if (validated != null) {
                val t = TacticValidator.canonical(validated, res.tactic)
                val okLimits = Formations.base(t.formation) in allowed && Math.abs(t.pressure - res.tactic.pressure) <= 15 &&
                    Math.abs(t.mentality - res.tactic.mentality) <= 15 && Math.abs(t.tempo - res.tactic.tempo) <= 15
                val rows = TacticEngine.lineup(t.formation, inp.players, inp.fitness)?.first
                if (okLimits && rows != null) {
                    val pts = prob(t, rows)?.points ?: 0.0
                    val bestPts = best?.points ?: pts
                    val repeated = same(t, res.tactic)
                    if (repeated && best != null && bestPts > draftPts + 0.05) {
                        notes.add("A IA manteve o rascunho, mas a simulação achou opção melhor (${"%.2f".format(bestPts)} x ${"%.2f".format(draftPts)} pontos esperados): apliquei a melhor.")
                    } else if (pts >= bestPts - 0.08) {
                        chosen = Cand(t.copy(notes = t.notes), rows, pts)
                        notes.addAll(t.notes)
                        if (repeated) notes.add("Rascunho mantido: a simulação confirma que é a melhor opção (${"%.2f".format(pts)} pontos esperados).")
                    } else {
                        notes.add("A sugestão da IA (${t.formation} • ${t.playStyle}) rende menos na simulação (${"%.2f".format(pts)} x ${"%.2f".format(bestPts)}): apliquei a melhor simulada.")
                    }
                } else {
                    notes.add("A IA saiu dos limites (formação fora das 3 melhores ou controles a mais de 15 pontos): apliquei a melhor simulada.")
                }
            } else {
                notes.add("Resposta da IA inválida ($err): apliquei a melhor tática simulada.")
            }
        } else {
            notes.add("IA indisponível agora (${(reply.error ?: "sem resposta").take(80)}): apliquei a melhor tática simulada.")
        }
        val pick = chosen ?: best ?: return Outcome(false, null, "Não consegui simular alternativas com o elenco lido.")
        val pickProb = prob(pick.tactic, pick.rows)
        notes.add("Chance com esta tática: ${pct(pickProb)} (rascunho: ${pct(draftProb)}).")
        notes.addAll(res.tactic.notes)
        val merged = res.copy(tactic = pick.tactic.copy(notes = notes), rows = pick.rows)
        val out = tacticPlanJson(merged, round, rival, true)
        val now = System.currentTimeMillis()
        repo.dao.putPlan(PlanEntity(slot, "tactic", out.toString(), now))
        repo.dao.putPlan(PlanEntity(slot, "tlog_R${round ?: 0}", logJson(pick.tactic, round, rival, inp).toString(), now))
        AiStatus.set("")
        return Outcome(true, out.toString(), null)
    }

    // ------------------------------------------------------------ mercado / treino

    suspend fun marketPlan(repo: Repo, slot: Int): MarketEngine.Plan? {
        val f = repo.fieldMap(slot)
        val mine = repo.playersOf(slot).filter { it.owner == "MY" }
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
