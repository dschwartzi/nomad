package ai.nomad.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(msg: MessageEntity): Long

    @Query("UPDATE messages SET forwardedToTelegram = 1, telegramMessageId = :tgId WHERE id = :id")
    suspend fun markForwarded(id: Long, tgId: Long?)

    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentFor(address: String, limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    fun recentAll(limit: Int = 200): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentAllOnce(limit: Int = 10): List<MessageEntity>
}

@Dao
interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conv: ConversationEntity)

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun all(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC LIMIT :limit")
    suspend fun recent(limit: Int = 20): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE address = :address")
    suspend fun get(address: String): ConversationEntity?
}

@Dao
interface EventDao {
    @Insert
    suspend fun insert(e: EventEntity): Long

    @Query("SELECT * FROM event_log ORDER BY timestamp DESC LIMIT :limit")
    fun recent(limit: Int = 100): Flow<List<EventEntity>>

    @Query("DELETE FROM event_log WHERE timestamp < :cutoff")
    suspend fun prune(cutoff: Long)
}
