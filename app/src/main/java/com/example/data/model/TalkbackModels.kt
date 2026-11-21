package com.example.data.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

enum class ThemeMode(val labelPt: String) {
    SYSTEM("Automático (Sistema)"),
    DARK("Tema Escuro"),
    LIGHT("Tema Claro")
}

enum class AudioOutputRoute(val labelPt: String) {
    EARPIECE("Fone do aparelho"),
    SPEAKER("Viva-voz"),
    BLUETOOTH("Bluetooth / Headset")
}

enum class ScreenAudioSource(val labelPt: String) {
    NONE("Sem áudio"),
    MICROPHONE("Microfone"),
    INTERNAL_AUDIO("Áudio interno"),
    BOTH("Áudio interno + Microfone")
}

enum class PresenceStatus(val wire: String, val labelPt: String) {
    ONLINE("online", "Online"),
    IDLE("idle", "Ausente"),
    DND("dnd", "Não perturbe"),
    OFFLINE("offline", "Invisível / Offline");

    companion object {
        fun fromWire(value: String?): PresenceStatus =
            entries.firstOrNull { it.wire == value } ?: ONLINE
    }
}

enum class ServerPermission(val wire: String, val labelPt: String) {
    ADMIN_SERVER("ADMIN_SERVER", "Administrar servidor"),
    MANAGE_CHANNELS("MANAGE_CHANNELS", "Gerenciar canais"),
    MANAGE_ROLES("MANAGE_ROLES", "Gerenciar cargos"),
    SEND_MESSAGES("SEND_MESSAGES", "Enviar mensagens"),
    DELETE_MESSAGES("DELETE_MESSAGES", "Excluir mensagens"),
    CONNECT_VOICE("CONNECT_VOICE", "Conectar-se a canais de voz"),
    INVITE_MEMBERS("INVITE_MEMBERS", "Convidar participantes"),
    MANAGE_MEMBERS("MANAGE_MEMBERS", "Gerenciar membros");

