package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Rodada do relatório de resultado em andamento (o cabeçalho com "Jornada N" só aparece no topo da tela). */
object ResultCtx {
    var slot: Int = 0
    var round: Int? = null
    var at: Long = 0L
}

class Repo(private val ctx: Context) {
    val dao: CoachDao = CoachDb.get(ctx).dao()

    private suspend fun putFields(slot: Int, readings: Map<String, Reading>, source: String, now: Long): Int {
        if (readings.isEmpty()) return 0
        val old = dao.fieldsOf(slot).associateBy { it.fkey }
        var changed = 0
        for ((k, r) in readings) {
            val o = old[k]?.let { StoredField(it.fvalue, it.conf, it.updatedAt) }
            val n = FieldMerge.merge(k, o, r, now) ?: continue
            dao.putField(FieldEntity(slot, k, n.value, n.conf, n.updatedAt, source))
            changed++
        }
        return changed
    }

    suspend fun learn(slot: Int, kind: String, text: String, now: Long = System.currentTimeMillis()) {
        dao.insertLearning(LearningEntity(at = now, slotId = slot, kind = kind, text = text.take(300)))
    }

    suspend fun fieldMap(slot: Int): Map<String, StoredField> =
        dao.fieldsOf(slot).associate { it.fkey to StoredField(it.fvalue, it.conf, it.updatedAt) }

    /** Identidades conhecidas de cada slot (para reconhecer o slot sem passar pela central). */
    suspend fun knownIdentities(): List<SlotIdentity> {
        val out = ArrayList<SlotIdentity>()
        for (slot in 1..4) {
            val f = fieldMap(slot)
            val names = HashSet<String>()
            f[K.TEAM]?.let { names.add(Txt.key(it.value)) }
            f[K.HUB_TITLE]?.let { names.add(Txt.key(it.value)) }
            names.removeAll { it.length < 3 }
            if (names.isEmpty()) continue
            out.add(SlotIdentity(slot, names, f[K.ROUND]?.value?.toIntOrNull()))
        }
        return out
    }

    /** Aplica uma extração. Só escreve no slot atribuído; sem slot confiável, nada é gravado. */
    suspend fun apply(slot: Int?, ex: Extraction, source: String, now: Long): Int {
        if (ex.type == ScreenType.HUB) return applyHub(ex, now)
        if (slot == null) return 0
        return when (ex.type) {
            ScreenType.PREGAME -> applyPregame(slot, ex, source, now)
            ScreenType.SQUAD -> applySquad(slot, ex, source, now)
            ScreenType.CALENDAR -> applyCalendar(slot, ex, source, now)
            ScreenType.MARKET -> applyMarket(slot, ex, source, now)
            ScreenType.REPORT -> {
                val n = putFields(slot, ex.fields, source, now)
                recordRivalProfile(slot, ex.fields, now)
                n
            }
            ScreenType.STADIUM -> putFields(slot, ex.fields, source, now)
            ScreenType.RESULT -> applyResult(slot, ex, now)
            else -> 0
        }
    }

    private suspend fun applyHub(ex: Extraction, now: Long): Int {
        var changed = 0
        for (c in ex.hubCards) {
            val r = LinkedHashMap<String, Reading>()
            r[K.HUB_TITLE] = Reading(Txt.titleCase(c.team), 0.8)
            if (c.subtitle.isNotBlank()) r[K.COMPETITION] = Reading(Txt.titleCase(c.subtitle), 0.8)
            c.roundDone?.let { r[K.ROUND_DONE] = Reading(it.toString(), 0.8) }
            c.roundTotal?.let { r[K.ROUND_TOTAL] = Reading(it.toString(), 0.8) }
            val sub = Txt.norm(c.subtitle)
            val type = when {
                sub.contains("batalha") -> "Batalha"
                sub.contains("copa") -> "Copa"
                else -> "Liga"
            }
            r[K.COMP_TYPE] = Reading(type, 0.7)
            if (type == "Batalha") r[K.RIVAL_HUMAN] = Reading("Sim", 0.9)
            changed += putFields(c.slot, r, "hub", now)
        }
        return changed
    }

