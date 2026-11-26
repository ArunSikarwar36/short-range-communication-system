package ai.paai.link.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey val id: String,
    val staticKey: ByteArray,
    /** Name the device announced when pairing. */
    val name: String,
    /** Optional name the user gave it on this phone. */
    val localName: String?,
    val pairedAt: Long,
) {
    val displayName: String get() = localName?.takeIf { it.isNotBlank() } ?: name
    override fun equals(other: Any?) = other is PeerEntity && other.id == id && other.name == name && other.localName == localName && other.pairedAt == pairedAt
    override fun hashCode() = id.hashCode()
}

@Entity(tableName = "messages", indices = [Index("conversationId"), Index("status")])
data class MessageEntity(
    @PrimaryKey val messageId: String,
    val conversationId: String,
    val isGroup: Boolean,
    val author: String,
    val authorName: String,
    val recipient: String,
    val outgoing: Boolean,
    val body: String,
    val sentAt: Long,
    val receivedAt: Long?,
    val status: String,
    val attempts: Int,
    val nextAttemptAt: Long,
    val read: Boolean,
)

@Entity(tableName = "calls")
data class CallLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val peerId: String,
    val peerName: String,
    val outgoing: Boolean,
    val startedAt: Long,
    val durationMs: Long,
    val result: String,
)

/** Remembers group names so group conversations stay readable after the group ends. */
@Entity(tableName = "group_names")
data class GroupNameEntity(@PrimaryKey val id: String, val name: String, val lastActive: Long)

data class UnreadCount(val conversationId: String, val n: Int)

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY name") suspend fun all(): List<PeerEntity>
    @Query("SELECT * FROM peers ORDER BY COALESCE(localName, name) COLLATE NOCASE") fun observe(): Flow<List<PeerEntity>>
    @Upsert suspend fun upsert(p: PeerEntity)
    @Query("DELETE FROM peers WHERE id = :id") suspend fun delete(id: String)
    @Query("UPDATE peers SET localName = :localName WHERE id = :id") suspend fun rename(id: String, localName: String?)
}

@Dao
interface MessageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(m: MessageEntity): Long
    @Update suspend fun update(m: MessageEntity)
    @Query("SELECT * FROM messages WHERE messageId = :id") suspend fun get(id: String): MessageEntity?
    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status IN ('QUEUED','SENT','RETRYING')") suspend fun pending(): List<MessageEntity>
    @Query("SELECT * FROM messages WHERE outgoing = 0 AND read = 0 AND conversationId = :conv") suspend fun unread(conv: String): List<MessageEntity>
    @Query("DELETE FROM messages WHERE sentAt < :ts") suspend fun deleteOlderThan(ts: Long): Int
    @Query("DELETE FROM messages WHERE conversationId = :conv") suspend fun deleteConversation(conv: String)
    @Query("SELECT * FROM messages WHERE conversationId = :conv ORDER BY sentAt") fun observeConversation(conv: String): Flow<List<MessageEntity>>
    @Query(
        "SELECT m.* FROM messages m JOIN (SELECT conversationId AS c, MAX(sentAt) AS t FROM messages GROUP BY conversationId) x " +
            "ON m.conversationId = x.c AND m.sentAt = x.t ORDER BY m.sentAt DESC",
    )
    fun observeLatestPerConversation(): Flow<List<MessageEntity>>
    @Query("SELECT conversationId, COUNT(*) AS n FROM messages WHERE outgoing = 0 AND read = 0 GROUP BY conversationId")
    fun observeUnread(): Flow<List<UnreadCount>>
}

@Dao
interface CallLogDao {
    @Insert suspend fun insert(c: CallLogEntity)
    @Query("SELECT * FROM calls ORDER BY startedAt DESC LIMIT 50") fun observeRecent(): Flow<List<CallLogEntity>>
    @Query("DELETE FROM calls") suspend fun clear()
}

@Dao
interface GroupNameDao {
    @Upsert suspend fun upsert(g: GroupNameEntity)
    @Query("SELECT * FROM group_names") fun observe(): Flow<List<GroupNameEntity>>
}

@Database(
    entities = [PeerEntity::class, MessageEntity::class, CallLogEntity::class, GroupNameEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class LinkDatabase : RoomDatabase() {
    abstract fun peers(): PeerDao
    abstract fun messages(): MessageDao
    abstract fun calls(): CallLogDao
    abstract fun groupNames(): GroupNameDao

    companion object {
        fun create(context: Context): LinkDatabase =
            Room.databaseBuilder(context, LinkDatabase::class.java, "paai-link.db")
                // Future schema changes must ship explicit migrations; never fall back to destructive migration
                // for user data silently.
                .build()
    }
}
