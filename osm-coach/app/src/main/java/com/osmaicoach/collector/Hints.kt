package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

/**
 * Dicas do Diretor por slot (mesma conta para a aba Diretor e para a bolha por cima do jogo) e o recorte do que
 * importa na tela do OSM aberta agora: Treinamento → treinos; Transferências → vender/comprar; Estádio → obra;
 * Pré-jogo/Tática → a tática a montar.
 */
object Hints {
    data class Hint(val icon: String, val title: String, val detail: String)

    suspend fun trainRates(repo: Repo): Map<String, Pair<Double, Int>> = runCatching {
        val ages = HashMap<String, Int?>()
        val hists = (1..4).mapNotNull { sl ->
            repo.playersOf(sl).forEach { ages[it.nameKey] = it.age }
            repo.dao.plan(sl, "phist")?.json?.let { JSONObject(it) }
        }
        TrainLearn.rates(hists, ages)
    }.getOrDefault(emptyMap())

    private fun kv(f: Map<String, StoredField>, k: String): String? = f[k]?.value?.takeIf { FieldMerge.known(it) }

    /** Telas desatualizadas deste slot (com as regras do Fresh). */
    suspend fun needsFor(repo: Repo, slot: Int, matchAt: Long?, active: Boolean, now: Long = System.currentTimeMillis()): List<Fresh.Need> {
        val sp = Sponsors.fromJson(repo.dao.plan(slot, "sponsor")?.json)?.first.orEmpty()
        return Fresh.needs(Fresh.In(slot, repo.seenMap(slot), repo.timers(slot), matchAt, sp.any { it.games <= 1 }, now, active))
    }