    private suspend fun applyPregame(slot: Int, ex: Extraction, source: String, now: Long): Int {
        val newRival = ex.fields[K.RIVAL_TEAM]
        if (newRival != null && newRival.conf >= 0.8) {
            val old = dao.fieldsOf(slot).firstOrNull { it.fkey == K.RIVAL_TEAM }
            if (old != null && Txt.sim(Txt.key(old.fvalue), Txt.key(newRival.value)) < 0.8) {
                dao.deleteRivalFields(slot)
                dao.deleteRivalPlayers(slot)
                learn(slot, "rival", "Rival mudou de ${old.fvalue} para ${newRival.value}: dados do rival anterior foram descartados.", now)
            }
        }
        return putFields(slot, ex.fields, source, now)
    }

    private suspend fun applySquad(slot: Int, ex: Extraction, source: String, now: Long): Int {
        val f = fieldMap(slot)
        val myTeam = f[K.TEAM]?.value
        val rival = f[K.RIVAL_TEAM]?.value
        var guess: String? = null
        val ot = ex.ownerTeam
        if (ot != null) {
            val k = Txt.key(ot)
            if (myTeam != null && Txt.sim(k, Txt.key(myTeam)) >= 0.8) guess = "MY"
            else if (rival != null && Txt.sim(k, Txt.key(rival)) >= 0.8) guess = "RIVAL"
        }
        val nick = ex.ownerNick
        if (guess == null && nick != null && Txt.sim(Txt.key(nick), MY_NICK) >= 0.75) guess = "MY"
        val owner: String = guess ?: run {
            learn(slot, "elenco", "Elenco de dono desconhecido ou de outro time (${ot ?: "sem cabeçalho"}) ignorado.", now)
            return 0
        }
        var changed = 0
        val prefix = if (owner == "MY") "my." else "rival."
        val mapped = LinkedHashMap<String, Reading>()
        for ((k, r) in ex.fields) {
            when (k) {
                "x.pos" -> if (owner == "MY") mapped[K.LEAGUE_POS] = r
                "x.objective" -> if (owner == "MY") mapped[K.MY_OBJECTIVE] = r
                else -> mapped[prefix + k.removePrefix("x.")] = r
            }
        }
        if (owner == "MY" && ot != null) mapped[K.TEAM] = Reading(ot, 0.8)
        if (owner == "RIVAL") {
            val st = ex.fields["x.strength"]?.value?.toIntOrNull() ?: f[K.RIVAL_STRENGTH]?.value?.toIntOrNull()
            val v = mapped[K.RIVAL_VALUE]
            if (v != null && st != null) {
                Money.fixLostComma(v.value, st)?.let { mapped[K.RIVAL_VALUE] = Reading(it, 0.8) }
            }
        }
        changed += putFields(slot, mapped, source, now)

        // Elenco do rival não é guardado: só o cabeçalho (forças, valor, formação) interessa.
        if (owner != "MY") return changed

        val existing = dao.playersOf(slot).filter { it.owner == owner }
        val cm = LinkedHashMap<String, Pair<Int, Int>>()
        for (p in ex.players) {
            var key = Txt.key(p.name)
            if (existing.none { it.nameKey == key }) {
                val near = existing.firstOrNull { it.age == p.age && Txt.sim(it.nameKey, key) >= 0.88 }
                // mesmo jogador lido com nome ilegível: mesma idade, força e posição, e algum dos nomes é lixo de OCR
                val twin = existing.firstOrNull {
                    p.age != null && it.age == p.age && p.strength != null && it.strength == p.strength && it.cat == p.cat &&
                        (it.valueText == null || p.valueText == null || it.valueText == p.valueText) &&
                        (looksGarbled(it.name) || looksGarbled(p.name))
                }
                if (near != null) key = near.nameKey else if (twin != null) key = twin.nameKey
            }
            val old = dao.player(slot, owner, key)
            if (p.cond != null && p.morale != null) cm[key] = Pair(p.cond, p.morale)
            val n = PlayerEntity(
                slotId = slot, owner = owner, nameKey = key,
                name = chooseName(old?.name, p.name),
                age = p.age ?: old?.age,
                posCode = p.posCode ?: old?.posCode,
                cat = p.cat ?: old?.cat,
                strength = p.strength ?: old?.strength,
                valueText = p.valueText ?: old?.valueText,
                training = p.training ?: old?.training,
                forSale = p.forSale ?: old?.forSale,
                updatedAt = now
            )
            if (old == null || n.copy(updatedAt = old.updatedAt) != old) {
                dao.putPlayer(n)
                changed++
            }
        }
        if (cm.isNotEmpty()) {
            val cur = dao.plan(slot, "cm")?.json?.let { try { JSONObject(it) } catch (e: Exception) { null } } ?: JSONObject()
            var cmChanged = false
            for ((k, v) in cm) {
                val a = JSONArray().put(v.first).put(v.second)
                if (cur.optJSONArray(k)?.toString() != a.toString()) {
                    cur.put(k, a)
                    cmChanged = true
                }
            }
            if (cmChanged) dao.putPlan(PlanEntity(slot, "cm", cur.toString(), now))
        }
        if (ex.players.isNotEmpty()) {
            val all = dao.playersOf(slot).filter { it.owner == owner }
            val counts = JSONObject()
            for (c in listOf("ATA", "MEI", "DEF", "GOL")) counts.put(c, all.count { it.cat == c })
            counts.put("total", all.size)
            dao.deleteSnapshots("SQUAD", slot, owner)
            dao.insertSnapshot(SnapshotEntity(kind = "SQUAD", slotId = slot, owner = owner, json = counts.toString(), at = now))
        }
        return changed
    }

