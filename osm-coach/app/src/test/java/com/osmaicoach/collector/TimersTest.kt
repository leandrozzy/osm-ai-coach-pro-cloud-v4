package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cronômetros das telas de Treinamento e Estádio (prints de 21/10). */
class TimersTest {
    private fun ocr(trace: String): OcrResult {
        val lines = trace.split(" | ").mapNotNull { part ->
            val at = part.lastIndexOf('@')
            if (at <= 0) return@mapNotNull null
            val xy = part.substring(at + 1).split(",")
            val t = part.substring(0, at)
            Fx.line(t, xy[0].toFloat(), xy[1].toFloat(), (0.012f * t.length).coerceIn(0.01f, 0.4f), 0.02f)
        }
        return Fx.ocr(*lines.toTypedArray())
    }

    @Test fun parsesGameTimers() {
        assertEquals(3 * 3600L + 9 * 60 + 14, Parsers.timerSecs("3h 09m 14s"))
        assertEquals(12 * 3600L + 43 * 60 + 16, Parsers.timerSecs("12h 43m 16s"))
        assertEquals(3600L + 59 * 60, Parsers.timerSecs("1h 59m 585"))
        assertEquals(3 * 3600L + 9 * 60, Parsers.timerSecs("3h O9m"))
        assertNull(Parsers.timerSecs("18h"))
        assertNull(Parsers.timerSecs("24h"))
        assertNull(Parsers.timerSecs("-2h"))
        assertNull(Parsers.timerSecs("600K + 18h"))
    }

    private val TRAINING = "Treina os teus jogadores para melhorares o teu plantel!@0.17,0.17 | Treinador universal@0.09,0.29 | 24h@0.04,0.36 | " +
        "PROGRESSÃO ADICIONAL E@0.10,0.79 | Desbloquear treinador@0.09,0.92 | 25@0.10,0.95 | " +
        "Treinador de avançados@0.28,0.29 | 11 ERICK DA COSTA FARIAS@0.28,0.48 | IDADE: 26@0.28,0.51 | 46%@0.28,0.68 | 3h 09m 14s@0.29,0.79 | -2h@0.23,0.92 | Concluir@0.31,0.92 | " +
        "Treinador de médios@0.47,0.29 | 16 RODRIGO ZALAZAR MARTÍNEZ@0.47,0.48 | IDADE: 27@0.47,0.51 | 35%@0.47,0.68 | 3h 09m 18s@0.48,0.79 | -2h@0.42,0.92 | Concluir@0.50,0.92 | " +
        "Treinador de defesas@0.66,0.29 | 5 ODILON KOSSOUNOU@0.66,0.48 | IDADE: 25@0.66,0.51 | 20%@0.66,0.68 | 3h 09m 22s@0.67,0.79 | Concluir@0.69,0.92 | " +
        "Treinador de guarda-redes@0.85,0.29 | 12 ROLANDO SILVA@0.85,0.48 | IDADE: 31@0.85,0.51 | 25%@0.85,0.68 | 3h 09m 24s@0.86,0.79 | Concluir@0.88,0.92"

    @Test fun trainingTimersPerTrainer() {
        val now = 1_000_000L
        val t = Parsers.training(ocr(TRAINING), now).associateBy { it.role }
        assertEquals(now + (3 * 3600L + 9 * 60 + 14) * 1000, t["ATA"]?.endAt)
        assertEquals(now + (3 * 3600L + 9 * 60 + 24) * 1000, t["GOL"]?.endAt)
        assertEquals("active", t["MEI"]?.state)
        // universal bloqueado: livre, sem cronômetro (o "24h" do selo não conta)
        assertEquals("empty", t["UNI"]?.state)
        assertNull(t["UNI"]?.endAt)
    }

