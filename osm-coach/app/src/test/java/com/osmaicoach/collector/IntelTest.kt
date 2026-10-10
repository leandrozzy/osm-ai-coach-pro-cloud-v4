package com.osmaicoach.collector

import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long {
        val c = Calendar.getInstance()
        c.set(y, mo - 1, d, h, mi, 0)
        c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private fun m(
        round: Int, opp: String?, result: String? = null, date: String? = null, time: String? = null,
        label: String = "Jornada $round", readAt: Long = 0L
    ) = MatchEntity(
        1, "L$round", label, round, date, time, true,
        if (result == null) null else if (result == "V") 2 else if (result == "E") 1 else 0,
        if (result == null) null else 1, result, opp, null, readAt
    )

    @Test fun cupStageIsNotATeam() {
        assertTrue(Fixtures.isStage("Meias finais"))
        assertTrue(Fixtures.isStage("Quartos de final"))
        assertTrue(Fixtures.isStage("Final"))
        assertFalse(Fixtures.isStage("FC Zhenis Astana"))
        assertFalse(Fixtures.isStage("Finlandia FC"))
        val cup = m(24, "Meias finais", date = "21/10/26")
        assertTrue(Fixtures.isCup(cup))
        assertNull(Fixtures.opponent(cup))
        assertEquals("Meias finais", Fixtures.stage(cup))
    }

    @Test fun cupLossVoidsTheNextCupGames() {
        val ms = listOf(
            m(20, "Quartos de final", result = "D", label = "Copa • Quartos de final"),
            m(22, "Dinamo", result = "V"),
            m(24, "Meias finais", date = "21/10/26"),
            m(25, "FC Zhenis Astana", time = "22:18", readAt = at(2026, 10, 21, 20, 0))
        )
        assertEquals(20, Fixtures.cupOutRound(ms))
        assertTrue(Fixtures.void(ms[2], ms))
        assertFalse(Fixtures.void(ms[3], ms))
        // a semifinal (data passada, copa perdida) não é o próximo jogo nem pede resultado
        val now = at(2026, 10, 21, 21, 0)
        assertEquals(25, Fixtures.next(ms, now)?.round)
        assertNull(Fixtures.awaitingResult(ms, emptySet(), now))
    }

    @Test fun resultIsAskedOnlyAfterTheCalendarTimePasses() {
        val readAt = at(2026, 10, 21, 20, 0)
        val ms = listOf(m(25, "FC Zhenis Astana", time = "22:18", readAt = readAt))
        assertNull(Fixtures.awaitingResult(ms, emptySet(), at(2026, 10, 21, 22, 0)))
        assertEquals(25, Fixtures.awaitingResult(ms, emptySet(), at(2026, 10, 21, 22, 30))?.round)
        // análise do jogo já leu o placar: não pede de novo
        assertNull(Fixtures.awaitingResult(ms, setOf(25), at(2026, 10, 21, 22, 30)))
        // card sem data e sem hora: nunca pede
        assertNull(Fixtures.awaitingResult(listOf(m(26, "X")), emptySet(), at(2026, 12, 1, 0, 0)))
    }

    @Test fun roundOrderDecidesWhenAGameHappened() {
        // card do próximo jogo (J25) lido ontem às 21h com "22:18": sem a rodada atual parecia já jogado
        val readYesterday = at(2026, 10, 7, 23, 0)
        val now = at(2026, 10, 8, 8, 47)
        val tonight = at(2026, 10, 8, 22, 18)
        val ms = listOf(m(24, "Dinamo", result = "V"), m(25, "FK Atyrau", time = "22:18", readAt = readYesterday))
        assertNull(Fixtures.awaitingResult(ms, emptySet(), now, 25, tonight))
        assertEquals(25, Fixtures.next(ms, now, 25, tonight)?.round)
        // passou do horário: agora sim pede o resultado
        assertEquals(25, Fixtures.awaitingResult(ms, emptySet(), at(2026, 10, 8, 22, 40), 25, tonight)?.round)
        // rodada antiga sem placar não vira alerta
        val old = listOf(m(10, "X", date = "01/09/26"), m(25, "FK Atyrau", time = "22:18", readAt = readYesterday))
        assertNull(Fixtures.awaitingResult(old, emptySet(), now, 25, tonight))
    }

    @Test fun backgroundTextIsNeverANick() {
        assertFalse(Evidence.plausibleNick("25 Anniversary"))
        assertFalse(Evidence.plausibleNick("Aniversário"))
        assertFalse(Evidence.plausibleNick("Jornada 12"))
        assertTrue(Evidence.plausibleNick("amar111_13"))
        assertTrue(Evidence.plausibleNick("Joel II Gonzalez"))
    }

    @Test fun nickNeedsTwoIndependentScreens() {
        val j = JSONObject()
        Evidence.add(j, K.RIVAL_NICK, "amar111_13", "CALENDAR", 1L)
        assertNull(Evidence.confirmed(j, K.RIVAL_NICK))
        Evidence.add(j, K.RIVAL_NICK, "amar11l_13", "CALENDAR2", 2L)
        val ok = Evidence.confirmed(j, K.RIVAL_NICK)
        assertNotNull(ok)
        assertEquals("amar111_13", ok!!.value)
        assertEquals(2, ok.sources.size)
        // o nome do usuário sob o time no pré-jogo (ou no plantel dele) já basta: humano não precisa de bônus
        val pre = JSONObject()
        Evidence.add(pre, K.RIVAL_NICK, "natan bianque_5", "PREGAME", 1L)
        assertNotNull(Evidence.confirmed(pre, K.RIVAL_NICK))
        val sq = JSONObject()
        Evidence.add(sq, K.RIVAL_NICK, "natan bianque_5", "SQUAD", 1L)
        assertNotNull(Evidence.confirmed(sq, K.RIVAL_NICK))
        // a etiqueta "Apelido [S3]" da análise sozinha já confirma
        val r = JSONObject()
        Evidence.add(r, K.RIVAL_NICK, "leo_fc", "REPORT", 1L)
        assertNotNull(Evidence.confirmed(r, K.RIVAL_NICK))
    }

    @Test fun divergentReadingsAreKeptApart() {
        val j = JSONObject()
        Evidence.add(j, K.RIVAL_TEAM, "Levski", "PREGAME", 1L)
        Evidence.add(j, K.RIVAL_TEAM, "Ludogorets", "CALENDAR", 2L)
        assertEquals(2, Evidence.values(j, K.RIVAL_TEAM).size)
    }

    @Test fun winProbabilityFollowsStrengthAndContext() {
        val even = WinModel.predict(WinModel.Input(80, 80))!!
        assertEquals(100, even.win + even.draw + even.loss)
        assertTrue(kotlin.math.abs(even.win - even.loss) <= 2)
        val home = WinModel.predict(WinModel.Input(80, 80, home = true))!!
        assertTrue(home.win > even.win)
        val strong = WinModel.predict(WinModel.Input(90, 72, home = true, recent = listOf("V", "V", "V")))!!
        assertTrue(strong.win >= 70)
        // sempre sobra chance de zebra
        val huge = WinModel.predict(WinModel.Input(95, 50, home = true))!!
        assertTrue(huge.win <= 92)
        val weak = WinModel.predict(WinModel.Input(70, 85, home = false, rivalHuman = true))!!
        assertTrue(weak.loss > weak.win)
        assertNull(WinModel.predict(WinModel.Input(null, null)))
    }

    private val plan = WinModel.Plan("4-4-2", "Jogo de passe", 50, 50, 50, "À zona", "Não", "Normal", "Atacar apenas", "Manter posições", 80.0, 80.0, 80.0, 80.0)

    @Test fun theGeneratedTacticChangesThePrediction() {
        val base = WinModel.Input(80, 80, rivalAtk = 80, rivalMid = 80, rivalDef = 80, rivalGol = 80, referee = "Rigoroso", rivalFormation = "4-3-3", rivalStyle = "Contra-ataque")
        val normal = WinModel.predict(base.copy(plan = plan))!!
        val aggressive = WinModel.predict(base.copy(plan = plan.copy(tackle = "Agressivo")))!!
        assertTrue(aggressive.win < normal.win)
        // linha de impedimento contra quem joga em contra-ataque é arriscada
        val trap = WinModel.predict(base.copy(plan = plan.copy(offside = "Sim")))!!
        assertTrue(trap.loss > normal.loss)
        assertTrue(normal.factors.isNotEmpty())
    }

    @Test fun counterAttackPaysOffAgainstAnAttackingStrongerRival() {
        val base = WinModel.Input(75, 82, myMid = 72, rivalAtk = 84, rivalMid = 82, rivalDef = 80, rivalFormation = "4-3-3", rivalStyle = "Jogo de passe")
        val passe = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-5-1", style = "Jogo de passe")))!!
        val contra = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-5-1", style = "Contra-ataque")))!!
        assertTrue(contra > passe)
    }

    @Test fun tacticHistoryMovesThePrediction() {
        val base = WinModel.predict(WinModel.Input(80, 80))!!
        val good = WinModel.predict(WinModel.Input(80, 80, tacticRecord = listOf("V", "V", "V", "E")))!!
        val bad = WinModel.predict(WinModel.Input(80, 80, tacticRecord = listOf("D", "D", "E")))!!
        assertTrue(good.win > base.win)
        assertTrue(bad.win < base.win)
    }

    @Test fun highLineIsPunishedByACounterAttackingHuman() {
        val base = WinModel.Input(80, 80, rivalAtk = 80, rivalMid = 80, rivalDef = 80, rivalGol = 80, rivalHuman = true, rivalStyle = "Contra-ataque")
        val safe = WinModel.predict(base.copy(plan = plan.copy(mentality = 45, advMid = "Manter posições")))!!
        val risky = WinModel.predict(base.copy(plan = plan.copy(mentality = 85, advMid = "Pressionar na frente")))!!
        assertTrue(risky.loss > safe.loss)
    }

    @Test fun registeredResultMovesTheClockAndDropsTheOldTime() {
        val now = at(2026, 10, 9, 12, 0)
        val j11At = at(2026, 10, 8, 21, 0)
        val ms = listOf(m(11, "Pakhtakor"), m(12, "Surkhan", time = "21:00", readAt = at(2026, 10, 8, 10, 0)))
        // análise do jogo J11 lida; a rodada ainda dizia 11 e o horário era o da J11 (já passou)
        val c = Fixtures.clock(ms, setOf(11), now, 11, at(2026, 10, 8, 10, 0), j11At, "Surkhan")
        assertEquals(12, c.round)
        assertNull(c.at)
        assertNull(Fixtures.awaitingResult(ms, setOf(11), now, c.round, c.at))
        assertEquals(12, Fixtures.next(ms, now, c.round, c.at)?.round)
    }

    @Test fun roundBumpedAfterTheGameTimeKeepsNoStaleTime() {
        val now = at(2026, 10, 9, 12, 0)
        val j11At = at(2026, 10, 8, 21, 0)
        val ms = listOf(m(11, "Pakhtakor", result = "D"), m(12, "Surkhan", time = "21:00"))
        // a rodada virou 12 depois do jogo, mas o horário guardado ainda é o da J11: não é o da J12
        val c = Fixtures.clock(ms, emptySet(), now, 12, at(2026, 10, 9, 8, 0), j11At, "Surkhan")
        assertEquals(12, c.round)
        assertNull(c.at)
        assertNull(Fixtures.awaitingResult(ms, emptySet(), now, c.round, c.at))
    }

    @Test fun roundThatJumpedOneTooFarComesBack() {
        val now = at(2026, 10, 9, 12, 0)
        val tonight = at(2026, 10, 9, 21, 0)
        val ms = listOf(m(11, "Pakhtakor", result = "D"), m(12, "Surkhan", time = "21:00"), m(13, "Navbahor", time = "21:00"))
        // a rodada foi para 13, mas o rival do pré-jogo (Surkhan) é o da J12, que ainda não tem placar
        val c = Fixtures.clock(ms, setOf(11), now, 13, at(2026, 10, 9, 8, 0), tonight, "Surkhan")
        assertEquals(12, c.round)
        assertEquals(tonight, c.at)
        assertNull(Fixtures.awaitingResult(ms, setOf(11), now, c.round, c.at))
        // depois do horário, aí sim pede o resultado da J12
        assertEquals(12, Fixtures.awaitingResult(ms, setOf(11), at(2026, 10, 9, 23, 0), c.round, c.at)?.round)
        // rodada legítima (rival já é o da J13) não volta
        assertEquals(13, Fixtures.clock(ms, setOf(11), now, 13, at(2026, 10, 9, 8, 0), tonight, "Navbahor").round)
    }

    @Test fun overlayButtonsAreNotScreenText() {
        assertTrue(Overlay.isChip("■ Encerrar"))
        assertTrue(Overlay.isChip("Encerrar"))
        assertTrue(Overlay.isChip("📱 App"))
        assertTrue(Overlay.isChip("⚽ OSM"))
        assertFalse(Overlay.isChip("Encerrado"))
        assertFalse(Overlay.isChip("J. Silva"))
    }

    @Test fun resultAlertOnlyAfterTheCurrentGameTime() {
        val now = at(2026, 10, 9, 13, 7)
        val tonight = at(2026, 10, 9, 22, 18)
        // J26 de ontem sem placar lido, J27 hoje à noite: nada de "Registrar resultado" no Hoje
        val ms = listOf(m(26, "Kairat", time = "22:18"), m(27, "FK Ulytau", time = "22:18"))
        assertNull(Fixtures.resultDue(ms, emptySet(), now, 27, tonight))
        // passou do horário de hoje: pede o da J27
        assertEquals(27, Fixtures.resultDue(ms, emptySet(), at(2026, 10, 9, 23, 0), 27, tonight)?.round)
        // placar lido: não pede mais
        assertNull(Fixtures.resultDue(ms, setOf(27), at(2026, 10, 9, 23, 0), 27, tonight))
        // horário desconhecido: não pede
        assertNull(Fixtures.resultDue(ms, emptySet(), now, 27, null))
    }

    @Test fun missingFieldsUseTheSameRuleForEverySlot() {
        val cpu = mapOf(K.RIVAL_HUMAN to StoredField("Não", 0.9, 1L))
        val human = mapOf(K.RIVAL_HUMAN to StoredField("Sim", 0.9, 1L))
        assertEquals(Completeness.gameMissing(cpu).size + 2, Completeness.gameMissing(human).size)
        assertTrue(Completeness.gameMissing(cpu).none { it.key == K.MY_STAD_CAP })
    }

    @Test fun extremeTackleIsPunishedUnlessTheRefereeIsLenient() {
        val base = WinModel.Input(80, 80, referee = "Médio")
        val normal = WinModel.predict(base.copy(plan = plan))!!
        val extreme = WinModel.predict(base.copy(plan = plan.copy(tackle = "Extremo")))!!
        assertTrue(extreme.loss > normal.loss)
        assertTrue(extreme.win < normal.win)
    }

    @Test fun threeStrikersBeatTwoAgainstAMuchWeakerRival() {
        val base = WinModel.Input(84, 70, rivalFormation = "4-4-2")
        val two = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-4-2")))!!
        val three = WinModel.expectedPoints(base.copy(plan = plan.copy(formation = "4-3-3")))!!
        assertTrue(three > two)
    }

    @Test fun coachFindsWhatWentWrongAndPlansTheFix() {
        val log5 = JSONObject().put("round", 5).put("rival", "Colo-Colo").put("formation", "4-2-3-1").put("playStyle", "Contra-ataque").put("result", JSONObject.NULL)
        val stats = JSONObject().put("posse de bola", org.json.JSONArray().put("32%").put("68%"))
            .put("remates", org.json.JSONArray().put("4").put("12")).put("faltas", org.json.JSONArray().put("8").put("16"))
            .put("formacao", org.json.JSONArray().put("4-2-3-1").put("4-4-2 B"))
        val rep6 = JSONObject().put("round", 6).put("sh", 0).put("sa", 2).put("mineHome", true)
            .put("homeTeam", "Universidad de Chile").put("awayTeam", "Colo-Colo").put("stats", stats)
        val games = Coach.games(listOf(log5), listOf(rep6))
        assertEquals(1, games.size)
        // a tática gerada na "J5" contra o mesmo rival é a do jogo J6
        assertEquals("4-2-3-1", games[0].formation)
        val rv = Coach.review(games[0])
        assertTrue(rv.wrong.any { it.contains("meio-campo") })
        assertTrue(rv.wrong.any { it.contains("Criou pouco") })
        assertTrue(rv.wrong.any { it.contains("Defesa exposta") })
        val l = Coach.lessons(games, "Colo-Colo")
        assertTrue(l.midNeed > 0 && l.defNeed > 0 && l.atkNeed > 0)
        assertTrue("4-2-3-1|Contra-ataque" in l.avoid)
        assertTrue(l.plan.isNotEmpty())
        assertEquals(54, Coach.num("540", true))
    }

    @Test fun predictionsAreCalibratedWithRealResults() {
        fun log(r: Int, res: String, pts: Double, w: Int, e: Int, d: Int) = JSONObject().put("round", r).put("result", res)
            .put("predPts", pts).put("predW", w).put("predE", e).put("predD", d)
        // previa vitória (2,1 pontos) e o time perdeu 3 vezes: corrige para baixo
        val bad = listOf(log(1, "D", 2.1, 65, 20, 15), log(2, "D", 2.0, 60, 25, 15), log(3, "E", 2.2, 70, 20, 10))
        assertTrue(Calib.offset(bad) < 0.0)
        assertEquals(Pair(0, 3), Calib.accuracy(bad))
        val good = listOf(log(1, "V", 2.0, 60, 25, 15), log(2, "V", 1.9, 55, 25, 20))
        assertEquals(0.0, Calib.offset(good), 0.0001) // menos de 3 jogos: sem ajuste
        assertEquals(Pair(2, 2), Calib.accuracy(good))
        // o ajuste muda a previsão
        val base = WinModel.predict(WinModel.Input(80, 80))!!
        val down = WinModel.predict(WinModel.Input(80, 80, calib = -0.15))!!
        assertTrue(down.win < base.win)
    }

    @Test fun newCompetitionDoesNotJumpTheRound() {
        val now = at(2026, 10, 10, 12, 0)
        val old = (1..10).map { m(it, "R$it", result = "V") }
        // batalha acabou (10 rodadas) e a liga nova começou: pré-jogo diz rodada 1
        val c = Fixtures.clock(old, emptySet(), now, 1, now, null, null)
        assertEquals(1, c.round)
    }
}