    companion object {
        val ALL_WIRES: List<String> = entries.map { it.wire }
        val DEFAULT_MEMBER_WIRES: List<String> = listOf(SEND_MESSAGES.wire, CONNECT_VOICE.wire)
        val MODERATOR_WIRES: List<String> = listOf(
            SEND_MESSAGES.wire,
            DELETE_MESSAGES.wire,
            CONNECT_VOICE.wire,
            INVITE_MEMBERS.wire,
            MANAGE_MEMBERS.wire
        )
    }
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val username: String = "",
    val displayName: String = "",
    val bio: String = "",
    val statusText: String = "",
    val presence: String = "online",
    val avatarUrl: String? = null,
    val bannerUrl: String? = null,
    val bannerVideoUrl: String? = null,
    val primaryColorHex: Long = 0xFF5865F2L,
    val secondaryColorHex: Long = 0xFF4752C4L,
    val gradientEnabled: Boolean = false,
    val fcmToken: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class PrivateAccount(
    @DocumentId val id: String = "",
    val userId: String = "",
    val email: String? = null,
    val blockedUids: List<String> = emptyList(),
    val notifyMessages: Boolean = true,
    val notifyFriendRequests: Boolean = true,
    val notifyInvites: Boolean = true,
    val notifyCalls: Boolean = true,
    val notifyMentions: Boolean = true,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FriendRequest(
    @DocumentId val id: String = "",
    val fromUid: String = "",
    val fromUsername: String = "",
    val fromDisplayName: String = "",
    val fromAvatarUrl: String? = null,
    val toUid: String = "",
    val toUsername: String = "",
    val toDisplayName: String = "",
    val toAvatarUrl: String? = null,
    val status: String = "pending",
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Friendship(
    @DocumentId val id: String = "",
    val participantUids: List<String> = emptyList(),
    val userAUid: String = "",
    val userAName: String = "",
    val userAUsername: String = "",
    val userAAvatarUrl: String? = null,
    val userBUid: String = "",
    val userBName: String = "",
    val userBUsername: String = "",
    val userBAvatarUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun peerUid(myUid: String): String = if (userAUid == myUid) userBUid else userAUid
    fun peerName(myUid: String): String = if (userAUid == myUid) userBName else userAName
    fun peerUsername(myUid: String): String = if (userAUid == myUid) userBUsername else userAUsername
    fun peerAvatarUrl(myUid: String): String? = if (userAUid == myUid) userBAvatarUrl else userAAvatarUrl
}

@Keep
@IgnoreExtraProperties
data class Conversation(
    @DocumentId val id: String = "",
    val type: String = "dm",
    val name: String? = null,
    val iconUrl: String? = null,
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val lastMessageText: String = "",
    val lastMessageSenderUid: String? = null,
    @ServerTimestamp val lastMessageAt: Timestamp? = null,
    val typingUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ChatMessage(
    @DocumentId val id: String = "",
    val conversationId: String? = null,
    val serverId: String? = null,
    val channelId: String? = null,
    val senderUid: String = "",
    val senderName: String = "",
    val senderAvatarUrl: String? = null,
    val memberUids: List<String> = emptyList(),
    val text: String = "",
    val replyToMessageId: String? = null,
    val replyToPreview: String? = null,
    val attachmentUrl: String? = null,
    val attachmentName: String? = null,
    val attachmentType: String? = null,
    val linkUrl: String? = null,
    val linkTitle: String? = null,
    val linkPlatform: String? = null,
    val linkDescription: String? = null,
    val linkThumbnailUrl: String? = null,
    val reactions: Map<String, List<String>> = emptyMap(),
    val readByUids: List<String> = emptyList(),
    val edited: Boolean = false,
    val deleted: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class Server(
    @DocumentId val id: String = "",
    val name: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val bannerUrl: String? = null,
    val ownerUid: String = "",
    val memberUids: List<String> = emptyList(),
    val inviteCode: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ServerMember(
    @DocumentId val id: String = "",
    val userId: String = "",
    val serverId: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val roleId: String = "member",
    val roleName: String = "Membro",
    val permissions: List<String> = emptyList(),
    val nickname: String? = null,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun hasPermission(perm: ServerPermission): Boolean =
        permissions.contains(ServerPermission.ADMIN_SERVER.wire) || permissions.contains(perm.wire)
}

@Keep
@IgnoreExtraProperties
data class ServerRole(
    @DocumentId val id: String = "",
    val serverId: String = "",
    val name: String = "",
    val colorHex: Long = 0xFF99AAB5L,
    val permissions: List<String> = emptyList(),
    val position: Long = 0L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ServerChannel(
    @DocumentId val id: String = "",
    val serverId: String = "",
    val name: String = "",
    val topic: String? = null,
    val type: String = "text",
    val position: Long = 0L,
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ServerInvite(
    @DocumentId val id: String = "",
    val code: String = "",
    val serverId: String = "",
    val serverName: String = "",
    val serverIconUrl: String? = null,
    val serverOwnerUid: String = "",
    val creatorUid: String = "",
    val maxUses: Long = 0L,
    val uses: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class ModerationLog(
    @DocumentId val id: String = "",
    val serverId: String = "",
    val actorUid: String = "",
    val actorName: String = "",
    val action: String = "",
    val targetUid: String? = null,
    val details: String = "",
    val memberUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSession(
    @DocumentId val id: String = "",
    val contextType: String = "dm",
    val contextId: String = "",
    val serverId: String? = null,
    val callerUid: String = "",
    val callerName: String = "",
    val callType: String = "audio",
    val status: String = "ringing",
    val participantUids: List<String> = emptyList(),
    val activeUids: List<String> = emptyList(),
    val screenSharingUid: String? = null,
    val sfuRoomUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class CallSignal(
    @DocumentId val id: String = "",
    val callId: String = "",
    val fromUid: String = "",
    val toUid: String = "",
    val type: String = "offer",
    val sdp: String? = null,
    val iceCandidate: String? = null,
    val sdpMid: String? = null,
    val sdpMLineIndex: Long? = null,
    val participantUids: List<String> = emptyList(),
    @ServerTimestamp val createdAt: Timestamp? = null
)

data class AudioProcessingSettings(
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val autoGainControl: Boolean = true,
    val backgroundNoiseReduction: Boolean = true,
    val preferredOutput: AudioOutputRoute = AudioOutputRoute.SPEAKER
)

data class SfuConfiguration(
    val signalingUrl: String = "",
    val turnServerUrl: String = "",
    val turnUsername: String = "",
    val turnCredential: String = ""
) {
    val isSfuConfigured: Boolean get() = signalingUrl.isNotBlank()
}

data class LinkPreviewMetadata(
    val url: String,
    val title: String,
    val platform: String,
    val description: String,
    val thumbnailUrl: String?,
    val isDirectPlayableMedia: Boolean = false
)

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}