    private suspend fun applyCalendar(slot: Int, ex: Extraction, source: String, now: Long): Int {
        val f = fieldMap(slot)
        val myTeam = f[K.TEAM]?.value
        var changed = 0

        // Humanos: apelido embaixo do time na linha superior.
        val rival = f[K.RIVAL_TEAM]?.value
        if (rival != null && ex.humans.isNotEmpty()) {
            val rk = Txt.key(rival)
            for ((k, nick) in ex.humans) {
                if (Txt.sim(k, rk) >= 0.85) {
                    val r = LinkedHashMap<String, Reading>()
                    if (nick != null && nick.trim().length >= 3) {
                        r[K.RIVAL_HUMAN] = Reading("Sim", 0.9)
                        r[K.RIVAL_NICK] = Reading(nick.trim(), 0.85)
                    } else {
                        r[K.RIVAL_HUMAN] = Reading("Não", 0.7)
                    }
                    changed += putFields(slot, r, source, now)
                }
            }
        }

        val ot = ex.ownerTeam
        var accept = false
        if (myTeam != null) {
            if (ot != null) {
                accept = Txt.sim(Txt.key(ot), Txt.key(myTeam)) >= 0.8
            } else {
                // Sem o título visível (lista rolada): é o MEU calendário se nenhum card mostra o meu time como adversário.
                val mk = Txt.key(myTeam)
                val seesMe = ex.matches.any { it.opponent != null && Txt.sim(Txt.key(it.opponent), mk) >= 0.85 }
                accept = ex.matches.isNotEmpty() && !seesMe
            }
        }
        if (!accept) {
            if (ex.matches.isNotEmpty()) learn(slot, "calendário", "Calendário de outro time ignorado (${ot ?: "sem título"}).", now)
            return changed
        }
        for (m in ex.matches) {
            val old = dao.match(slot, m.key)
            val n = MatchEntity(
                slotId = slot, mkey = m.key, label = m.label,
                round = m.round ?: old?.round,
                date = m.date ?: old?.date,
                time = m.time ?: old?.time,
                home = m.home ?: old?.home,
                scoreMine = m.scoreMine ?: old?.scoreMine,
                scoreOpp = m.scoreOpp ?: old?.scoreOpp,
                result = m.result ?: old?.result,
                opponent = m.opponent ?: old?.opponent,
                opponentNick = m.opponentNick ?: old?.opponentNick,
                updatedAt = now
            )
            if (old == null || n.copy(updatedAt = old.updatedAt) != old) {
                dao.putMatch(n)
                changed++
            }
        }
        return changed
    }

    private suspend fun applyMarket(slot: Int, ex: Extraction, source: String, now: Long): Int {
        var changed = putFields(slot, ex.fields, source, now)
        for (l in ex.listings) {
            val key = Txt.key(l.name)
            val old = dao.listing(slot, key)
            val n = ListingEntity(
                slotId = slot, nameKey = key, name = l.name,
                age = l.age ?: old?.age, posCode = l.posCode ?: old?.posCode, cat = l.cat ?: old?.cat,
                strength = l.strength ?: old?.strength, priceText = l.priceText ?: old?.priceText,
                club = l.club ?: old?.club, sellerNick = l.sellerNick ?: old?.sellerNick, seenAt = now
            )
            if (old == null || n.copy(seenAt = old.seenAt) != old) changed++
            dao.putListing(n)
        }
        dao.deleteOldListings(slot, now - 3L * 24L * 3600L * 1000L)
        if (ex.listings.isNotEmpty() || ex.fields.containsKey(K.SELLING)) {
            val j = JSONObject()
            j.put("selling", ex.fields[K.SELLING]?.value ?: NI)
            j.put("rows", ex.listings.size)
            dao.deleteSnapshots("TRANSFER", slot, null)
            dao.insertSnapshot(SnapshotEntity(kind = "TRANSFER", slotId = slot, owner = null, json = j.toString(), at = now))
        }
        return changed
    }

