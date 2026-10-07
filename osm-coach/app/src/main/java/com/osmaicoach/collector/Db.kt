package com.osmaicoach.collector

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "fields", primaryKeys = ["slotId", "fkey"])
data class FieldEntity(
    val slotId: Int,
    val fkey: String,
    val fvalue: String,
    val conf: Double,
    val updatedAt: Long,
    val source: String
)

@Entity(tableName = "players", primaryKeys = ["slotId", "owner", "nameKey"])
data class PlayerEntity(
    val slotId: Int,
    val owner: String,
    val nameKey: String,
    val name: String,
    val age: Int?,
    val posCode: String?,
    val cat: String?,
    val strength: Int?,
    val valueText: String?,
    val training: Boolean?,
    val forSale: Boolean?,
    val updatedAt: Long
)

@Entity(tableName = "matches", primaryKeys = ["slotId", "mkey"])
data class MatchEntity(
    val slotId: Int,
    val mkey: String,
    val label: String,
    val round: Int?,
    val date: String?,
    val time: String?,
    val home: Boolean?,
    val scoreMine: Int?,
    val scoreOpp: Int?,
    val result: String?,
    val opponent: String?,
    val opponentNick: String?,
    val updatedAt: Long
)

@Entity(tableName = "listings", primaryKeys = ["slotId", "nameKey"])
data class ListingEntity(
    val slotId: Int,
    val nameKey: String,
    val name: String,
    val age: Int?,
    val posCode: String?,
    val cat: String?,
    val strength: Int?,
    val priceText: String?,
    val club: String?,
    val sellerNick: String?,
    val seenAt: Long
)

@Entity(tableName = "snapshots")
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: String,
    val slotId: Int,
    val owner: String?,
    val json: String,
    val at: Long
)

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val endedAt: Long?,
    val state: String
)

@Entity(tableName = "screens")
data class ScreenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val at: Long,
    val type: String,
    val slotId: Int,
    val slotConf: Double,
    val hash: String,
    val imagePath: String?,
    val ocrChars: Int,
    val extracted: Int,
    val aiState: String,
    val note: String
)

@Entity(tableName = "learning")
data class LearningEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val slotId: Int,
    val kind: String,
    val text: String
)

@Entity(tableName = "plans", primaryKeys = ["slotId", "kind"])
data class PlanEntity(val slotId: Int, val kind: String, val json: String, val at: Long)

data class TypeStat(val type: String, val c: Long)

@Dao
interface CoachDao {
    @Query("SELECT * FROM fields WHERE slotId = :slot")
    suspend fun fieldsOf(slot: Int): List<FieldEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putField(f: FieldEntity)

    @Query("DELETE FROM fields WHERE slotId = :slot AND fkey LIKE 'rival.%'")
    suspend fun deleteRivalFields(slot: Int)

    @Query("DELETE FROM fields WHERE source = 'legacy'")
    suspend fun deleteLegacyFields(): Int

    @Query("SELECT * FROM players WHERE slotId = :slot")
    suspend fun playersOf(slot: Int): List<PlayerEntity>

    @Query("SELECT * FROM players WHERE slotId = :slot AND owner = :owner AND nameKey = :nameKey LIMIT 1")
    suspend fun player(slot: Int, owner: String, nameKey: String): PlayerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlayer(p: PlayerEntity)

    @Query("DELETE FROM players WHERE slotId = :slot AND owner = 'RIVAL'")
    suspend fun deleteRivalPlayers(slot: Int)

    @Query("SELECT * FROM matches WHERE slotId = :slot")
    suspend fun matchesOf(slot: Int): List<MatchEntity>

    @Query("SELECT * FROM matches WHERE slotId = :slot AND mkey = :mkey LIMIT 1")
    suspend fun match(slot: Int, mkey: String): MatchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putMatch(m: MatchEntity)

    @Query("SELECT * FROM listings WHERE slotId = :slot")
    suspend fun listingsOf(slot: Int): List<ListingEntity>

