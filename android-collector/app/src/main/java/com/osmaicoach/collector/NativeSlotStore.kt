package com.osmaicoach.collector

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class NativePlayerData(
    var name: String = "NI",
    var position: String = "NI",
    var age: String = "NI",
    var strength: String = "NI",
    var value: String = "NI",
    var training: String = "NI",
    var selling: String = "NI"
)

data class NativeCalendarGame(
    var round: String = "NI",
    var opponent: String = "NI",
    var date: String = "NI",
    var time: String = "NI",
    var venue: String = "NI",
    var score: String = "NI",
    var competition: String = "NI",
    var cup: Boolean = false
)

data class NativeSlotData(
    val id: Int,
    var team: String = "NI",
    var competition: String = "NI",
    var competitionType: String = "Liga normal",
    var nextRival: String = "NI",
    var matchDate: String = "NI",
    var matchTime: String = "NI",
    var venue: String = "NI",
    var referee: String = "NI",
    var myStrength: String = "NI",
    var rivalStrength: String = "NI",
    var myValue: String = "NI",
    var rivalValue: String = "NI",
    var myGoalkeeper: String = "NI",
    var myDefense: String = "NI",
    var myMidfield: String = "NI",
    var myAttack: String = "NI",
    var rivalGoalkeeper: String = "NI",
    var rivalDefense: String = "NI",
    var rivalMidfield: String = "NI",
    var rivalAttack: String = "NI",
    var rivalFormation: String = "NI",
    var rivalPlan: String = "NI",
    var marking: String = "NI",
    var offside: String = "NI",
    var secretTraining: String = "NI",
    var trainingCamp: String = "NI",
    var stadium: String = "NI",
    var bonus: String = "NI",
    var squadCount: Int = 0,
    var attackers: Int = 0,
    var midfielders: Int = 0,
    var defenders: Int = 0,
    var goalkeepers: Int = 0,
    var trainingCount: Int = 0,
    var sellingCount: Int = 0,
    var calendarCount: Int = 0,
    var marketSeen: Boolean = false,
    var trainingSeen: Boolean = false,
    var players: MutableList<NativePlayerData> = mutableListOf(),
    var calendar: MutableList<NativeCalendarGame> = mutableListOf(),
    var lastUpdated: Long = 0L
)

class NativeSlotStore(context: Context) {
    private val prefs = context.getSharedPreferences("native_slots_v3", Context.MODE_PRIVATE)

