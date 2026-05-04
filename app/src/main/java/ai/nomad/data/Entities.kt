package ai.nomad.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val address: String,          // phone number of the other party
    val body: String,
    val timestamp: Long,          // epoch millis
    val direction: Int,           // 0=inbound, 1=outbound
    val forwardedToTelegram: Boolean = false,
    val telegramMessageId: Long? = null
) {
    companion object {
        const val IN = 0
        const val OUT = 1
    }
}

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val address: String,      // normalized phone number
    val displayName: String?,
    val lastMessageTime: Long,
    val lastMessagePreview: String,
    val unread: Int = 0
)

@Entity(tableName = "event_log")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val level: String, // INFO, WARN, ERROR
    val message: String
)
