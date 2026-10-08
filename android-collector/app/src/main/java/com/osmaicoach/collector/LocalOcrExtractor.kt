package com.osmaicoach.collector

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * OCR local conservador.
 * Só preenche quando a evidência textual é forte.
 * Nunca apaga campo já válido e nunca inventa jogador a partir de texto solto.
 */
class LocalOcrExtractor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        var cropped: Bitmap? = null
        return try {
            // Remove barra de status/navegação, mas preserva todo o conteúdo do jogo.
            val top = (bitmap.height * 0.045f).toInt().coerceAtLeast(0)
            val bottom = (bitmap.height * 0.055f).toInt().coerceAtLeast(0)
            val height = (bitmap.height - top - bottom).coerceAtLeast(1)
            cropped = Bitmap.createBitmap(bitmap, 0, top, bitmap.width, height)
            recognizer.process(InputImage.fromBitmap(cropped!!, 0)).await().text.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            cropped?.takeIf { it !== bitmap }?.recycle()
            bitmap.recycle()
        }
    }

    fun applyToSlot(slot: NativeSlotData, texts: List<String>) {
        if (texts.isEmpty()) return

        val joined = texts
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
            .take(180000)

        if (joined.isBlank()) return

        val lines = joined.lines()
            .map(::cleanValue)
            .filter { it.length in 2..100 }

        val n = normalize(joined)

        fun setIfNi(current: String, value: String?, setter: (String) -> Unit) {
            if (current != "NI" && current.isNotBlank()) return
            val v = value?.trim().orEmpty()
            if (v.isNotBlank() && v != "NI") setter(v)
        }

        setIfNi(slot.nextRival,
            extractLabeled(lines, listOf("adversário","adversario","opponent","rival"))
                ?.takeIf(::validEntityName)
        ) { slot.nextRival = it }

        setIfNi(slot.referee,
            extractLabeled(lines, listOf("árbitro","arbitro","referee"))
                ?.takeIf(::validReferee)
        ) { slot.referee = it }

        setIfNi(slot.stadium,
            extractLabeled(lines, listOf("estádio","estadio","stadium"))
                ?.takeIf(::validStadium)
        ) { slot.stadium = it }

        if (slot.rivalFormation == "NI") {
            Regex("""\b[3-5]\s*[-–]\s*[1-5](?:\s*[-–]\s*[1-5])?\s*[AB]?\b""", RegexOption.IGNORE_CASE)
                .find(joined)?.value
                ?.replace(" ", "")
                ?.let { slot.rivalFormation = it }
        }

        if (slot.myStrength == "NI" || slot.rivalStrength == "NI") {
            Regex("""\b(\d{2,3})\s*(?:x|×|vs\.?)\s*(\d{2,3})\b""", RegexOption.IGNORE_CASE)
                .find(joined)?.let { m ->
                    val a = m.groupValues[1].toIntOrNull()
                    val b = m.groupValues[2].toIntOrNull()
                    if (a in 40..200 && b in 40..200) {
                        if (slot.myStrength == "NI") slot.myStrength = a.toString()
                        if (slot.rivalStrength == "NI") slot.rivalStrength = b.toString()
                    }
                }
        }

        if (slot.matchDate == "NI") {
            Regex("""\b([0-3]?\d/[01]?\d/(?:20)?\d{2})\b""")
                .find(joined)?.value?.let { slot.matchDate = it }
        }

        if (slot.matchTime == "NI") {
            Regex("""\b([01]?\d|2[0-3]):[0-5]\d\b""")
                .find(joined)?.value?.let { slot.matchTime = it }
        }

        if (slot.venue == "NI") {
            slot.venue = when {
                Regex("""(?i)\b(?:casa|home)\b""").containsMatchIn(joined) &&
                    !Regex("""(?i)\b(?:fora|away)\b""").containsMatchIn(joined) -> "Casa"
                Regex("""(?i)\b(?:fora|away)\b""").containsMatchIn(joined) &&
                    !Regex("""(?i)\b(?:casa|home)\b""").containsMatchIn(joined) -> "Fora"
                else -> "NI"
            }
        }

        if (slot.marking == "NI") {
            slot.marking = when {
                n.contains("marcacao a zona") || n.contains("zonal marking") -> "Marcação à zona"
                n.contains("marcacao individual") || n.contains("man marking") -> "Marcação individual"
                else -> "NI"
            }
        }

        if (slot.rivalPlan == "NI") {
            slot.rivalPlan = when {
                n.contains("jogo de passes") || n.contains("passing game") -> "Jogo de passes"
                n.contains("pelas alas") || n.contains("jogo pelas alas") || n.contains("wing play") -> "Pelas alas"
                n.contains("contra ataque") || n.contains("counter attack") -> "Contra-ataque"
                n.contains("bola longa") || n.contains("long ball") -> "Bola longa"
                n.contains("chutar de longe") || n.contains("shoot on sight") -> "Chutar de longe"
                else -> "NI"
            }
        }

        if (slot.offside == "NI") {
            slot.offside = when {
                Regex("""(?i)impedimento\s*[:|-]?\s*n[aã]o""").containsMatchIn(joined) ||
                    Regex("""(?i)offside\s*[:|-]?\s*no""").containsMatchIn(joined) -> "Não"
                Regex("""(?i)impedimento\s*[:|-]?\s*sim""").containsMatchIn(joined) ||
                    Regex("""(?i)offside\s*[:|-]?\s*yes""").containsMatchIn(joined) -> "Sim"
                else -> "NI"
            }
        }

        if (slot.secretTraining == "NI") {
            slot.secretTraining = when {
                Regex("""(?i)treino secreto\s*[:|-]?\s*sim""").containsMatchIn(joined) -> "Sim"
                Regex("""(?i)treino secreto\s*[:|-]?\s*n[aã]o""").containsMatchIn(joined) -> "Não"
                else -> "NI"
            }
        }

        if (slot.trainingCamp == "NI") {
            slot.trainingCamp = when {
                Regex("""(?i)campo de treinamento\s*[:|-]?\s*sim""").containsMatchIn(joined) -> "Sim"
                Regex("""(?i)campo de treinamento\s*[:|-]?\s*n[aã]o""").containsMatchIn(joined) -> "Não"
                else -> "NI"
            }
        }

        if (slot.bonus == "NI") {
            Regex("""\b([1-3])\s*%\b""").find(joined)
                ?.groupValues?.getOrNull(1)?.let { slot.bonus = "$it%" }
        }

        fun labeledNumber(vararg labels: String): String? {
            for (label in labels) {
                val m = Regex(
                    """(?i)\b${Regex.escape(label)}\b\s*[:|-]?\s*(\d{2,3})\b"""
                ).find(joined) ?: continue
                val v = m.groupValues[1].toIntOrNull()
                if (v in 40..200) return v.toString()
            }
            return null
        }

        if (slot.myGoalkeeper == "NI") labeledNumber("Meu GOL","Meu GK")?.let { slot.myGoalkeeper = it }
        if (slot.myDefense == "NI") labeledNumber("Minha DEF","Meu DEF")?.let { slot.myDefense = it }
        if (slot.myMidfield == "NI") labeledNumber("Meu MEI","Meu MID")?.let { slot.myMidfield = it }
        if (slot.myAttack == "NI") labeledNumber("Meu ATA","Meu ATT")?.let { slot.myAttack = it }
        if (slot.rivalGoalkeeper == "NI") labeledNumber("Rival GOL","Rival GK")?.let { slot.rivalGoalkeeper = it }
        if (slot.rivalDefense == "NI") labeledNumber("Rival DEF")?.let { slot.rivalDefense = it }
        if (slot.rivalMidfield == "NI") labeledNumber("Rival MEI","Rival MID")?.let { slot.rivalMidfield = it }
        if (slot.rivalAttack == "NI") labeledNumber("Rival ATA","Rival ATT")?.let { slot.rivalAttack = it }

        // Valores monetários explicitamente rotulados.
        if (slot.myValue == "NI") {
            extractLabeled(lines, listOf("valor do elenco","meu elenco","squad value"))
                ?.takeIf(::validMoney)?.let { slot.myValue = it }
        }

        if (slot.rivalValue == "NI") {
            extractLabeled(lines, listOf("elenco rival","valor rival","opponent value"))
                ?.takeIf(::validMoney)?.let { slot.rivalValue = it }
        }

        if (n.contains("mercado") || n.contains("transfer")) slot.marketSeen = true
        if (n.contains("treino") || n.contains("training")) slot.trainingSeen = true
    }

    private fun validReferee(v: String): Boolean {
        val n = normalize(v)
        return listOf(
            "verde","azul","amarelo","laranja","vermelho",
            "muito rigoroso","rigoroso","medio","tolerante",
            "green","blue","yellow","orange","red"
        ).any { n.contains(normalize(it)) }
    }

    private fun validStadium(v: String): Boolean {
        val n = normalize(v)
        if (Regex("""^[0-3]$""").matches(n)) return true
        return Regex("""(?i)^(?:nivel|level)\s*[0-3]$""").matches(n)
    }

    private fun validMoney(v: String): Boolean =
        Regex("""(?i)^\s*\d{1,4}(?:[.,]\d+)?\s*(?:K|M|MM|B)\s*$""").matches(v.trim())

    private fun validEntityName(v: String): Boolean {
        val n = normalize(v)
        if (n.length !in 3..50 || !n.any(Char::isLetter)) return false
        val blocked = listOf(
            "analista de dados","calendario","elenco","tatica","mercado","treino",
            "classificacao","proximo jogo","dados completos","leitura automatica",
            "sessao","osm ai coach","perfil","comunicacoes","diamantes"
        )
        return blocked.none { n.contains(it) }
    }

    private fun extractLabeled(lines: List<String>, labels: List<String>): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val n = normalize(line)
            for (label in labels) {
                val nl = normalize(label)
                if (n == nl) {
                    val next = lines.getOrNull(i + 1) ?: continue
                    if (next.isNotBlank()) return next
                }
                if (n.startsWith("$nl:") || n.startsWith("$nl -")) {
                    val afterColon = line.substringAfter(":", "")
                    val afterDash = line.substringAfter("-", "")
                    val v = (if (afterColon.isNotBlank()) afterColon else afterDash).trim()
                    if (v.isNotBlank()) return cleanValue(v)
                }
            }
        }
        return null
    }

    private fun cleanValue(v: String) =
        v.replace("|", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(80)

    private fun normalize(v: String): String =
        Normalizer.normalize(
            v.lowercase(Locale.ROOT),
            Normalizer.Form.NFD
        )
            .replace(Regex("""\p{Mn}+"""), "")
            .replace(Regex("""[^a-z0-9 :/.,%-]+"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
}