    fun loadAll(): MutableList<NativeSlotData> {
        val raw = prefs.getString("slots", null) ?: return MutableList(4) { NativeSlotData(it + 1) }
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(4) { index ->
                val o = if (index < arr.length()) arr.getJSONObject(index) else JSONObject()
                val players = mutableListOf<NativePlayerData>()
                val pArr = o.optJSONArray("players") ?: JSONArray()
                for (i in 0 until pArr.length()) {
                    val p = pArr.optJSONObject(i) ?: continue
                    players += NativePlayerData(
                        name=p.optString("name","NI"),
                        position=p.optString("position","NI"),
                        age=p.optString("age","NI"),
                        strength=p.optString("strength","NI"),
                        value=p.optString("value","NI"),
                        training=p.optString("training","NI"),
                        selling=p.optString("selling","NI")
                    )
                }

                val calendar = mutableListOf<NativeCalendarGame>()
                val cArr = o.optJSONArray("calendar") ?: JSONArray()
                for (i in 0 until cArr.length()) {
                    val g = cArr.optJSONObject(i) ?: continue
                    calendar += NativeCalendarGame(
                        round=g.optString("round","NI"),
                        opponent=g.optString("opponent","NI"),
                        date=g.optString("date","NI"),
                        time=g.optString("time","NI"),
                        venue=g.optString("venue","NI"),
                        score=g.optString("score","NI"),
                        competition=g.optString("competition","NI"),
                        cup=g.optBoolean("cup",false)
                    )
                }

                NativeSlotData(
                    id=index+1,
                    team=o.optString("team","NI"),
                    competition=o.optString("competition","NI"),
                    competitionType=o.optString("competitionType","Liga normal"),
                    nextRival=o.optString("nextRival","NI"),
                    matchDate=o.optString("matchDate","NI"),
                    matchTime=o.optString("matchTime","NI"),
                    venue=o.optString("venue","NI"),
                    referee=o.optString("referee","NI"),
                    myStrength=o.optString("myStrength","NI"),
                    rivalStrength=o.optString("rivalStrength","NI"),
                    myValue=o.optString("myValue","NI"),
                    rivalValue=o.optString("rivalValue","NI"),
                    myGoalkeeper=o.optString("myGoalkeeper","NI"),
                    myDefense=o.optString("myDefense","NI"),
                    myMidfield=o.optString("myMidfield","NI"),
                    myAttack=o.optString("myAttack","NI"),
                    rivalGoalkeeper=o.optString("rivalGoalkeeper","NI"),
                    rivalDefense=o.optString("rivalDefense","NI"),
                    rivalMidfield=o.optString("rivalMidfield","NI"),
                    rivalAttack=o.optString("rivalAttack","NI"),
                    rivalFormation=o.optString("rivalFormation","NI"),
                    rivalPlan=o.optString("rivalPlan","NI"),
                    marking=o.optString("marking","NI"),
                    offside=o.optString("offside","NI"),
                    secretTraining=o.optString("secretTraining","NI"),
                    trainingCamp=o.optString("trainingCamp","NI"),
                    stadium=o.optString("stadium","NI"),
                    bonus=o.optString("bonus","NI"),
                    squadCount=o.optInt("squadCount",players.size),
                    attackers=o.optInt("attackers",0),
                    midfielders=o.optInt("midfielders",0),
                    defenders=o.optInt("defenders",0),
                    goalkeepers=o.optInt("goalkeepers",0),
                    trainingCount=o.optInt("trainingCount",0),
                    sellingCount=o.optInt("sellingCount",0),
                    calendarCount=o.optInt("calendarCount",calendar.size),
                    marketSeen=o.optBoolean("marketSeen",false),
                    trainingSeen=o.optBoolean("trainingSeen",false),
                    players=players,
                    calendar=calendar,
                    lastUpdated=o.optLong("lastUpdated",0L)
                )
            }
        }.getOrElse { MutableList(4) { NativeSlotData(it + 1) } }
    }

    fun saveAll(slots: List<NativeSlotData>) {
        val arr = JSONArray()
        slots.forEach { s ->
            arr.put(JSONObject().apply {
                put("team",s.team); put("competition",s.competition); put("competitionType",s.competitionType)
                put("nextRival",s.nextRival); put("matchDate",s.matchDate); put("matchTime",s.matchTime)
                put("venue",s.venue); put("referee",s.referee); put("myStrength",s.myStrength); put("rivalStrength",s.rivalStrength)
                put("myValue",s.myValue); put("rivalValue",s.rivalValue)
                put("myGoalkeeper",s.myGoalkeeper); put("myDefense",s.myDefense); put("myMidfield",s.myMidfield); put("myAttack",s.myAttack)
                put("rivalGoalkeeper",s.rivalGoalkeeper); put("rivalDefense",s.rivalDefense); put("rivalMidfield",s.rivalMidfield); put("rivalAttack",s.rivalAttack)
                put("rivalFormation",s.rivalFormation); put("rivalPlan",s.rivalPlan); put("marking",s.marking); put("offside",s.offside)
                put("secretTraining",s.secretTraining); put("trainingCamp",s.trainingCamp); put("stadium",s.stadium); put("bonus",s.bonus)
                put("squadCount",s.squadCount); put("attackers",s.attackers); put("midfielders",s.midfielders); put("defenders",s.defenders); put("goalkeepers",s.goalkeepers)
                put("trainingCount",s.trainingCount); put("sellingCount",s.sellingCount); put("calendarCount",s.calendarCount)
                put("marketSeen",s.marketSeen); put("trainingSeen",s.trainingSeen); put("lastUpdated",s.lastUpdated)

                put("players", JSONArray().apply {
                    s.players.forEach { p ->
                        put(JSONObject().apply {
                            put("name",p.name); put("position",p.position); put("age",p.age)
                            put("strength",p.strength); put("value",p.value)
                            put("training",p.training); put("selling",p.selling)
                        })
                    }
                })
                put("calendar", JSONArray().apply {
                    s.calendar.forEach { g ->
                        put(JSONObject().apply {
                            put("round",g.round); put("opponent",g.opponent); put("date",g.date)
                            put("time",g.time); put("venue",g.venue); put("score",g.score)
                            put("competition",g.competition); put("cup",g.cup)
                        })
                    }
                })
            })
        }
        prefs.edit().putString("slots", arr.toString()).apply()
    }
}
