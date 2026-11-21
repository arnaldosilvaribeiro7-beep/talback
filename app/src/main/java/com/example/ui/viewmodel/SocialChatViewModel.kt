package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.FriendRequest
import com.example.data.model.Friendship
import com.example.data.model.PresenceStatus
import com.example.data.model.PrivateAccount
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.remote.ChatRepository
import com.example.data.remote.ProfileRepository
import com.example.data.remote.SocialRepository
import com.example.data.remote.unwrapFirestoreError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class SocialChatViewModel(
    private val currentUserId: String,
    private val profileRepo: ProfileRepository,
    private val socialRepo: SocialRepository,
    private val chatRepo: ChatRepository
) : ViewModel() {

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents = _toastEvents.asSharedFlow()

    val myProfile: StateFlow<UiState<UserProfile?>> = profileRepo.observeProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            Log.w("SocialChatVM", "Error observing profile", root)
            emit(UiState.Error(root.message ?: "Erro ao carregar perfil"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val privateAccount: StateFlow<PrivateAccount?> = profileRepo.observePrivateAccount(currentUserId)
        .catch { e ->
            if (e is CancellationException) throw e
            Log.w("SocialChatVM", "Error observing private account", e)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val friendships: StateFlow<UiState<List<Friendship>>> = socialRepo.observeFriendships(currentUserId)
        .map<List<Friendship>, UiState<List<Friendship>>> { UiState.Success(it) }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            emit(UiState.Error(root.message ?: "Erro ao carregar amigos"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val friendRequests: StateFlow<UiState<List<FriendRequest>>> = socialRepo.observeFriendRequests(currentUserId)
        .map<List<FriendRequest>, UiState<List<FriendRequest>>> { UiState.Success(it) }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            emit(UiState.Error(root.message ?: "Erro ao carregar solicitações"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val conversations: StateFlow<UiState<List<Conversation>>> = chatRepo.observeConversations(currentUserId)
        .map<List<Conversation>, UiState<List<Conversation>>> { UiState.Success(it) }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            emit(UiState.Error(root.message ?: "Erro ao carregar conversas"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _selectedConversationId = MutableStateFlow<String?>(null)
    val selectedConversationId = _selectedConversationId.asStateFlow()

    private val _messagePageSize = MutableStateFlow(50L)

    val activeMessages: StateFlow<UiState<List<ChatMessage>>> = _selectedConversationId
        .flatMapLatest { convId ->
            if (convId == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                _messagePageSize.flatMapLatest { limit ->
                    chatRepo.observeMessages(convId, currentUserId, limit)
                        .map<List<ChatMessage>, UiState<List<ChatMessage>>> { list ->
                            viewModelScope.launch { chatRepo.markConversationRead(convId, list) }
                            UiState.Success(list)
                        }
                        .catch { e ->
                            val root = unwrapFirestoreError(e)
                            emit(UiState.Error(root.message ?: "Erro ao carregar mensagens"))
                        }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _peerProfiles = MutableStateFlow<Map<String, UserProfile>>(emptyMap())
    val peerProfiles: StateFlow<Map<String, UserProfile>> = _peerProfiles.asStateFlow()

    private var typingJob: Job? = null

    fun selectConversation(conversationId: String?) {
        _selectedConversationId.value = conversationId
        _messagePageSize.value = 50L
    }

    fun loadMoreMessages() {
        _messagePageSize.value = (_messagePageSize.value + 50L).coerceAtMost(300L)
    }

    fun ensurePeerProfileLoaded(uid: String) {
        if (uid.isBlank() || _peerProfiles.value.containsKey(uid)) return
        viewModelScope.launch {
            profileRepo.getUserProfile(uid)?.let { profile ->
                _peerProfiles.value = _peerProfiles.value + (uid to profile)
            }
        }
    }

    fun openDirectChat(peerUid: String, onOpened: (String) -> Unit) {
        val profile = (myProfile.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepo.getOrCreateDirectConversation(profile, peerUid)
                .onSuccess { convId ->
                    _selectedConversationId.value = convId
                    onOpened(convId)
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Falha ao abrir conversa") }
        }
    }

    fun sendFriendRequest(username: String) {
        val profile = (myProfile.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            socialRepo.sendFriendRequestByUsername(username, profile)
                .onSuccess { name -> _toastEvents.tryEmit("Solicitação enviada para $name") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Não foi possível enviar solicitação") }
        }
    }

    fun acceptFriendRequest(request: FriendRequest) {
        viewModelScope.launch {
            socialRepo.acceptFriendRequest(request)
                .onSuccess { _toastEvents.tryEmit("${request.fromDisplayName} agora é seu amigo") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao aceitar solicitação") }
        }
    }

    fun declineFriendRequest(requestId: String) {
        viewModelScope.launch {
            socialRepo.declineOrCancelRequest(requestId)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao recusar solicitação") }
        }
    }

    fun removeFriend(friendshipId: String) {
        viewModelScope.launch {
            socialRepo.removeFriend(friendshipId)
                .onSuccess { _toastEvents.tryEmit("Amizade removida") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao remover amigo") }
        }
    }

    fun blockUser(targetUid: String) {
        viewModelScope.launch {
            socialRepo.blockUser(targetUid)
                .onSuccess { _toastEvents.tryEmit("Usuário bloqueado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao bloquear") }
        }
    }

    fun unblockUser(targetUid: String) {
        viewModelScope.launch {
            socialRepo.unblockUser(targetUid)
                .onSuccess { _toastEvents.tryEmit("Usuário desbloqueado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao desbloquear") }
        }
    }

    fun createQuickGroup(name: String, memberUids: List<String>, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            chatRepo.createQuickGroup(name, memberUids)
                .onSuccess { convId ->
                    _selectedConversationId.value = convId
                    _toastEvents.tryEmit("Grupo \"$name\" criado")
                    onCreated(convId)
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao criar grupo") }
        }
    }

    fun updateGroup(conversationId: String, name: String, memberUids: List<String>) {
        viewModelScope.launch {
            chatRepo.updateGroupMembersAndInfo(conversationId, name, memberUids)
                .onSuccess { _toastEvents.tryEmit("Grupo atualizado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao atualizar grupo") }
        }
    }

    fun sendMessage(
        conversation: Conversation,
        text: String,
        replyTo: ChatMessage? = null,
        attachmentUrl: String? = null,
        attachmentName: String? = null,
        attachmentType: String? = null
    ) {
        val profile = (myProfile.value as? UiState.Success)?.data ?: return
        viewModelScope.launch {
            chatRepo.setTyping(conversation.id, false)
            chatRepo.sendMessage(
                conversation = conversation,
                sender = profile,
                text = text,
                replyTo = replyTo,
                attachmentUrl = attachmentUrl,
                attachmentName = attachmentName,
                attachmentType = attachmentType
            ).onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao enviar mensagem") }
        }
    }

    fun sendMediaAttachment(
        context: Context,
        conversation: Conversation,
        uri: Uri,
        isImage: Boolean,
        caption: String,
        replyTo: ChatMessage?
    ) {
        viewModelScope.launch {
            val maxBytes = if (isImage) 5L * 1024 * 1024 else 10L * 1024 * 1024
            val mimes = if (isImage) listOf("image/") else listOf("*/*")
            profileRepo.uploadMedia(context, uri, "chat_attachments", maxBytes, mimes)
                .onSuccess { (url, fileName) ->
                    sendMessage(
                        conversation = conversation,
                        text = caption,
                        replyTo = replyTo,
                        attachmentUrl = url,
                        attachmentName = fileName,
                        attachmentType = if (isImage) "image" else "file"
                    )
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao enviar anexo") }
        }
    }

    fun onTypingChanged(conversationId: String, isTyping: Boolean) {
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            chatRepo.setTyping(conversationId, isTyping)
            if (isTyping) {
                delay(3500L)
                chatRepo.setTyping(conversationId, false)
            }
        }
    }

    fun toggleReaction(conversationId: String, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            chatRepo.toggleReaction(conversationId, message, emoji)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro na reação") }
        }
    }

    fun editMessage(conversationId: String, messageId: String, newText: String) {
        viewModelScope.launch {
            chatRepo.editMessage(conversationId, messageId, newText)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao editar") }
        }
    }

    fun deleteMessage(conversationId: String, messageId: String) {
        viewModelScope.launch {
            chatRepo.deleteMessage(conversationId, messageId)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao excluir") }
        }
    }

    fun reportContent(targetUid: String, messageId: String?, serverId: String?, reason: String, details: String) {
        viewModelScope.launch {
            chatRepo.reportTarget(targetUid, messageId, serverId, reason, details)
                .onSuccess { _toastEvents.tryEmit("Denúncia registrada") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao enviar denúncia") }
        }
    }

    fun updateProfile(
        displayName: String,
        username: String,
        bio: String,
        statusText: String,
        presence: PresenceStatus,
        primaryColorHex: Long,
        secondaryColorHex: Long,
        gradientEnabled: Boolean,
        avatarUrl: String?,
        bannerUrl: String?,
        bannerVideoUrl: String?
    ) {
        viewModelScope.launch {
            profileRepo.updateProfile(
                displayName = displayName,
                newUsername = username,
                bio = bio,
                statusText = statusText,
                presence = presence,
                primaryColorHex = primaryColorHex,
                secondaryColorHex = secondaryColorHex,
                gradientEnabled = gradientEnabled,
                avatarUrl = avatarUrl,
                bannerUrl = bannerUrl,
                bannerVideoUrl = bannerVideoUrl
            ).onSuccess {
                _toastEvents.tryEmit("Perfil atualizado com sucesso")
            }.onFailure {
                _toastEvents.tryEmit(it.message ?: "Erro ao salvar perfil")
            }
        }
    }

    fun uploadProfileAsset(
        context: Context,
        uri: Uri,
        assetKind: String,
        onUploaded: (String) -> Unit
    ) {
        viewModelScope.launch {
            val (folder, maxMb, mimes) = when (assetKind) {
                "avatar" -> Triple("avatars", 5L, listOf("image/"))
                "banner" -> Triple("banners", 6L, listOf("image/"))
                else -> Triple("banner_videos", 15L, listOf("video/"))
            }
            profileRepo.uploadMedia(context, uri, folder, maxMb * 1024 * 1024, mimes)
                .onSuccess { (url, _) ->
                    _toastEvents.tryEmit("Mídia carregada! Salve o perfil para aplicar.")
                    onUploaded(url)
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Falha no upload") }
        }
    }

    fun updateNotificationPrefs(
        notifyMessages: Boolean,
        notifyFriendRequests: Boolean,
        notifyInvites: Boolean,
        notifyCalls: Boolean,
        notifyMentions: Boolean
    ) {
        viewModelScope.launch {
            profileRepo.updateNotificationPrefs(
                notifyMessages, notifyFriendRequests, notifyInvites, notifyCalls, notifyMentions
            ).onSuccess { _toastEvents.tryEmit("Preferências de notificação salvas") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao salvar preferências") }
        }
    }
}