    private val STADIUM = "Renova o teu Estádio para melhorares a tua equipa e aumentares as receitas@0.50,0.12 | " +
        "Capacidade@0.20,0.31 | Nível 2@0.20,0.63 | +450K receitas de bilheteira@0.19,0.68 | Começar melhoramento@0.20,0.80 | 600K + 18h@0.20,0.84 | " +
        "Relvado@0.50,0.31 | 12h 43m 16s@0.51,0.68 | -2h@0.42,0.79 | Expandir de imediato@0.53,0.80 | 13@0.54,0.84 | " +
        "Treino@0.80,0.31 | Nível 2@0.80,0.62 | +25% progressão@0.79,0.68 | Começar melhoramento@0.80,0.80 | 525K + 18h@0.80,0.84"

    @Test fun stadiumTimerOnlyOnCardUnderConstruction() {
        val now = 5_000L
        val t = Parsers.stadiumTimers(ocr(STADIUM), now)
        assertEquals(now + (12 * 3600L + 43 * 60 + 16) * 1000, t["Relvado"])
        assertTrue(t.containsKey("Capacidade") && t["Capacidade"] == null)
        assertTrue(t.containsKey("Treino") && t["Treino"] == null)
    }

    @Test fun timeLeftText() {
        assertEquals("em 3h 09m", Timers.left(10_000L + (3 * 3600L + 9 * 60 + 30) * 1000, 10_000L))
        assertEquals("terminou", Timers.left(1L, 2L))
    }

    @Test fun shortTimerAndNotAuthorizedStillCountAsTraining() {
        val tr = "Treinador universal@0.09,0.29 | 24h@0.04,0.36 | Desbloquear treinador@0.09,0.92 | " +
            "Treinador de avançados@0.28,0.29 | 9 TYRESE CAMPBELL@0.28,0.48 | IDADE: 26@0.28,0.51 | 17%@0.28,0.68 | 05m 48s@0.29,0.79 | Não autorizado@0.28,0.92 | " +
            "Treinador de médios@0.47,0.29 | Seleciona@0.47,0.92 | 8h@0.48,0.96 | " +
            "Treinador de defesas@0.66,0.29 | 3 NIKOLA MILENKOVIC@0.66,0.48 | IDADE: 29@0.66,0.51 | 56%@0.66,0.68 | 05m 56s@0.67,0.79 | Não autorizado@0.66,0.92 | " +
            "Treinador de guarda-redes@0.85,0.29 | 13 ANDRÉ FERREIRA@0.85,0.48 | IDADE: 30@0.85,0.51 | 16%@0.85,0.68 | 05m 58s@0.86,0.79 | Não autorizado@0.85,0.92"
        val t = Parsers.training(ocr(tr), 0L).associateBy { it.role }
        assertEquals("active", t["ATA"]?.state)
        assertEquals(348_000L, t["ATA"]?.endAt)
        assertEquals("empty", t["MEI"]?.state)
        assertEquals(358_000L, t["GOL"]?.endAt)
    }

    @Test fun slotWorkGroupsSeatsAndThreeStadiumCards() {
        val screen = org.json.JSONObject()
            .put("ATA", org.json.JSONObject().put("name", "9 TYRESE CAMPBELL").put("state", "active"))
            .put("MEI", org.json.JSONObject().put("name", org.json.JSONObject.NULL).put("state", "empty"))
            .put("UNI", org.json.JSONObject().put("name", org.json.JSONObject.NULL).put("state", "empty"))
        val f = mapOf(
            K.MY_STAD_PITCH to StoredField("2", 0.85, 0L), K.MY_STAD_CAP to StoredField("1", 0.85, 0L),
            K.MY_STAD_STATUS to StoredField("Capacidade: 760K + 18h • Treino: 665K + 18h", 0.8, 0L)
        )
        val w = Work.of(1, "Fortuna", screen, listOf(Timers.Entry(1, "stad_Relvado", 9_000L, "obra: Relvado"), Timers.Entry(1, "train_ATA", 5_000L, "x")), f)
        assertEquals(listOf("ATA", "MEI"), w.seats.map { it.role })
        assertEquals("Tyrese Campbell", w.seats[0].name)
        assertEquals(3, w.cards.size)
        assertEquals(9_000L, w.cards.first { it.label == "Relvado" }.endAt)
        assertEquals("760K + 18h", w.cards.first { it.label == "Capacidade" }.next)
        assertEquals(1, w.needsAction(0L))
    }
}
