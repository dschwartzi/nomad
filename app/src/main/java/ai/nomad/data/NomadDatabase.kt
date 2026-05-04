package ai.nomad.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [MessageEntity::class, ConversationEntity::class, EventEntity::class],
    version = 1,
    exportSchema = false
)
abstract class NomadDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun eventDao(): EventDao

    companion object {
        @Volatile private var INSTANCE: NomadDatabase? = null
        fun get(context: Context): NomadDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    NomadDatabase::class.java,
                    "nomad.db"
                ).build().also { INSTANCE = it }
            }
    }
}
