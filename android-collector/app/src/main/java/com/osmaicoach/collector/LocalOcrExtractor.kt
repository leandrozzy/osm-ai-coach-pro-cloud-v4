package com.osmaicoach.collector

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import java.io.File
import java.text.Normalizer
import java.util.Locale

class LocalOcrExtractor {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(file: File): String {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            recognizer.process(image).await().text.orEmpty()
        } catch (_: Throwable) {
            ""
        } finally {
            bitmap.recycle()
        }
    }

    fun applyToSlot(slot: NativeSlotData, texts: List<String>) {
        if (texts.isEmpty()) return
        val joined = texts.joinToString("\n")
        val lines = joined.lines().map { it.trim() }.filter { it.length in 2..120 }
        val n = normalize(joined)

        extractLabeled(lines, listOf("meu time","my team","clube","club"))?.let { candidate ->
            if (slot.team=="NI" && validName(candidate)) slot.team=candidate
        }
        extractLabeled(lines, listOf("competição","competicao","competition","liga","league"))?.let { candidate ->
            if (slot.competition=="NI" && validName(candidate)) slot.competition=candidate
        }
        extractLabeled(lines, listOf("adversário","adversario","opponent","rival","próximo rival","proximo rival"))?.let { candidate ->
            if (slot.nextRival=="NI" && validName(candidate)) slot.nextRival=candidate
        }

        extractLabeled(lines, listOf("árbitro","arbitro","referee"))?.let { candidate ->
            val r = normalize(candidate)
            val valid = listOf(
                "verde","azul","amarelo","laranja","vermelho",
                "muito rigoroso","rigoroso","medio","tolerante","leniente",
                "green","blue","yellow","orange","red"
            ).any { r.contains(normalize(it)) }
            if (slot.referee=="NI" && valid) slot.referee=candidate
        }

        extractLabeled(lines, listOf("estádio","estadio","stadium"))?.let {
            if (slot.stadium=="NI") slot.stadium=it
        }

        Regex("\\b[3-5]\\s*[-–]\\s*[1-5]\\s*[-–]\\s*[1-5]\\s*[AB]?\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.value?.replace(" ","")?.let { if (slot.rivalFormation=="NI") slot.rivalFormation=it }

        Regex("\\b(\\d{2,3})\\s*(?:x|×|vs\\.?)\\s*(\\d{2,3})\\b", RegexOption.IGNORE_CASE)
            .find(joined)?.let {
                if (slot.myStrength=="NI") slot.myStrength=it.groupValues[1]
                if (slot.rivalStrength=="NI") slot.rivalStrength=it.groupValues[2]
            }

        val fullDate = Regex("\\b([0-3]?\\d/[01]?\\d/(?:20)?\\d{2})\\b").find(joined)?.value
        if (fullDate != null && slot.matchDate=="NI") slot.matchDate=fullDate

        Regex("\\b([01]?\\d|2[0-3]):[0-5]\\d\\b").find(joined)?.value
            ?.let { if (slot.matchTime=="NI") slot.matchTime=it }

        if (slot.venue=="NI") {
            slot.venue = when {
                n.contains(" em casa ") || n.contains(" casa ") || n.contains(" home ") -> "Casa"
                n.contains(" fora ") || n.contains(" away ") -> "Fora"
                else -> "NI"
            }
        }
        if (slot.marking=="NI") {
            slot.marking = when {
                n.contains("marcacao a zona") || n.contains("zonal") -> "Marcação à zona"
                n.contains("marcacao individual") || n.contains("individual marking") -> "Marcação individual"
                else -> "NI"
            }
        }
        if (slot.rivalPlan=="NI") {
            slot.rivalPlan = when {
                n.contains("jogo de passes") || n.contains("passing game") -> "Jogo de passes"
                n.contains("pelas alas") || n.contains("wing play") -> "Pelas alas"
                n.contains("contra ataque") || n.contains("counter attack") -> "Contra-ataque"
                n.contains("bola longa") || n.contains("long ball") -> "Bola longa"
                n.contains("chutar de longe") || n.contains("shoot on sight") -> "Chutar de longe"
                else -> "NI"
            }
        }
        if (slot.offside=="NI") {
            slot.offside = when {
                n.contains("impedimento nao") || n.contains("offside no") -> "Não"
                n.contains("impedimento sim") || n.contains("offside yes") -> "Sim"
                else -> "NI"
            }
        }


        // Valores monetários do elenco (ex.: 310M, 24,4M, 1,2B).
        val moneyValues = Regex("\\b\\d{1,4}(?:[.,]\\d+)?\\s*(?:M|B|K|MM)\\b", RegexOption.IGNORE_CASE)
            .findAll(joined).map { it.value.replace(" ","") }.toList()
        if (slot.myValue=="NI" && moneyValues.isNotEmpty()) slot.myValue=moneyValues.first()
        if (slot.rivalValue=="NI" && moneyValues.size>1) slot.rivalValue=moneyValues[1]

        // Bônus do estádio/login costuma aparecer como 1%, 2% ou 3%.
        Regex("\\b([1-3])\\s*%\\b").find(joined)?.groupValues?.getOrNull(1)?.let {
            if(slot.bonus=="NI") slot.bonus="$it%"
        }

        // Forças por setor, quando a tela mostra quatro valores em sequência.
        fun labeledNumber(vararg labels:String):String? {
            for(label in labels){
                val re=Regex("(?i)"+Regex.escape(label)+"\\s*[:|-]?\\s*(\\d{1,3})")
                re.find(joined)?.groupValues?.getOrNull(1)?.let{return it}
            }
            return null
        }
        if(slot.myGoalkeeper=="NI") labeledNumber("GOL","GK","goleiro")?.let{slot.myGoalkeeper=it}
        if(slot.myDefense=="NI") labeledNumber("DEF","defesa")?.let{slot.myDefense=it}
        if(slot.myMidfield=="NI") labeledNumber("MEI","MID","meio-campo","meio campo")?.let{slot.myMidfield=it}
        if(slot.myAttack=="NI") labeledNumber("ATA","ATT","ataque")?.let{slot.myAttack=it}

        if(slot.secretTraining=="NI") {
            slot.secretTraining = when {
                n.contains("treino secreto sim") -> "Sim"
                n.contains("treino secreto nao") -> "Não"
                else -> "NI"
            }
        }
        if(slot.trainingCamp=="NI") {
            slot.trainingCamp = when {
                n.contains("campo de treinamento sim") || n.contains("training camp yes") -> "Sim"
                n.contains("campo de treinamento nao") || n.contains("training camp no") -> "Não"
                else -> "NI"
            }
        }

        // Tenta preservar jogadores individuais mesmo quando a tela não contém a palavra "Elenco".
        // O OCR do OSM costuma produzir nome + idade/valor/força em linhas próximas.
        val ageRe=Regex("\\b(1[6-9]|[2-3]\\d|4[0-5])\\s*(?:anos|years)?\\b",RegexOption.IGNORE_CASE)
        val strengthRe=Regex("\\b(\\d{2,3})\\b")
        for(i in lines.indices){
            val line=cleanValue(lines[i])
            if(!validName(line) || looksLikeUiLabel(line) || line.length>40) continue
            val window=lines.subList(i, minOf(lines.size,i+4)).joinToString(" ")
            val age=ageRe.find(window)?.groupValues?.getOrNull(1) ?: continue
            val value=moneyValues.firstOrNull { window.contains(it,ignoreCase=true) } ?: "NI"
            val nums=strengthRe.findAll(window).map{it.groupValues[1]}.filter{it.toIntOrNull() in 40..200}.toList()
            val strength=nums.lastOrNull() ?: "NI"
            val pos=when {
                window.contains(Regex("\\b(?:ATA|ATT|FW)\\b",RegexOption.IGNORE_CASE))->"ATA"
                window.contains(Regex("\\b(?:MEI|MID|MF)\\b",RegexOption.IGNORE_CASE))->"MEI"
                window.contains(Regex("\\b(?:DEF|CB|LB|RB)\\b",RegexOption.IGNORE_CASE))->"DEF"
                window.contains(Regex("\\b(?:GOL|GK)\\b",RegexOption.IGNORE_CASE))->"GOL"
                else->"NI"
            }
            if(slot.players.none{normalize(it.name)==normalize(line)}) {
                slot.players += NativePlayerData(name=line,position=pos,age=age,strength=strength,value=value)
            }
        }
        if(slot.players.isNotEmpty()){
            slot.squadCount=maxOf(slot.squadCount,slot.players.size)
            slot.attackers=maxOf(slot.attackers,slot.players.count{it.position=="ATA"})
            slot.midfielders=maxOf(slot.midfielders,slot.players.count{it.position=="MEI"})
            slot.defenders=maxOf(slot.defenders,slot.players.count{it.position=="DEF"})
            slot.goalkeepers=maxOf(slot.goalkeepers,slot.players.count{it.position=="GOL"})
        }

        if(n.contains("mercado") || n.contains("transfer")) slot.marketSeen=true
        if(n.contains("treino") || n.contains("training")) slot.trainingSeen=true

        val dates = Regex("\\b[0-3]?\\d/[01]?\\d/(?:20)?\\d{2}\\b").findAll(joined).count()
        if (dates > slot.calendarCount) slot.calendarCount = dates

        val playerRows = lines.count {
            Regex("\\b\\d{1,2}\\s*(?:anos|years)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it)
        }
        if (playerRows > slot.squadCount) slot.squadCount = playerRows

        slot.attackers = maxOf(slot.attackers, countTokens(lines, listOf("ATA","ATT")))
        slot.midfielders = maxOf(slot.midfielders, countTokens(lines, listOf("MEI","MID")))
        slot.defenders = maxOf(slot.defenders, countTokens(lines, listOf("DEF")))
        slot.goalkeepers = maxOf(slot.goalkeepers, countTokens(lines, listOf("GOL","GK")))
    }

    private fun validName(v:String):Boolean {
        val n=normalize(v)
        if(n.length !in 2..60) return false
        if(n in setOf("boa","ni","sim","nao","casa","fora","liga normal")) return false
        return n.any { it.isLetter() }
    }

    private fun extractLabeled(lines: List<String>, labels: List<String>): String? {
        for (i in lines.indices) {
            val line = lines[i]
            val n = normalize(line)
            val matched = labels.firstOrNull {
                n == normalize(it) ||
                n.startsWith(normalize(it)+" ") ||
                n.startsWith(normalize(it)+":")
            }
            if (matched != null) {
                val same = line.substringAfter(":", "").trim()
                if (same.isNotBlank() && normalize(same) != normalize(matched)) return cleanValue(same)
                if (i+1 < lines.size) {
                    val next = cleanValue(lines[i+1])
                    if (next.length in 2..60 && !looksLikeUiLabel(next)) return next
                }
            }
        }
        return null
    }

    private fun looksLikeUiLabel(v:String):Boolean {
        val n=normalize(v)
        return listOf(
            "calendario","elenco","tatica","mercado","treino","classificacao",
            "resultado","proximo jogo","arbitro","formacao","forca"
        ).any { n==it }
    }

    private fun cleanValue(v:String)=v.replace("|"," ").replace(Regex("\\s+")," ").trim().take(80)

    private fun countTokens(lines:List<String>,tokens:List<String>):Int =
        lines.count { line -> tokens.any { t -> Regex("\\b${Regex.escape(t)}\\b",RegexOption.IGNORE_CASE).containsMatchIn(line) } }

    private fun normalize(v:String):String =
        Normalizer.normalize(v.lowercase(Locale.ROOT),Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"),"")
            .replace(Regex("[^a-z0-9 :/.-]+")," ")
            .replace(Regex("\\s+")," ")
            .trim()
}