    /** Valor digitado pelo usuário: confiança acima de qualquer leitura (nada da leitura automática sobrescreve). */
    suspend fun setManual(slot: Int, key: String, value: String) {
        dao.putField(FieldEntity(slot, key, value.trim(), 2.0, System.currentTimeMillis(), "manual"))
    }

    suspend fun clearField(slot: Int, key: String) {
        dao.deleteField(slot, key)
    }

    suspend fun setManualMatch(slot: Int, round: Int, opponent: String?, mine: Int?, opp: Int?, home: Boolean?) {
        val key = "L$round"
        val old = dao.match(slot, key)
        val result = if (mine != null && opp != null) (if (mine > opp) "V" else if (mine == opp) "E" else "D") else null
        val name = opponent?.trim()?.ifBlank { null } ?: old?.opponent
        dao.putMatch(
            MatchEntity(
                slot, key, old?.label ?: "Jornada $round", round, old?.date, old?.time, home ?: old?.home,
                mine, opp, result, name, old?.opponentNick, System.currentTimeMillis()
            )
        )
    }

    /** Condição e moral (0 a 100) de cada jogador, lidas das barras da tela do Plantel. */
    suspend fun fitness(slot: Int): Map<String, Pair<Int, Int>> {
        val p = dao.plan(slot, "cm") ?: return emptyMap()
        val out = HashMap<String, Pair<Int, Int>>()
        try {
            val j = org.json.JSONObject(p.json)
            for (k in j.keys()) {
                val a = j.optJSONArray(k) ?: continue
                out[k] = Pair(a.optInt(0), a.optInt(1))
            }
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    // ------------------------------------------------------------------ perfil do rival humano

    private fun profileKind(nick: String): String = "rp_" + Txt.key(nick).take(24)

    private suspend fun recordRivalProfile(slot: Int, fields: Map<String, Reading>, now: Long) {
        val f = fieldMap(slot)
        val nick = fields[K.RIVAL_NICK]?.value ?: f[K.RIVAL_NICK]?.value ?: return
        if (!FieldMerge.known(nick)) return
        val attrs = listOf(
            K.RIVAL_FORMATION to "formation", K.RIVAL_PLAN to "plan", K.RIVAL_MARKING to "marking",
            K.RIVAL_OFFSIDE to "offside", K.RIVAL_TACKLE to "tackle"
        )
        val e = org.json.JSONObject()
        var any = false
        for ((k, n) in attrs) {
            val v = fields[k]?.value
            if (v != null && FieldMerge.known(v)) {
                e.put(n, v)
                any = true
            }
        }
        if (!any) return
        val round = f[K.ROUND]?.value?.toIntOrNull() ?: -1
        e.put("round", round)
        e.put("at", now)
        val kind = profileKind(nick)
        val old = dao.plan(slot, kind)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (ex: Exception) { org.json.JSONObject() }
        j.put("nick", nick)
        val arr = j.optJSONArray("entries") ?: org.json.JSONArray()
        val list = ArrayList<org.json.JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        val last = list.lastOrNull()
        if (last != null && last.optInt("round", -2) == round) {
            for (k in e.keys()) last.put(k, e.get(k))
        } else {
            list.add(e)
        }
        val out = org.json.JSONArray()
        for (x in list.takeLast(10)) out.put(x)
        j.put("entries", out)
        dao.putPlan(PlanEntity(slot, kind, j.toString(), now))
    }

    suspend fun rivalProfile(slot: Int): PlanEntity? {
        val nick = fieldMap(slot)[K.RIVAL_NICK]?.value ?: return null
        if (!FieldMerge.known(nick)) return null
        return dao.plan(slot, profileKind(nick))
    }

    /** Formação que esse rival humano mais usou (para quando a atual ainda não foi lida). */
    suspend fun rivalProfileFormation(slot: Int): String? {
        val p = rivalProfile(slot) ?: return null
        val counts = HashMap<String, Int>()
        try {
            val arr = org.json.JSONObject(p.json).optJSONArray("entries") ?: return null
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i)?.optString("formation") ?: continue
                if (v.isNotBlank()) counts[v] = (counts[v] ?: 0) + 1
            }
        } catch (e: Exception) {
            return null
        }
        return counts.maxByOrNull { it.value }?.key
    }

