package com.osmaicoach.collector

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.Normalizer
import java.util.Locale

/**
 * V20 SAFE OCR
 * Só grava valores quando existe evidência forte.
 * Dado duvidoso fica NI. Não cria jogadores a partir de linhas soltas.
 */
class LocalOcrExtractor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        var cropped: android.graphics.Bitmap? = null
        return try {
            val top = (bitmap.height * 0.060f).toInt().coerceAtLeast(0)
            val bottom = (bitmap.height * 0.080f).toInt().coerceAtLeast(0)
            val h = (bitmap.height - top - bottom).coerceAtLeast(1)
            cropped = android.graphics.Bitmap.createBitmap(bitmap, 0, top, bitmap.width, h)
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
        val joined = texts.joinToString("\n")
        val lines = joined.lines().map { cleanValue(it) }.filter { it.length in 2..100 }
        val n = normalize(joined)

        extractLabeled(lines, listOf("adversário","adversario","opponent","rival"))?.let {
            if (slot.nextRival=="NI" && validEntityName(it)) slot.nextRival=it
        }

        extractLabeled(lines, listOf("árbitro","arbitro","referee"))?.let {
            if (slot.referee=="NI" && validReferee(it)) slot.referee=it
        }

        Regex("\\b[3-5]\\s*[-–]\\s*[1-5]\\s*[-–]\\s*[1-5]\\s*[AB]?\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.value?.replace(" ","")?.let {
                if (slot.rivalFormation=="NI") slot.rivalFormation=it
            }

        Regex("\\b(\\d{2,3})\\s*(?:x|×|vs\\.?)\\s*(\\d{2,3})\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.let {
                val a=it.groupValues[1].toIntOrNull()
                val b=it.groupValues[2].toIntOrNull()
                if(a in 40..200 && b in 40..200){
                    if(slot.myStrength=="NI") slot.myStrength=a.toString()
                    if(slot.rivalStrength=="NI") slot.rivalStrength=b.toString()
                }
            }

        Regex("\\b([0-3]?\\d/[01]?\\d/(?:20)?\\d{2})\\b").find(joined)?.value?.let {
            if(slot.matchDate=="NI") slot.matchDate=it
        }

        if(slot.venue=="NI"){
            slot.venue = when {
                Regex("(?i)\\b(?:joga|jogo|partida)\\s+(?:em\\s+)?casa\\b").containsMatchIn(joined) -> "Casa"
                Regex("(?i)\\b(?:joga|jogo|partida)\\s+(?:fora|away)\\b").containsMatchIn(joined) -> "Fora"
                else -> "NI"
            }
        }

        if(slot.marking=="NI"){
            slot.marking = when {
                n.contains("marcacao a zona") || n.contains("zonal marking") -> "Marcação à zona"
                n.contains("marcacao individual") || n.contains("man marking") -> "Marcação individual"
                else -> "NI"
            }
        }

        if(slot.rivalPlan=="NI"){
            slot.rivalPlan = when {
                n.contains("jogo de passes") || n.contains("passing game") -> "Jogo de passes"
                n.contains("pelas alas") || n.contains("wing play") -> "Pelas alas"
                n.contains("contra ataque") || n.contains("counter attack") -> "Contra-ataque"
                n.contains("bola longa") || n.contains("long ball") -> "Bola longa"
                n.contains("chutar de longe") || n.contains("shoot on sight") -> "Chutar de longe"
                else -> "NI"
            }
        }

        if(slot.offside=="NI"){
            slot.offside = when {
                Regex("(?i)impedimento\\s*[:|-]?\\s*n[aã]o").containsMatchIn(joined) ||
                    Regex("(?i)offside\\s*[:|-]?\\s*no").containsMatchIn(joined) -> "Não"
                Regex("(?i)impedimento\\s*[:|-]?\\s*sim").containsMatchIn(joined) ||
                    Regex("(?i)offside\\s*[:|-]?\\s*yes").containsMatchIn(joined) -> "Sim"
                else -> "NI"
            }
        }

        if(slot.secretTraining=="NI"){
            slot.secretTraining = when {
                Regex("(?i)treino secreto\\s*[:|-]?\\s*sim").containsMatchIn(joined) -> "Sim"
                Regex("(?i)treino secreto\\s*[:|-]?\\s*n[aã]o").containsMatchIn(joined) -> "Não"
                else -> "NI"
            }
        }

        if(slot.trainingCamp=="NI"){
            slot.trainingCamp = when {
                Regex("(?i)campo de treinamento\\s*[:|-]?\\s*sim").containsMatchIn(joined) -> "Sim"
                Regex("(?i)campo de treinamento\\s*[:|-]?\\s*n[aã]o").containsMatchIn(joined) -> "Não"
                else -> "NI"
            }
        }

        Regex("\\b([1-3])\\s*%\\b").find(joined)?.groupValues?.getOrNull(1)?.let {
            if(slot.bonus=="NI") slot.bonus="$it%"
        }

        fun labeledNumber(vararg labels:String):String?{
            for(label in labels){
                val m=Regex("(?i)\\b"+Regex.escape(label)+"\\b\\s*[:|-]?\\s*(\\d{2,3})\\b")
                    .find(joined) ?: continue
                val v=m.groupValues[1].toIntOrNull()
                if(v in 40..200) return v.toString()
            }
            return null
        }

        if(slot.myGoalkeeper=="NI") labeledNumber("Meu GOL","Meu GK")?.let{slot.myGoalkeeper=it}
        if(slot.myDefense=="NI") labeledNumber("Minha DEF","Meu DEF")?.let{slot.myDefense=it}
        if(slot.myMidfield=="NI") labeledNumber("Meu MEI","Meu MID")?.let{slot.myMidfield=it}
        if(slot.myAttack=="NI") labeledNumber("Meu ATA","Meu ATT")?.let{slot.myAttack=it}
        if(slot.rivalGoalkeeper=="NI") labeledNumber("Rival GOL","Rival GK")?.let{slot.rivalGoalkeeper=it}
        if(slot.rivalDefense=="NI") labeledNumber("Rival DEF")?.let{slot.rivalDefense=it}
        if(slot.rivalMidfield=="NI") labeledNumber("Rival MEI","Rival MID")?.let{slot.rivalMidfield=it}
        if(slot.rivalAttack=="NI") labeledNumber("Rival ATA","Rival ATT")?.let{slot.rivalAttack=it}

        if(n.contains("mercado") || n.contains("transfer")) slot.marketSeen=true
        if(n.contains("treino") || n.contains("training")) slot.trainingSeen=true

        // Jogadores individuais não são inferidos de OCR solto.
    }

    private fun validReferee(v:String):Boolean{
        val n=normalize(v)
        return listOf(
            "verde","azul","amarelo","laranja","vermelho",
            "muito rigoroso","rigoroso","medio","tolerante",
            "green","blue","yellow","orange","red"
        ).any { n.contains(normalize(it)) }
    }

    private fun validEntityName(v:String):Boolean{
        val n=normalize(v)
        if(n.length !in 3..45 || !n.any{it.isLetter()}) return false
        val blocked=listOf(
            "analista de dados","calendario","elenco","tatica","mercado","treino",
            "classificacao","proximo jogo","dados completos","leitura automatica",
            "sessao","osm ai coach","perfil","comunicacoes","diamantes"
        )
        return blocked.none { n.contains(it) }
    }

    private fun extractLabeled(lines: List<String>, labels: List<String>): String? {
        for(i in lines.indices){
            val line=lines[i]
            val n=normalize(line)
            for(label in labels){
                val nl=normalize(label)
                if(n==nl){
                    val next=lines.getOrNull(i+1) ?: continue
                    if(validEntityName(next) || validReferee(next)) return next
                }
                if(n.startsWith("$nl:") || n.startsWith("$nl -")){
                    val afterColon=line.substringAfter(":", "")
                    val afterDash=line.substringAfter("-", "")
                    val v=(if(afterColon.isNotBlank()) afterColon else afterDash).trim()
                    if(v.isNotBlank()) return cleanValue(v)
                }
            }
        }
        return null
    }

    private fun cleanValue(v:String)=v.replace("|"," ").replace(Regex("\\s+")," ").trim().take(80)

    private fun normalize(v:String):String =
        Normalizer.normalize(v.lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"),"")
            .replace(Regex("[^a-z0-9 :/.-]+")," ")
            .replace(Regex("\\s+")," ")
            .trim()
}
