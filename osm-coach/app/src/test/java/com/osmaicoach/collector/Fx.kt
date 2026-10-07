package com.osmaicoach.collector

/** Fixtures sintéticas com as coordenadas medidas nos quadros reais do OSM. */
object Fx {
    fun line(text: String, xc: Float, yc: Float, w: Float = 0.06f, h: Float = 0.03f): OcrLine {
        val words = text.split(" ").filter { it.isNotEmpty() }
        val n = words.size
        val l0 = xc - w / 2
        val toks = words.mapIndexed { i, wd ->
            OcrToken(wd, l0 + w * i / n, yc - h / 2, l0 + w * (i + 1) / n, yc + h / 2)
        }
        return OcrLine(text, l0, yc - h / 2, xc + w / 2, yc + h / 2, toks)
    }

    fun ocr(vararg lines: OcrLine): OcrResult = OcrResult(lines.toList(), lines.flatMap { it.tokens })

    fun hubLines(): List<OcrLine> = listOf(
        line("ESPAÇOS DE TREINADOR", 0.52f, 0.03f, 0.12f),
        line("24/34 JORNADA", 0.58f, 0.07f, 0.10f), line("TOBOL", 0.587f, 0.395f), line("CAZAQUISTÃO", 0.587f, 0.42f, 0.09f),
        line("20/30 JORNADA", 0.79f, 0.07f, 0.10f), line("LEVSKI SOFIA", 0.795f, 0.395f, 0.10f), line("BULGÁRIA", 0.795f, 0.42f),
        line("9/34 JORNADA", 0.58f, 0.52f, 0.10f), line("NASAF", 0.587f, 0.845f), line("SUPERLIGA UZBEQUE UZM", 0.587f, 0.871f, 0.15f),
        line("3/10 JORNADA", 0.79f, 0.52f, 0.10f), line("UZM VS BAY", 0.795f, 0.845f, 0.09f), line("BATALHA DE GRUPOS", 0.795f, 0.871f, 0.13f)
    )

    fun hubCards(): List<HubCard> = Parsers.hub(ocr(*hubLines().toTypedArray())).hubCards

    fun pregameLines(): List<OcrLine> = listOf(
        line("Jornada 4", 0.5f, 0.126f, 0.08f),
        line("21h 5m 10s", 0.5f, 0.18f, 0.09f),
        line("Deportes La Serena", 0.23f, 0.366f, 0.16f, 0.05f),
        line("Universidad de Chile", 0.77f, 0.366f, 0.18f, 0.05f),
        line("ChicoR78", 0.23f, 0.41f, 0.08f),
        line("leandrozzy", 0.77f, 0.41f, 0.09f),
        line("73", 0.655f, 0.27f, 0.03f),
        line("4,6M", 0.17f, 0.04f, 0.05f)
    )
}