    // ------------------------------------------------------------------ resultado registrado à mão

    /** Registra o resultado de um jogo que teve tática gerada; entra no mesmo formato da leitura automática. */
    suspend fun registerManualResult(
        slot: Int, round: Int, mine: Int, opp: Int, mineHome: Boolean,
        possession: Int?, myShots: Int?, oppShots: Int?, myFouls: Int?, oppFouls: Int?,
        oppFormation: String?, mom: String?
    ) {
        val now = System.currentTimeMillis()
        val f = fieldMap(slot)
        val key = "mr_R$round"
        val old = dao.plan(slot, key)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (e: Exception) { org.json.JSONObject() }
        val tl = dao.plan(slot, "tlog_R$round")?.json?.let { try { org.json.JSONObject(it) } catch (e: Exception) { null } }
        val myTeam = f[K.TEAM]?.value ?: "Meu time"
        val rival = tl?.optString("rival")?.takeIf { it.isNotBlank() && it != NI } ?: f[K.RIVAL_TEAM]?.value ?: "Adversário"
        j.put("round", round)
        j.put("mineHome", mineHome)
        j.put("homeTeam", if (mineHome) myTeam else rival)
        j.put("awayTeam", if (mineHome) rival else myTeam)
        j.put("sh", if (mineHome) mine else opp)
        j.put("sa", if (mineHome) opp else mine)
        j.put("manual", true)
        if (!mom.isNullOrBlank()) j.put("mom", mom.trim())
        val stats = j.optJSONObject("stats") ?: org.json.JSONObject()
        fun pair(label: String, a: String?, b: String?) {
            if (a == null || b == null) return
            val arr = org.json.JSONArray()
            arr.put(if (mineHome) a else b)
            arr.put(if (mineHome) b else a)
            stats.put(label, arr)
        }
        pair("golos", mine.toString(), opp.toString())
        if (possession != null) pair("posse de bola", "$possession%", "${100 - possession}%")
        pair("remates", myShots?.toString(), oppShots?.toString())
        pair("faltas", myFouls?.toString(), oppFouls?.toString())
        if (!oppFormation.isNullOrBlank()) pair("formacao", tl?.optString("formation")?.ifBlank { null } ?: NI, oppFormation.trim())
        j.put("stats", stats)
        dao.putPlan(PlanEntity(slot, key, j.toString(), now))
        // permite corrigir um resultado já registrado: limpa o resultado da tática para o vínculo gravar o novo
        val tp = dao.plan(slot, "tlog_R$round")
        if (tp != null && tl != null) {
            tl.put("result", org.json.JSONObject.NULL)
            dao.putPlan(PlanEntity(slot, "tlog_R$round", tl.toString(), tp.at))
        }
        linkResult(slot, round, j, now)
    }

    // ------------------------------------------------------------------ treinos informados à mão

    private suspend fun trainingOverrides(slot: Int): Map<String, Boolean> {
        val p = dao.plan(slot, "trainov") ?: return emptyMap()
        val out = HashMap<String, Boolean>()
        try {
            val j = org.json.JSONObject(p.json)
            for (k in j.keys()) out[k] = j.optBoolean(k)
        } catch (e: Exception) {
            return emptyMap()
        }
        return out
    }

    /** Jogadores do slot com o que o usuário marcou à mão sobre treino (vale mais que a leitura da camisa laranja). */
    suspend fun playersOf(slot: Int): List<PlayerEntity> {
        val ov = trainingOverrides(slot)
        return dao.playersOf(slot).map { p ->
            val o = if (p.owner == "MY") ov[p.nameKey] else null
            if (o != null) p.copy(training = o) else p
        }
    }

    suspend fun setTrainingOverride(slot: Int, nameKey: String, value: Boolean?) {
        val j = org.json.JSONObject()
        for ((k, v) in trainingOverrides(slot)) j.put(k, v)
        if (value == null) j.remove(nameKey) else j.put(nameKey, value)
        dao.putPlan(PlanEntity(slot, "trainov", j.toString(), System.currentTimeMillis()))
    }