    suspend fun slotTips(
        repo: Repo, slot: Int, xi0: Set<String>?, matchAt: Long?, title: String, rates: Map<String, Pair<Double, Int>>
    ): List<Playbook.Tip> = runCatching {
        val now = System.currentTimeMillis()
        val f = repo.fieldMap(slot)
        val xi = xi0 ?: runCatching {
            TacticEngine.recommend(Director.tacticInput(repo, slot))?.rows?.flatten()?.mapNotNull { it?.nameKey }?.toSet()
        }.getOrNull() ?: emptySet()
        val used = Regex("^(\\d)\\s*/\\s*(\\d)$").find(f[K.SELLING]?.value?.trim() ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val timers = repo.timers(slot)
        val work = Work.of(slot, title, repo.dao.plan(slot, "trainscreen")?.json?.let { JSONObject(it) }, timers, f)
        val allEv = OsmEvents.parse(repo.dao.plan(0, "events")?.json)
        val active = OsmEvents.active(allEv, now)
        val events = active.map { it.kind }.toSet()
        val upcoming = OsmEvents.upcoming(allEv, now, 3)
        val sponsor = Sponsors.fromJson(repo.dao.plan(slot, "sponsor")?.json)
        val nations = repo.dao.plan(slot, "nations")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { o.optString(it) } } } ?: emptyMap()
        val prep = repo.dao.plan(slot, "prep")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().associateWith { k -> o.optJSONObject(k)?.optBoolean("done") ?: false } } } ?: emptyMap()
        val (inj, susp) = repo.outByDoctorOrLawyer(slot)
        val yellow = repo.dao.plan(slot, "cards")?.json?.let { j -> JSONObject(j).let { o -> o.keys().asSequence().filter { o.optBoolean(it) }.toSet() } } ?: emptySet()
        val spec = repo.dao.plan(slot, "spec")?.json?.let { j -> JSONObject(j).let { o -> Screens.SPEC_ROLES.filter { o.has(it) }.associateWith { o.optString(it) } } } ?: emptyMap()
        val state = f[K.SLOT_STATE]?.value
        val matchTime = matchAt ?: f[K.MATCH_AT]?.value?.toLongOrNull()
        val needs = needsFor(repo, slot, matchTime, (state == null || state == "ativo") && kv(f, K.TEAM) != null, now)
        Playbook.tips(
            Playbook.Input(
                events = events, activeEvents = active, upcomingEvents = upcoming,
                prep = prep, injured = inj, suspended = susp, yellow = yellow, spec = spec, needs = needs,
                ceiling = repo.dao.plan(slot, "ceiling")?.json?.let { JSONObject(it).optDouble("r", Playbook.MAX_PRICE) } ?: Playbook.MAX_PRICE,
                paid = SellPrices.learnedRatio(Ledger.parse(repo.dao.plan(slot, "ledger")?.json), repo.dao.plan(slot, "phist")?.json?.let { JSONObject(it) } ?: JSONObject()),
                trainRates = rates,
                sponsorDeals = sponsor?.first.orEmpty(),
                sponsorReadAt = sponsor?.second,
                nations = nations,
                players = repo.playersOf(slot), xi = xi, cashM = Money.parse(f[K.CASH]?.value), listedUsed = used,
                capLevel = f[K.MY_STAD_CAP]?.value?.toIntOrNull(), pitchLevel = f[K.MY_STAD_PITCH]?.value?.toIntOrNull(),
                trainLevel = f[K.MY_STAD_TRAIN]?.value?.toIntOrNull(),
                stadiumBuilding = timers.any { !it.training && it.at > now },
                round = kv(f, K.ROUND)?.toIntOrNull(), roundTotal = kv(f, K.ROUND_TOTAL)?.toIntOrNull(),
                seats = work.seats, matchAt = matchAt ?: f[K.MATCH_AT]?.value?.toLongOrNull(), now = now
            )
        )
    }.getOrDefault(emptyList())

    private val BY_SCREEN = mapOf(
        ScreenType.TRAINING to setOf("🏋", "🗓", "⏰"),
        ScreenType.MARKET to setOf("💰", "🔎", "➕", "➖", "🔁", "📉", "🚨"),
        ScreenType.STADIUM to setOf("🏟"),
        ScreenType.SQUAD to setOf("➕", "➖", "🚨", "🌍", "💰", "🟨", "🚑")
    )

    fun screenLabel(t: ScreenType): String = when (t) {
        ScreenType.TRAINING -> "Treinamento"
        ScreenType.MARKET -> "Transferências"
        ScreenType.STADIUM -> "Estádio"
        ScreenType.SQUAD -> "Plantel"
        ScreenType.PREGAME -> "Pré-jogo"
        ScreenType.TACTIC -> "Tática"
        ScreenType.REPORT -> "Análise do rival"
        ScreenType.PREP -> "Preparação"
        ScreenType.DOCTOR -> "Médico"
        ScreenType.LAWYER -> "Advogado"
        ScreenType.SPECIALISTS -> "Especialistas"
        ScreenType.SPONSORS -> "Patrocinadores"
        ScreenType.SCOUT -> "Olheiro"
        ScreenType.DEALS -> "Negociações"
        ScreenType.STATS -> "Estatísticas"
        ScreenType.MISSIONS -> "Missões"
        ScreenType.HUB -> "Central"
        else -> "OSM"
    }

    /** O que fazer na tela aberta agora (sem os que o usuário já marcou como feitos). */
    suspend fun forScreen(ctx: Context, slot: Int, type: ScreenType): List<Hint> {
        if (slot <= 0) return emptyList()
        val repo = Repo(ctx)
        val f = repo.fieldMap(slot)
        val title = kv(f, K.TEAM) ?: kv(f, K.HUB_TITLE) ?: "Slot $slot"
        val tips = slotTips(repo, slot, null, null, title, trainRates(repo))
        val out = ArrayList<Hint>()
        tips.filter { it.icon == "🎉" }.forEach { out.add(Hint(it.icon, it.title, it.why)) }
        // lembrete de quais telas visitar (menos a que está aberta agora)
        val needs = runCatching {
            val st = f[K.SLOT_STATE]?.value
            needsFor(repo, slot, null, (st == null || st == "ativo") && kv(f, K.TEAM) != null)
        }.getOrDefault(emptyList()).filter { it.type != type && it.urgency >= 1 }
        needs.take(2).forEach { n ->
            out.add(Hint("🔄", "Atualize: " + (Fresh.NAME[n.type] ?: n.type.name), n.reason.replaceFirstChar { it.uppercase() } + ". " + (Fresh.PATH[n.type] ?: "")))
        }
        when (type) {
            ScreenType.PREP -> {
                val pend = Screens.PREP_DEFS.filter { d -> repo.dao.plan(slot, "prep")?.json?.let { JSONObject(it).optJSONObject(d.key)?.optBoolean("done") } == false }
                if (pend.isEmpty()) out.add(Hint("✅", "Preparação do jogo em dia", "Nenhum item pendente entre os que vi. Role a lista para eu ler os outros."))
                pend.take(5).forEach { out.add(Hint("📝", it.title, it.why.replaceFirstChar { c -> c.uppercase() })) }
            }
            ScreenType.SPECIALISTS -> tips.filter { it.icon == "🎖" }.forEach { out.add(Hint(it.icon, it.title, it.why)) }
            ScreenType.DOCTOR, ScreenType.LAWYER -> {
                tips.filter { it.icon == "🚑" }.forEach { out.add(Hint(it.icon, it.title, it.why)) }
                if (tips.none { it.icon == "🚑" }) out.add(Hint("✅", "Ninguém fora do jogo", "Li esta tela: nenhum jogador do seu elenco aparece como " + (if (type == ScreenType.DOCTOR) "lesionado" else "suspenso") + "."))
            }
            ScreenType.SCOUT -> tips.filter { it.icon == "🔎" }.forEach { out.add(Hint(it.icon, it.title, it.why)) }
            ScreenType.SPONSORS -> tips.filter { it.icon == "🤝" }.forEach { out.add(Hint(it.icon, it.title, it.why)) }
            ScreenType.PREGAME, ScreenType.TACTIC, ScreenType.REPORT -> {
                tips.filter { it.icon == "📝" || it.icon == "🎖" || it.icon == "🚑" }.take(2).forEach { out.add(Hint(it.icon, it.title, it.why)) }
                val ch = rivalChangesOf(repo, slot)
                if (ch.isNotEmpty()) out.add(Hint("⚠", "O rival mudou: gere a tática de novo", RivalWatch.headline(ch)))
                val p = repo.dao.plan(slot, "tactic")
                val j = p?.json?.let { runCatching { JSONObject(it) }.getOrNull() }
                if (p != null && j != null && Director.tacticValid(j, p.at, f, System.currentTimeMillis())) {
                    out.add(Hint("📋", "Monte: ${j.optString("formation")} • ${j.optString("playStyle")}", Director.tacticSummary(j)))
                } else {
                    out.add(Hint("⚡", "Gere a tática deste jogo no app", "Toque em 📱 App → Slots → Tática → Gerar."))
                }
            }
            else -> {
                val keep = BY_SCREEN[type]
                val list = if (keep != null) tips.filter { it.icon in keep } else tips.filter { it.icon != "🎉" && it.icon != "🔄" }.take(3)
                list.forEach { out.add(Hint(it.icon, it.title, it.why)) }
            }
        }
        return out.filter { !dismissed(ctx, it.title) }
    }

    private fun dismissKey(t: String) = "hint_x_" + t.hashCode()

    /** "Já fiz": some por 6 h (se a leitura confirmar, a dica nem volta). */
    fun dismiss(ctx: Context, title: String) = Settings.put(ctx, dismissKey(title), (System.currentTimeMillis() + 6L * 3600000L).toString())

    private fun dismissed(ctx: Context, title: String): Boolean =
        (Settings.get(ctx, dismissKey(title), "0").toLongOrNull() ?: 0L) > System.currentTimeMillis()
}
