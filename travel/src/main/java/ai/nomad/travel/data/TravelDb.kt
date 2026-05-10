package ai.nomad.travel.data

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class TMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,
    val body: String,
    val timestamp: Long,
    /** 0 = inbound (received from address), 1 = outbound (we sent), 2 = pending outbound. */
    val direction: Int,
    /** Client id used to ack outbound. */
    val clientId: String? = null
) {
    companion object {
        const val IN = 0
        const val OUT = 1
        const val PENDING = 2
    }
}

@Entity(tableName = "conversations")
data class TConversation(
    @PrimaryKey val address: String,
    val displayName: String?,
    val lastMessageTime: Long,
    val lastMessagePreview: String,
    val unread: Int = 0
)

@Entity(tableName = "contacts")
data class TContact(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Comma-separated list of phone numbers. */
    val numbers: String
)

@Dao
interface TMessageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: TMessage): Long

    @Query("UPDATE messages SET direction = :direction WHERE clientId = :clientId")
    suspend fun setDirectionByClient(clientId: String, direction: Int)

    @Query("DELETE FROM messages WHERE clientId = :clientId")
    suspend fun deleteByClient(clientId: String)

    @Query("SELECT * FROM messages WHERE address = :address ORDER BY timestamp ASC")
    fun forAddress(address: String): Flow<List<TMessage>>

    @Query("SELECT COUNT(*) FROM messages WHERE address = :address")
    suspend fun countFor(address: String): Int
}

@Dao
interface TConversationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(c: TConversation)

    @Query("SELECT * FROM conversations ORDER BY lastMessageTime DESC")
    fun all(): Flow<List<TConversation>>

    @Query("SELECT * FROM conversations WHERE address = :address")
    suspend fun get(address: String): TConversation?

    @Query("UPDATE conversations SET unread = 0 WHERE address = :address")
    suspend fun markRead(address: String)
}

@Dao
interface TContactDao {
    @Query("DELETE FROM contacts")
    suspend fun clear()

    @Insert
    suspend fun insertAll(contacts: List<TContact>)

    @Query("SELECT * FROM contacts ORDER BY name COLLATE NOCASE ASC")
    fun all(): Flow<List<TContact>>

    @Query("SELECT * FROM contacts WHERE name LIKE :q OR numbers LIKE :q ORDER BY name COLLATE NOCASE ASC LIMIT 100")
    suspend fun search(q: String): List<TContact>
}

@Database(
    entities = [TMessage::class, TConversation::class, TContact::class],
    version = 1,
    exportSchema = false
)
abstract class TravelDb : RoomDatabase() {
    abstract fun messageDao(): TMessageDao
    abstract fun conversationDao(): TConversationDao
    abstract fun contactDao(): TContactDao

    companion object {
        @Volatile private var instance: TravelDb? = null
        fun get(context: Context): TravelDb {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    TravelDb::class.java,
                    "nomad_travel.db"
                ).build().also { instance = it }
            }
        }
    }
}