    // ------------------------------------------------------------------ resultado do jogo

    private fun statPair(j: org.json.JSONObject, label: String): Pair<String, String>? {
        val a = j.optJSONObject("stats")?.optJSONArray(label) ?: return null
        return Pair(a.optString(0), a.optString(1))
    }

    private fun pct(s: String): Int? = Regex("(\\d{1,3})").find(s)?.groupValues?.get(1)?.toIntOrNull()

    private suspend fun applyResult(slot: Int, ex: Extraction, now: Long): Int {
        val rep = ex.matchReport ?: return 0
        val f = fieldMap(slot)
        var round = rep.round
        if (round != null) {
            ResultCtx.slot = slot
            ResultCtx.round = round
            ResultCtx.at = now
        } else if (ResultCtx.slot == slot && ResultCtx.round != null && now - ResultCtx.at < 600000L) {
            round = ResultCtx.round
            ResultCtx.at = now
        } else {
            val done = f[K.ROUND_DONE]?.value?.toIntOrNull()
            val next = f[K.ROUND]?.value?.toIntOrNull()
            round = done ?: next?.let { it - 1 }
        }
        if (round == null || round <= 0) return 0
        val key = "mr_R$round"
        val old = dao.plan(slot, key)
        val j = try { if (old != null) org.json.JSONObject(old.json) else org.json.JSONObject() } catch (e: Exception) { org.json.JSONObject() }
        var changed = 0
        fun put(k: String, v: Any?) {
            if (v == null) return
            if (j.opt(k)?.toString() != v.toString()) {
                j.put(k, v)
                changed++
            }
        }
        put("round", round)
        put("homeTeam", rep.homeTeam)
        put("awayTeam", rep.awayTeam)
        put("homeNick", rep.homeNick)
        put("awayNick", rep.awayNick)
        put("sh", rep.scoreHome)
        put("sa", rep.scoreAway)
        put("referee", rep.referee)
        put("tip", rep.tip)
        put("advice", rep.advice)
        put("mom", rep.mom)
        if (rep.homeNick != null || rep.awayNick != null) {
            val mineHome = rep.homeNick != null && Txt.sim(Txt.key(rep.homeNick), MY_NICK) >= 0.75
            val mineAway = rep.awayNick != null && Txt.sim(Txt.key(rep.awayNick), MY_NICK) >= 0.75
            if (mineHome != mineAway) put("mineHome", mineHome)
        }
        val stats = j.optJSONObject("stats") ?: org.json.JSONObject()
        for ((lab, pr) in rep.stats) {
            val arr = org.json.JSONArray().put(pr.first).put(pr.second)
            if (stats.optJSONArray(lab)?.toString() != arr.toString()) {
                stats.put(lab, arr)
                changed++
            }
        }
        j.put("stats", stats)
        if (rep.zones.size == 3) {
            val z = org.json.JSONArray()
            for (v in rep.zones) z.put(v)
            put("zones", z)
        }
        fun mergeRatings(k: String, list: List<Pair<String, Int>>) {
            if (list.isEmpty()) return
            val cur = j.optJSONArray(k) ?: org.json.JSONArray()
            val names = HashSet<String>()
            for (i in 0 until cur.length()) names.add(cur.optJSONArray(i)?.optString(0) ?: "")
            for ((n, r) in list) {
                if (n !in names) {
                    cur.put(org.json.JSONArray().put(n).put(r))
                    names.add(n)
                    changed++
                }
            }
            j.put(k, cur)
        }
        mergeRatings("rh", rep.ratingsHome)
        mergeRatings("ra", rep.ratingsAway)
        val evs = j.optJSONArray("events") ?: org.json.JSONArray()
        val seen = HashSet<String>()
        for (i in 0 until evs.length()) seen.add(evs.optString(i))
        for (e in rep.events) if (seen.add(e)) {
            evs.put(e)
            changed++
        }
        j.put("events", evs)
        if (changed > 0 || old == null) {
            dao.putPlan(PlanEntity(slot, key, j.toString(), now))
            linkResult(slot, round, j, now)
        }
        return changed
    }

