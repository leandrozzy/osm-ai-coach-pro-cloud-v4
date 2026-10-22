package com.osmaicoach.collector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybookTest {
    private fun p(n: String, cat: String, age: Int, str: Int, value: String, training: Boolean = false) =
        PlayerEntity(1, "MY", Txt.key(n), n, age, null, cat, str, value, training, false, 0L)

    private val squad = listOf(
        p("Gk A", "GOL", 28, 80, "5M"), p("Gk B", "GOL", 33, 70, "1,2M"),
        p("Def A", "DEF", 24, 80, "6M"), p("Def B", "DEF", 25, 79, "6M"), p("Def C", "DEF", 26, 78, "5M"), p("Def D", "DEF", 31, 70, "2M"), p("Def E", "DEF", 20, 72, "3M"),
        p("Mid A", "MEI", 22, 82, "9M"), p("Mid B", "MEI", 27, 80, "7M"), p("Mid C", "MEI", 30, 76, "3M"), p("Mid D", "MEI", 19, 70, "2M"), p("Mid E", "MEI", 32, 68, "900K"),
        p("Atk A", "ATA", 23, 83, "10M"), p("Atk B", "ATA", 26, 80, "8M"), p("Atk C", "ATA", 30, 71, "2M")
    )

    @Test fun prioritizesFreeTrainerListingAndStadiumOrder() {
        val xi = setOf("gka", "defa", "defb", "defc", "mida", "midb", "midc", "atka", "atkb", "atkc", "defd").toSet()
        val tips = Playbook.tips(
            Playbook.Input(
                squad, xi, 3.0, listedUsed = 1, capLevel = 1, pitchLevel = 1, trainLevel = 1, stadiumBuilding = false,
                round = 2, roundTotal = 22, seats = listOf(WorkSeat("MEI", null, "empty", null), WorkSeat("ATA", "ATK A", "active", 9_000L)),
                matchAt = 5_000L, now = 0L
            )
        )
        val titles = tips.map { it.title }
        assertTrue(titles.any { it.contains("treinador de médios") && it.contains("Mid D") })   // o mais jovem
        assertTrue(titles.any { it.contains("Titular treinando") })
        assertTrue(titles.any { it.contains("Elenco curto (15)") })
        assertTrue(titles.any { it.contains("Próxima obra do estádio: Treino") })
        assertTrue(tips.zipWithNext().all { (a, b) -> a.priority >= b.priority })
    }

    @Test fun sellPriceTable() {
        assertEquals(25.0, Playbook.prices(10.0, early = true).second, 0.001)
        assertEquals(20.0, Playbook.prices(10.0, early = false).second, 0.001)
        assertEquals("2,5M", Playbook.money(2.5))
        assertEquals("900K", Playbook.money(0.9))
    }

    @Test fun doesNotSellYoungGoodReservesAndKeepsPositionMinimums() {
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        val extra = squad + listOf(
            p("Young 72", "MEI", 19, 72, "7M"), p("Old Mid", "MEI", 33, 66, "1M"),
            p("Old Atk", "ATA", 34, 60, "1M"), p("Old Def", "DEF", 34, 60, "1M"), p("Old Mid2", "MEI", 35, 61, "1M")
        )
        val tips = Playbook.tips(
            Playbook.Input(
                extra, xi, 3.0, listedUsed = 2, capLevel = 2, pitchLevel = 2, trainLevel = 2, stadiumBuilding = false,
                round = 8, roundTotal = 22, seats = emptyList(), matchAt = null, now = 0L
            )
        )
        val sell = tips.first { it.title.startsWith("Sobram") }.title
        assertTrue(sell.contains("Old"))
        assertTrue(!sell.contains("Young 72"))
        assertTrue(!sell.contains("Gk B")) // 2 goleiros é o mínimo
    }

    @Test fun neverAsksToSellWhenSquadIsAtMinimum() {
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        val tips = Playbook.tips(
            Playbook.Input(
                squad, xi, 3.0, listedUsed = 0, capLevel = 2, pitchLevel = 2, trainLevel = 2, stadiumBuilding = false,
                round = 8, roundTotal = 22, seats = emptyList(), matchAt = null, now = 0L
            )
        )
        assertTrue(tips.none { it.title.startsWith("Sobram") || it.title.startsWith("Lista com") })
    }

    @Test fun bcCostingPrepIsHiddenUnlessAccepted() {
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        fun run(spend: Boolean) = Playbook.tips(
            Playbook.Input(
                squad, xi, 1.0, null, 2, 2, 2, false, 8, 22, emptyList(), matchAt = 5 * 3600000L, now = 0L,
                prep = mapOf("onze" to true, "amistoso" to false, "secreto" to false, "estagio" to false), spendBc = spend
            )
        )
        assertTrue(run(false).none { it.icon == "📝" || it.icon == "🗒" })
        assertTrue(run(true).any { it.icon == "📝" || it.icon == "🗒" })
    }

    @Test fun suggestsAgeMixWhenXiIsAllOneAge() {
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        val tips = Playbook.tips(
            Playbook.Input(
                squad.map { it.copy(age = 23) }, xi, 3.0, listedUsed = 0, capLevel = 2, pitchLevel = 2, trainLevel = 2, stadiumBuilding = false,
                round = 8, roundTotal = 22, seats = emptyList(), matchAt = null, now = 0L
            )
        )
        assertTrue(tips.any { it.icon == "👥" })
    }

    @Test fun oldPrepReadingIsOnlyInformation() {
        val xi = setOf("gka", "defa", "defb", "defc", "defd", "mida", "midb", "midc", "midd", "atka", "atkb")
        fun run(prepAt: Long) = Playbook.tips(
            Playbook.Input(
                squad, xi, 1.0, null, 2, 2, 2, false, 8, 22, emptyList(), matchAt = 5 * 3600000L, now = 10 * 3600000L,
                prep = mapOf("onze" to false, "banco" to false), prepAt = prepAt
            )
        )
        assertTrue(run(10 * 3600000L - 600_000L).any { it.icon == "📝" })
        assertTrue(run(0L).none { it.icon == "📝" } && run(0L).any { it.icon == "🗒" })
    }
}