    @Query("SELECT * FROM listings WHERE slotId = :slot AND nameKey = :nameKey LIMIT 1")
    suspend fun listing(slot: Int, nameKey: String): ListingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putListing(l: ListingEntity)

    @Query("DELETE FROM listings WHERE slotId = :slot AND seenAt < :before")
    suspend fun deleteOldListings(slot: Int, before: Long)

    @Query("DELETE FROM snapshots WHERE kind = :kind AND slotId = :slot AND (owner IS :owner)")
    suspend fun deleteSnapshots(kind: String, slot: Int, owner: String?)

    @Insert
    suspend fun insertSnapshot(s: SnapshotEntity)

    @Query("SELECT * FROM snapshots WHERE kind = :kind AND slotId = :slot ORDER BY at DESC")
    suspend fun snapshots(kind: String, slot: Int): List<SnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putSession(s: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :id LIMIT 1")
    suspend fun session(id: String): SessionEntity?

    @Query("SELECT * FROM sessions ORDER BY startedAt DESC LIMIT 30")
    suspend fun sessions(): List<SessionEntity>

    @Insert
    suspend fun insertScreen(s: ScreenEntity): Long

    @Query("SELECT * FROM screens WHERE id = :id LIMIT 1")
    suspend fun screen(id: Long): ScreenEntity?

    @Query("UPDATE screens SET aiState = :state, extracted = :extracted, note = :note WHERE id = :id")
    suspend fun updateScreenAi(id: Long, state: String, extracted: Int, note: String)

    @Query("UPDATE screens SET slotId = :slot, slotConf = :conf, extracted = :extracted, note = :note WHERE id = :id")
    suspend fun updateScreenSlot(id: Long, slot: Int, conf: Double, extracted: Int, note: String)

    @Query("SELECT * FROM screens WHERE aiState = 'pending' ORDER BY at ASC LIMIT :limit")
    suspend fun pendingAi(limit: Int): List<ScreenEntity>

    @Query("SELECT * FROM screens WHERE slotId = 0 AND imagePath IS NOT NULL AND aiState != 'done' ORDER BY at ASC LIMIT :limit")
    suspend fun unassignedWithImage(limit: Int): List<ScreenEntity>

    @Query("SELECT * FROM screens WHERE imagePath IS NOT NULL ORDER BY at DESC LIMIT :limit")
    suspend fun savedScreens(limit: Int): List<ScreenEntity>

    @Query("SELECT type, COUNT(*) AS c FROM screens WHERE sessionId = :id GROUP BY type")
    suspend fun typeCounts(id: String): List<TypeStat>

    @Query("SELECT type, MAX(at) AS c FROM screens WHERE slotId = :slot AND extracted > 0 GROUP BY type")
    suspend fun lastBySection(slot: Int): List<TypeStat>

    @Query("SELECT COUNT(*) FROM screens WHERE slotId = 0 AND sessionId = :id AND type != 'HUB'")
    suspend fun unassignedCount(id: String): Int

    @Insert
    suspend fun insertLearning(l: LearningEntity)

    @Query("SELECT * FROM learning WHERE slotId = :slot OR slotId = 0 ORDER BY at DESC LIMIT 60")
    suspend fun learningOf(slot: Int): List<LearningEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putPlan(p: PlanEntity)

    @Query("SELECT * FROM plans WHERE slotId = :slot AND kind = :kind LIMIT 1")
    suspend fun plan(slot: Int, kind: String): PlanEntity?
}

@Database(
    entities = [
        FieldEntity::class, PlayerEntity::class, MatchEntity::class, ListingEntity::class,
        SnapshotEntity::class, SessionEntity::class, ScreenEntity::class, LearningEntity::class,
        PlanEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class CoachDb : RoomDatabase() {
    abstract fun dao(): CoachDao

    companion object {
        @Volatile
        private var inst: CoachDb? = null

        fun get(ctx: Context): CoachDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, CoachDb::class.java, "osm_coach.db")
                .build().also { inst = it }
        }
    }
}
