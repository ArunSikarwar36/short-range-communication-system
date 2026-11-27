package ai.paai.link.data

import ai.paai.link.core.messaging.MessageStatus
import ai.paai.link.core.messaging.MessageStore
import ai.paai.link.core.messaging.StoredMessage
import ai.paai.link.core.node.TrustStore
import ai.paai.link.core.node.TrustedPeer
import ai.paai.link.core.util.DeviceId
import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

class RoomTrustStore(private val dao: PeerDao) : TrustStore {
    override suspend fun loadAll(): List<TrustedPeer> =
        dao.all().map { TrustedPeer(DeviceId.fromHex(it.id), it.staticKey, it.displayName, it.pairedAt) }

    override suspend fun save(peer: TrustedPeer) {
        val existing = dao.all().firstOrNull { it.id == peer.id.hex }
        dao.upsert(PeerEntity(peer.id.hex, peer.staticKey, peer.name, existing?.localName, peer.pairedAt))
    }

    override suspend fun remove(id: DeviceId) = dao.delete(id.hex)
}

class RoomMessageStore(private val dao: MessageDao) : MessageStore {
    override suspend fun insert(m: StoredMessage): Boolean = dao.insert(m.toEntity()) != -1L
    override suspend fun update(m: StoredMessage) = dao.update(m.toEntity())
    override suspend fun get(id: DeviceId): StoredMessage? = dao.get(id.hex)?.toStored()
    override suspend fun pendingOutgoing(): List<StoredMessage> = dao.pending().map { it.toStored() }
    override suspend fun unreadIncoming(conversationId: DeviceId): List<StoredMessage> = dao.unread(conversationId.hex).map { it.toStored() }
    override suspend fun deleteOlderThan(timestamp: Long): Int = dao.deleteOlderThan(timestamp)
}

fun StoredMessage.toEntity() = MessageEntity(
    messageId.hex, conversationId.hex, isGroup, author.hex, authorName, recipient.hex, outgoing, body, sentAt, receivedAt,
    status.name, attempts, nextAttemptAt, read,
)

fun MessageEntity.toStored() = StoredMessage(
    DeviceId.fromHex(messageId), DeviceId.fromHex(conversationId), isGroup, DeviceId.fromHex(author), authorName,
    DeviceId.fromHex(recipient), outgoing, body, sentAt, receivedAt, MessageStatus.valueOf(status), attempts, nextAttemptAt, read,
)

data class Settings(
    val displayName: String = "",
    val onboarded: Boolean = false,
    val relayEnabled: Boolean = true,
    val maxGroupMembers: Int = 6,
    val retentionDays: Int = 30,
    val lowBatteryRelayCutoff: Int = 20,
    /** Go online again automatically when the app is opened (the user left it online). */
    val wasOnline: Boolean = false,
    /**
     * Use Wi-Fi Aware next to Wi-Fi Direct. Off by default: many phones cannot run both at once,
     * and Wi-Fi Aware then blocks Wi-Fi Direct completely (seen on a Xiaomi with Android 16).
     */
    val useWifiAware: Boolean = false,
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object K {
        val name = stringPreferencesKey("display_name")
        val onboarded = booleanPreferencesKey("onboarded")
        val relay = booleanPreferencesKey("relay_enabled")
        val maxGroup = intPreferencesKey("max_group_members")
        val retention = intPreferencesKey("retention_days")
        val batteryCutoff = intPreferencesKey("low_battery_relay_cutoff")
        val wasOnline = booleanPreferencesKey("was_online")
        val useWifiAware = booleanPreferencesKey("use_wifi_aware")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            displayName = p[K.name] ?: "",
            onboarded = p[K.onboarded] ?: false,
            relayEnabled = p[K.relay] ?: true,
            maxGroupMembers = (p[K.maxGroup] ?: 6).coerceIn(2, 8),
            retentionDays = (p[K.retention] ?: 30).coerceIn(0, 3650),
            lowBatteryRelayCutoff = (p[K.batteryCutoff] ?: 20).coerceIn(0, 100),
            wasOnline = p[K.wasOnline] ?: false,
            useWifiAware = p[K.useWifiAware] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { p ->
            val s = transform(
                Settings(
                    p[K.name] ?: "", p[K.onboarded] ?: false, p[K.relay] ?: true, p[K.maxGroup] ?: 6,
                    p[K.retention] ?: 30, p[K.batteryCutoff] ?: 20, p[K.wasOnline] ?: false, p[K.useWifiAware] ?: false,
                ),
            )
            p[K.name] = s.displayName.trim().take(40)
            p[K.onboarded] = s.onboarded
            p[K.relay] = s.relayEnabled
            p[K.maxGroup] = s.maxGroupMembers.coerceIn(2, 8)
            p[K.retention] = s.retentionDays.coerceIn(0, 3650)
            p[K.batteryCutoff] = s.lowBatteryRelayCutoff.coerceIn(0, 100)
            p[K.wasOnline] = s.wasOnline
            p[K.useWifiAware] = s.useWifiAware
        }
    }
}
