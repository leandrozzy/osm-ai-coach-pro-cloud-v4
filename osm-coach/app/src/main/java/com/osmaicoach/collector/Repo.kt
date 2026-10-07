package com.osmaicoach.collector

import android.content.Context
import org.json.JSONObject

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
            ScreenType.REPORT -> putFields(slot, ex.fields, source, now)
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
        for (p in ex.players) {
            var key = Txt.key(p.name)
            if (existing.none { it.nameKey == key }) {
                val near = existing.firstOrNull { it.age == p.age && Txt.sim(it.nameKey, key) >= 0.88 }
                if (near != null) key = near.nameKey
            }
            val old = dao.player(slot, owner, key)
            val n = PlayerEntity(
                slotId = slot, owner = owner, nameKey = key,
                name = old?.name ?: p.name,
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
        if (ot == null || myTeam == null || Txt.sim(Txt.key(ot), Txt.key(myTeam)) < 0.8) {
            if (ex.matches.isNotEmpty()) learn(slot, "calendário", "Calendário de outro time ou sem dono (${ot ?: "?"}) ignorado.", now)
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

    /** Remove dados importados do app antigo (eram inválidos). Os dados novos só vêm de leitura real. */
    suspend fun purgeLegacy(): Int {
        val prefs = ctx.getSharedPreferences("collector_runtime", Context.MODE_PRIVATE)
        val removed = dao.deleteLegacyFields()
        prefs.edit().putBoolean("legacy_imported", true).apply()
        return removed
    }
}