    /** Liga o relatório ao calendário e à tática usada naquela rodada (é daqui que a IA aprende). */
    private suspend fun linkResult(slot: Int, round: Int, j: org.json.JSONObject, now: Long) {
        if (!j.has("mineHome")) return
        val mineHome = j.optBoolean("mineHome")
        val hasScore = j.has("sh") && j.has("sa")
        val mine = if (hasScore) (if (mineHome) j.optInt("sh") else j.optInt("sa")) else null
        val opp = if (hasScore) (if (mineHome) j.optInt("sa") else j.optInt("sh")) else null
        val oppName = (if (mineHome) j.optString("awayTeam") else j.optString("homeTeam")).ifBlank { null }
        if (mine != null && opp != null) setManualMatch(slot, round, oppName, mine, opp, mineHome)
        val p = dao.plan(slot, "tlog_R$round") ?: return
        val t = try { org.json.JSONObject(p.json) } catch (e: Exception) { return }
        if (mine != null && opp != null && t.isNull("result")) {
            t.put("result", if (mine > opp) "V" else if (mine == opp) "E" else "D")
            t.put("scoreMine", mine)
            t.put("scoreOpp", opp)
            learn(slot, "resultado", "Tática ${t.optString("formation")} (${t.optString("playStyle")}) na J$round → ${t.optString("result")} $mine-$opp (análise do jogo)", now)
        }
        fun side(label: String, mineSide: Boolean): String? {
            val pr = statPair(j, label) ?: return null
            val home = mineHome == mineSide
            return if (home) pr.first else pr.second
        }
        side("posse de bola", true)?.let { v -> pct(v)?.let { t.put("myPossession", it) } }
        side("faltas", true)?.let { v -> pct(v)?.let { t.put("myFouls", it) } }
        side("remates", true)?.let { v -> pct(v)?.let { t.put("myShots", it) } }
        side("remates", false)?.let { v -> pct(v)?.let { t.put("oppShots", it) } }
        side("formacao", false)?.let { t.put("oppFormation", it) }
        j.optString("mom").takeIf { it.isNotBlank() }?.let { t.put("mom", it) }
        dao.putPlan(PlanEntity(slot, "tlog_R$round", t.toString(), p.at))
    }

    private fun looksGarbled(name: String): Boolean =
        Regex("^[a-z][A-Z]").containsMatchIn(name) || Txt.letters(name) < 3

    private fun chooseName(old: String?, incoming: String): String {
        if (old == null) return incoming
        return if (looksGarbled(old) && !looksGarbled(incoming)) incoming else old
    }

    /** Apaga jogadores "fantasma" (nome ilegível) que repetem outro jogador com mesma idade, força e posição. */
    private suspend fun dedupeSquads() {
        for (slot in 1..4) {
            val mine = dao.playersOf(slot).filter { it.owner == "MY" }
            for (bad in mine) {
                if (!looksGarbled(bad.name)) continue
                val real = mine.firstOrNull {
                    it.nameKey != bad.nameKey && !looksGarbled(it.name) && it.age != null && it.age == bad.age &&
                        it.strength != null && it.strength == bad.strength && it.cat == bad.cat &&
                        (it.valueText == null || bad.valueText == null || it.valueText == bad.valueText)
                }
                if (real != null) dao.deletePlayer(slot, "MY", bad.nameKey)
            }
        }
    }

    /** Táticas guardadas por versões antigas (sem rodada) não valem mais e não podem aparecer como "prontas". */
    private suspend fun dropStaleTactics() {
        for (slot in 1..4) {
            val p = dao.plan(slot, "tactic") ?: continue
            val has = try { org.json.JSONObject(p.json).optInt("forRound", -1) > 0 } catch (e: Exception) { false }
            if (!has) dao.deletePlan(slot, "tactic")
        }
    }

    /** Remove dados importados do app antigo (eram inválidos). Os dados novos só vêm de leitura real. */
    suspend fun purgeLegacy(): Int {
        val prefs = ctx.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE)
        val removed = dao.deleteLegacyFields()
        prefs.edit().putBoolean("legacy_imported", true).apply()
        try {
            dedupeSquads()
            dropStaleTactics()
            for (slot in 1..4) {
                dao.deleteCupCards(slot)
                val st = dao.fieldsOf(slot).firstOrNull { it.fkey == K.MY_STADIUM }
                if (st != null && !st.fvalue.contains("Capacidade ")) dao.deleteField(slot, K.MY_STADIUM)
            }
        } catch (e: Exception) {
            Diag.lastError = "Limpeza: " + (e.message ?: e.javaClass.simpleName)
        }
        return removed
    }
}
