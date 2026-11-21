package com.example.ui.viewmodel

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.ChatMessage
import com.example.data.model.ModerationLog
import com.example.data.model.Server
import com.example.data.model.ServerChannel
import com.example.data.model.ServerMember
import com.example.data.model.ServerRole
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.data.remote.ProfileRepository
import com.example.data.remote.ServerRepository
import com.example.data.remote.unwrapFirestoreError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class ServerViewModel(
    private val currentUserId: String,
    private val serverRepo: ServerRepository,
    private val profileRepo: ProfileRepository
) : ViewModel() {

    private val _toastEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toastEvents = _toastEvents.asSharedFlow()

    val servers: StateFlow<UiState<List<Server>>> = serverRepo.observeUserServers(currentUserId)
        .map<List<Server>, UiState<List<Server>>> { list ->
            if (_selectedServerId.value == null && list.isNotEmpty()) {
                _selectedServerId.value = list.first().id
            }
            UiState.Success(list)
        }
        .catch { e ->
            val root = unwrapFirestoreError(e)
            Log.w("ServerVM", "Error observing servers", root)
            emit(UiState.Error(root.message ?: "Erro ao carregar servidores"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _selectedServerId = MutableStateFlow<String?>(null)
    val selectedServerId = _selectedServerId.asStateFlow()

    private val _selectedChannelId = MutableStateFlow<String?>(null)
    val selectedChannelId = _selectedChannelId.asStateFlow()

    private val _channelPageSize = MutableStateFlow(50L)

    val channels: StateFlow<UiState<List<ServerChannel>>> = _selectedServerId
        .flatMapLatest { serverId ->
            if (serverId == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                serverRepo.observeChannels(serverId, currentUserId)
                    .map<List<ServerChannel>, UiState<List<ServerChannel>>> { list ->
                        if (_selectedChannelId.value == null || list.none { it.id == _selectedChannelId.value }) {
                            _selectedChannelId.value = list.firstOrNull { it.type == "text" }?.id ?: list.firstOrNull()?.id
                        }
                        UiState.Success(list)
                    }
                    .catch { e ->
                        val root = unwrapFirestoreError(e)
                        emit(UiState.Error(root.message ?: "Erro ao carregar canais"))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    val roles: StateFlow<List<ServerRole>> = _selectedServerId
        .flatMapLatest { serverId ->
            if (serverId == null) flowOf(emptyList())
            else serverRepo.observeRoles(serverId, currentUserId)
                .catch { e -> if (e is CancellationException) throw e }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val myMembership: StateFlow<ServerMember?> = _selectedServerId
        .flatMapLatest { serverId ->
            if (serverId == null) flowOf(null)
            else serverRepo.observeMyMembership(serverId, currentUserId)
                .catch { e -> if (e is CancellationException) throw e }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val moderationLogs: StateFlow<List<ModerationLog>> = _selectedServerId
        .flatMapLatest { serverId ->
            if (serverId == null) flowOf(emptyList())
            else serverRepo.observeModerationLogs(serverId, currentUserId)
                .catch { e -> if (e is CancellationException) throw e }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val channelMessages: StateFlow<UiState<List<ChatMessage>>> = combine(
        _selectedServerId,
        _selectedChannelId,
        _channelPageSize
    ) { srvId, chId, limit -> Triple(srvId, chId, limit) }
        .flatMapLatest { (srvId, chId, limit) ->
            if (srvId == null || chId == null) {
                flowOf(UiState.Success(emptyList()))
            } else {
                serverRepo.observeChannelMessages(srvId, chId, currentUserId, limit)
                    .map<List<ChatMessage>, UiState<List<ChatMessage>>> { UiState.Success(it) }
                    .catch { e ->
                        val root = unwrapFirestoreError(e)
                        emit(UiState.Error(root.message ?: "Erro ao carregar mensagens"))
                    }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), UiState.Loading)

    private val _serverMembers = MutableStateFlow<List<ServerMember>>(emptyList())
    val serverMembers = _serverMembers.asStateFlow()

    fun selectServer(server: Server) {
        _selectedServerId.value = server.id
        _selectedChannelId.value = null
        _channelPageSize.value = 50L
        refreshMembers(server)
    }

    fun selectChannel(channelId: String) {
        _selectedChannelId.value = channelId
        _channelPageSize.value = 50L
    }

    fun loadMoreChannelMessages() {
        _channelPageSize.value = (_channelPageSize.value + 50L).coerceAtMost(300L)
    }

    fun refreshMembers(server: Server) {
        viewModelScope.launch {
            _serverMembers.value = serverRepo.fetchServerMembers(server)
        }
    }

    fun createServer(name: String, description: String, creator: UserProfile) {
        viewModelScope.launch {
            serverRepo.createServer(name, description, creator)
                .onSuccess { id ->
                    _selectedServerId.value = id
                    _toastEvents.tryEmit("Servidor \"$name\" criado!")
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao criar servidor") }
        }
    }

    fun joinByInvite(code: String, user: UserProfile) {
        viewModelScope.launch {
            serverRepo.joinServerByInvite(code, user)
                .onSuccess { serverId ->
                    _selectedServerId.value = serverId
                    _toastEvents.tryEmit("Você entrou no servidor!")
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Não foi possível entrar pelo convite") }
        }
    }

    fun createInvite(server: Server, maxUses: Long = 0L, onCodeReady: (String) -> Unit) {
        viewModelScope.launch {
            serverRepo.createInvite(server, maxUses)
                .onSuccess { code ->
                    _toastEvents.tryEmit("Convite $code gerado!")
                    onCodeReady(code)
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao gerar convite") }
        }
    }

    fun leaveServer(server: Server) {
        viewModelScope.launch {
            serverRepo.leaveServer(server)
                .onSuccess {
                    if (_selectedServerId.value == server.id) _selectedServerId.value = null
                    _toastEvents.tryEmit("Você saiu do servidor ${server.name}")
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao sair do servidor") }
        }
    }

    fun updateServerInfo(serverId: String, name: String, description: String, iconUrl: String?) {
        viewModelScope.launch {
            serverRepo.updateServerInfo(serverId, name, description, iconUrl)
                .onSuccess { _toastEvents.tryEmit("Servidor atualizado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao atualizar servidor") }
        }
    }

    fun createChannel(server: Server, name: String, topic: String, type: String, position: Long) {
        viewModelScope.launch {
            serverRepo.createChannel(server, name, topic, type, position)
                .onSuccess { _toastEvents.tryEmit("Canal #$name criado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Sem permissão ou erro ao criar canal") }
        }
    }

    fun updateChannel(serverId: String, channelId: String, name: String, topic: String, position: Long) {
        viewModelScope.launch {
            serverRepo.updateChannel(serverId, channelId, name, topic, position)
                .onSuccess { _toastEvents.tryEmit("Canal atualizado") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao editar canal") }
        }
    }

    fun deleteChannel(serverId: String, channelId: String) {
        viewModelScope.launch {
            serverRepo.deleteChannel(serverId, channelId)
                .onSuccess { _toastEvents.tryEmit("Canal excluído") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao excluir canal") }
        }
    }

    fun createOrUpdateRole(
        server: Server,
        roleId: String?,
        name: String,
        colorHex: Long,
        permissions: List<String>,
        position: Long
    ) {
        viewModelScope.launch {
            serverRepo.createOrUpdateRole(server, roleId, name, colorHex, permissions, position)
                .onSuccess { _toastEvents.tryEmit("Cargo \"$name\" salvo") }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao salvar cargo") }
        }
    }

    fun assignRole(server: Server, actor: UserProfile, targetUid: String, role: ServerRole) {
        viewModelScope.launch {
            serverRepo.assignRoleToMember(server, actor, targetUid, role)
                .onSuccess {
                    refreshMembers(server)
                    _toastEvents.tryEmit("Cargo atribuído")
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao atribuir cargo") }
        }
    }

    fun kickOrBan(server: Server, actor: UserProfile, targetUid: String, ban: Boolean, reason: String) {
        viewModelScope.launch {
            serverRepo.kickOrBanMember(server, actor, targetUid, ban, reason)
                .onSuccess {
                    refreshMembers(server)
                    _toastEvents.tryEmit(if (ban) "Membro banido" else "Membro expulso")
                }
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro de moderação") }
        }
    }

    fun sendChannelMessage(
        server: Server,
        channelId: String,
        sender: UserProfile,
        text: String,
        replyTo: ChatMessage? = null
    ) {
        viewModelScope.launch {
            serverRepo.sendChannelMessage(server, channelId, sender, text, replyTo)
                .onFailure { _toastEvents.tryEmit(it.message ?: "Erro ao enviar mensagem") }
        }
    }

    fun sendChannelMediaAttachment(
        context: Context,
        server: Server,
        channelId: String,
        sender: UserProfile,
        uri: Uri,
        isImage: Boolean,
        caption: String,
        replyTo: ChatMessage?
    ) {
        viewModelScope.launch {
            val maxBytes = if (isImage) 5L * 1024 * 1024 else 10L * 1024 * 1024
            val mimes = if (isImage) listOf("image/") else listOf("*/*")
            profileRepo.uploadMedia(context, uri, "server_attachments", maxBytes, mimes)
                .onSuccess { (url, fileName) ->
                    serverRepo.sendChannelMessage(
                        server = server,
                        channelId = channelId,
                        sender = sender,
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

    fun toggleChannelReaction(serverId: String, channelId: String, message: ChatMessage, emoji: String) {
        viewModelScope.launch {
            serverRepo.toggleChannelReaction(serverId, channelId, message, emoji)
        }
    }

    fun editChannelMessage(serverId: String, channelId: String, messageId: String, newText: String) {
        viewModelScope.launch {
            serverRepo.editChannelMessage(serverId, channelId, messageId, newText)
        }
    }

    fun deleteChannelMessage(serverId: String, channelId: String, messageId: String) {
        viewModelScope.launch {
            serverRepo.deleteChannelMessage(serverId, channelId, messageId)
        }
    }
}
