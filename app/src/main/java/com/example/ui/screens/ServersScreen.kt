package com.example.ui.screens

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.model.ChatMessage
import com.example.data.model.ModerationLog
import com.example.data.model.Server
import com.example.data.model.ServerChannel
import com.example.data.model.ServerMember
import com.example.data.model.ServerPermission
import com.example.data.model.ServerRole
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.ui.components.MessageListAndComposer
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.viewmodel.CallViewModel
import com.example.ui.viewmodel.ServerViewModel
import com.example.ui.viewmodel.SocialChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServersScreen(
    currentUserId: String,
    myProfile: UserProfile?,
    serversState: UiState<List<Server>>,
    selectedServerId: String?,
    channelsState: UiState<List<ServerChannel>>,
    selectedChannelId: String?,
    channelMessagesState: UiState<List<ChatMessage>>,
    myMembership: ServerMember?,
    roles: List<ServerRole>,
    members: List<ServerMember>,
    moderationLogs: List<ModerationLog>,
    isWideScreen: Boolean,
    serverViewModel: ServerViewModel,
    socialViewModel: SocialChatViewModel,
    callViewModel: CallViewModel
) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current

    val servers = (serversState as? UiState.Success)?.data ?: emptyList()
    val channels = (channelsState as? UiState.Success)?.data ?: emptyList()
    val activeServer = servers.firstOrNull { it.id == selectedServerId }
    val activeChannel = channels.firstOrNull { it.id == selectedChannelId }

    var showChannelPaneOnly by remember(selectedServerId) { mutableStateOf(true) }
    var showCreateServerDialog by remember { mutableStateOf(false) }
    var showJoinInviteDialog by remember { mutableStateOf(false) }
    var showCreateChannelDialog by remember { mutableStateOf(false) }
    var editingChannel by remember { mutableStateOf<ServerChannel?>(null) }
    var showRolesDialog by remember { mutableStateOf(false) }
    var showMembersDialog by remember { mutableStateOf(false) }
    var showLogsDialog by remember { mutableStateOf(false) }
    var showServerEditDialog by remember { mutableStateOf(false) }
    var reportTarget by remember { mutableStateOf<ChatMessage?>(null) }

    val canManageChannels = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.MANAGE_CHANNELS) == true)
    val canManageRoles = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.MANAGE_ROLES) == true)
    val canManageMembers = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.MANAGE_MEMBERS) == true)
    val canInvite = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.INVITE_MEMBERS) == true)
    val canSendMessages = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.SEND_MESSAGES) != false)
    val canDeleteMessages = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.DELETE_MESSAGES) == true)
    val canConnectVoice = activeServer?.ownerUid == currentUserId ||
        (myMembership?.hasPermission(ServerPermission.CONNECT_VOICE) != false)

    if (!isWideScreen && !showChannelPaneOnly && activeChannel != null) {
        BackHandler { showChannelPaneOnly = true }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.chatBackground)
    ) {
        if (isWideScreen || showChannelPaneOnly || activeChannel == null) {
            // 1. Compact Discord-style Server Rail
            LazyColumn(
                modifier = Modifier
                    .width(64.dp)
                    .fillMaxHeight()
                    .background(colors.railBackground)
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(servers, key = { it.id }) { srv ->
                    val isSelected = srv.id == activeServer?.id
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(if (isSelected) 14.dp else 24.dp))
                            .background(if (isSelected) MaterialTheme.colorScheme.primary else colors.elevatedBackground)
                            .clickable {
                                serverViewModel.selectServer(srv)
                                showChannelPaneOnly = true
                            }
                            .testTag("server_rail_item_${srv.id}")
                    ) {
                        if (!srv.iconUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = srv.iconUrl,
                                contentDescription = srv.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            Text(
                                text = srv.name.take(2).uppercase(),
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                item(key = "create_server_rail") {
                    Surface(
                        shape = CircleShape,
                        color = colors.elevatedBackground,
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { showCreateServerDialog = true }
                            .testTag("create_server_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, contentDescription = "Criar servidor", tint = colors.online)
                        }
                    }
                }

                item(key = "join_invite_rail") {
                    Surface(
                        shape = CircleShape,
                        color = colors.elevatedBackground,
                        modifier = Modifier
                            .size(48.dp)
                            .clickable { showJoinInviteDialog = true }
                            .testTag("join_invite_button")
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Link, contentDescription = "Entrar por convite", tint = colors.textSecondary)
                        }
                    }
                }
            }

            // 2. Channels Sidebar
            Column(
                modifier = if (isWideScreen) {
                    Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(colors.sidebarBackground)
                } else {
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colors.sidebarBackground)
                }
            ) {
                if (activeServer == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Nenhum servidor ainda", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Crie uma comunidade ou entre com um código de convite.",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textMuted
                        )
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { showCreateServerDialog = true }) {
                            Text("Criar servidor")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = { showJoinInviteDialog = true }) {
                            Text("Entrar por convite")
                        }
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = activeServer.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = colors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            if (activeServer.ownerUid == currentUserId || myMembership?.hasPermission(ServerPermission.ADMIN_SERVER) == true) {
                                IconButton(onClick = { showServerEditDialog = true }) {
                                    Icon(Icons.Default.Settings, contentDescription = "Configurar servidor")
                                }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (canInvite) {
                                IconButton(onClick = {
                                    serverViewModel.createInvite(activeServer) { code ->
                                        val share = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, "Entre no servidor ${activeServer.name} no Talkback! Código: $code")
                                        }
                                        context.startActivity(Intent.createChooser(share, "Compartilhar convite"))
                                    }
                                }) {
                                    Icon(Icons.Default.PersonAdd, contentDescription = "Convidar")
                                }
                            }
                            if (canManageRoles) {
                                IconButton(onClick = { showRolesDialog = true }) {
                                    Icon(Icons.Default.Shield, contentDescription = "Cargos")
                                }
                            }
                            IconButton(onClick = {
                                serverViewModel.refreshMembers(activeServer)
                                showMembersDialog = true
                            }) {
                                Icon(Icons.Default.Security, contentDescription = "Membros")
                            }
                            if (canManageMembers) {
                                IconButton(onClick = { showLogsDialog = true }) {
                                    Icon(Icons.Default.Edit, contentDescription = "Logs de moderação")
                                }
                            }
                            if (activeServer.ownerUid != currentUserId) {
                                IconButton(onClick = { serverViewModel.leaveServer(activeServer) }) {
                                    Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Sair do servidor")
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = colors.subtleBorder)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "CANAIS",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textMuted,
                            modifier = Modifier.weight(1f)
                        )
                        if (canManageChannels) {
                            IconButton(
                                onClick = { showCreateChannelDialog = true },
                                modifier = Modifier.testTag("create_channel_button")
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Novo canal")
                            }
                        }
                    }

                    when (channelsState) {
                        UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is UiState.Error -> Text(channelsState.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
                        is UiState.Success -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(channels, key = { it.id }) { ch ->
                                    val isSelected = ch.id == activeChannel?.id
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(if (isSelected) colors.elevatedBackground else colors.sidebarBackground)
                                            .clickable {
                                                serverViewModel.selectChannel(ch.id)
                                                showChannelPaneOnly = false
                                            }
                                            .padding(horizontal = 10.dp, vertical = 10.dp)
                                            .testTag("channel_item_${ch.id}")
                                    ) {
                                        Icon(
                                            imageVector = if (ch.type == "voice") Icons.Default.VolumeUp else Icons.Default.Tag,
                                            contentDescription = null,
                                            tint = if (isSelected) colors.textPrimary else colors.textMuted,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            text = ch.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (isSelected) colors.textPrimary else colors.textSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (canManageChannels) {
                                            IconButton(
                                                onClick = { editingChannel = ch },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "Editar canal", modifier = Modifier.size(15.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Active Channel Area (Text Chat or Voice Channel)
        if (activeServer != null && activeChannel != null && (isWideScreen || !showChannelPaneOnly)) {
            if (isWideScreen) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(colors.subtleBorder))
            }
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TopAppBar(
                    navigationIcon = {
                        if (!isWideScreen) {
                            IconButton(onClick = { showChannelPaneOnly = true }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Canais")
                            }
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (activeChannel.type == "voice") Icons.Default.VolumeUp else Icons.Default.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Column {
                                Text(activeChannel.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                activeChannel.topic?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = colors.textSecondary, maxLines = 1)
                                }
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.sidebarBackground)
                )
                HorizontalDivider(color = colors.subtleBorder)

                if (activeChannel.type == "voice") {
                    VoiceChannelView(
                        server = activeServer,
                        channel = activeChannel,
                        canConnect = canConnectVoice,
                        myProfile = myProfile,
                        callViewModel = callViewModel
                    )
                } else {
                    when (channelMessagesState) {
                        UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        is UiState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text(channelMessagesState.message, color = MaterialTheme.colorScheme.error)
                        }
                        is UiState.Success -> {
                            MessageListAndComposer(
                                messages = channelMessagesState.data,
                                currentUserId = currentUserId,
                                typingIndicatorText = null,
                                canSendMessages = canSendMessages,
                                canModerateMessages = canDeleteMessages,
                                onLoadMore = { serverViewModel.loadMoreChannelMessages() },
                                onSendMessage = { text, reply ->
                                    myProfile?.let { me ->
                                        serverViewModel.sendChannelMessage(activeServer, activeChannel.id, me, text, reply)
                                    }
                                },
                                onAttachMedia = { uri, isImage, caption, reply ->
                                    myProfile?.let { me ->
                                        serverViewModel.sendChannelMediaAttachment(
                                            context, activeServer, activeChannel.id, me, uri, isImage, caption, reply
                                        )
                                    }
                                },
                                onTypingChanged = {},
                                onToggleReaction = { msg, emoji ->
                                    serverViewModel.toggleChannelReaction(activeServer.id, activeChannel.id, msg, emoji)
                                },
                                onEditMessage = { msg, newText ->
                                    serverViewModel.editChannelMessage(activeServer.id, activeChannel.id, msg.id, newText)
                                },
                                onDeleteMessage = { msg ->
                                    serverViewModel.deleteChannelMessage(activeServer.id, activeChannel.id, msg.id)
                                },
                                onReportMessage = { msg -> reportTarget = msg }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateServerDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateServerDialog = false },
            title = { Text("Criar Servidor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Nome do servidor") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("create_server_name_input")
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Descrição") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        myProfile?.let { serverViewModel.createServer(name, desc, it) }
                        showCreateServerDialog = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("Criar") }
            },
            dismissButton = { TextButton(onClick = { showCreateServerDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showJoinInviteDialog) {
        var code by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showJoinInviteDialog = false },
            title = { Text("Entrar por Convite") },
            text = {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Código do convite (ex: TB1A2B3C)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("join_invite_code_input")
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        myProfile?.let { serverViewModel.joinByInvite(code, it) }
                        showJoinInviteDialog = false
                    },
                    enabled = code.isNotBlank()
                ) { Text("Entrar") }
            },
            dismissButton = { TextButton(onClick = { showJoinInviteDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showCreateChannelDialog && activeServer != null) {
        ChannelEditorDialog(
            title = "Criar Canal",
            initialName = "",
            initialTopic = "",
            initialType = "text",
            initialPosition = channels.size.toLong(),
            allowTypeSelection = true,
            onDismiss = { showCreateChannelDialog = false },
            onSave = { n, t, ty, pos ->
                serverViewModel.createChannel(activeServer, n, t, ty, pos)
                showCreateChannelDialog = false
            },
            onDelete = null
        )
    }

    editingChannel?.let { ch ->
        if (activeServer != null) {
            ChannelEditorDialog(
                title = "Editar Canal",
                initialName = ch.name,
                initialTopic = ch.topic ?: "",
                initialType = ch.type,
                initialPosition = ch.position,
                allowTypeSelection = false,
                onDismiss = { editingChannel = null },
                onSave = { n, t, _, pos ->
                    serverViewModel.updateChannel(activeServer.id, ch.id, n, t, pos)
                    editingChannel = null
                },
                onDelete = {
                    serverViewModel.deleteChannel(activeServer.id, ch.id)
                    editingChannel = null
                }
            )
        }
    }

    if (showServerEditDialog && activeServer != null) {
        var sName by remember { mutableStateOf(activeServer.name) }
        var sDesc by remember { mutableStateOf(activeServer.description) }
        AlertDialog(
            onDismissRequest = { showServerEditDialog = false },
            title = { Text("Configurações do Servidor") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = sName, onValueChange = { sName = it }, label = { Text("Nome") }, singleLine = true)
                    OutlinedTextField(value = sDesc, onValueChange = { sDesc = it }, label = { Text("Descrição") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    serverViewModel.updateServerInfo(activeServer.id, sName, sDesc, activeServer.iconUrl)
                    showServerEditDialog = false
                }) { Text("Salvar") }
            },
            dismissButton = { TextButton(onClick = { showServerEditDialog = false }) { Text("Cancelar") } }
        )
    }

    if (showRolesDialog && activeServer != null) {
        RolesManagerDialog(
            server = activeServer,
            roles = roles,
            onDismiss = { showRolesDialog = false },
            onSaveRole = { roleId, name, color, perms, pos ->
                serverViewModel.createOrUpdateRole(activeServer, roleId, name, color, perms, pos)
            }
        )
    }

    if (showMembersDialog && activeServer != null) {
        MembersManagerDialog(
            server = activeServer,
            currentUserId = currentUserId,
            myProfile = myProfile,
            members = members,
            roles = roles,
            canManageMembers = canManageMembers,
            canManageRoles = canManageRoles,
            onDismiss = { showMembersDialog = false },
            onAssignRole = { targetUid, role ->
                myProfile?.let { serverViewModel.assignRole(activeServer, it, targetUid, role) }
            },
            onKickOrBan = { targetUid, ban, reason ->
                myProfile?.let { serverViewModel.kickOrBan(activeServer, it, targetUid, ban, reason) }
            }
        )
    }

    if (showLogsDialog) {
        ModerationLogsDialog(logs = moderationLogs, onDismiss = { showLogsDialog = false })
    }

    reportTarget?.let { target ->
        var reason by remember { mutableStateOf("Conteúdo impróprio") }
        AlertDialog(
            onDismissRequest = { reportTarget = null },
            title = { Text("Denunciar mensagem") },
            text = {
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it },
                    label = { Text("Motivo") },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    socialViewModel.reportContent(target.senderUid, target.id, activeServer?.id, reason, target.text)
                    reportTarget = null
                }) { Text("Enviar") }
            },
            dismissButton = { TextButton(onClick = { reportTarget = null }) { Text("Cancelar") } }
        )
    }
}

@Composable
private fun VoiceChannelView(
    server: Server,
    channel: ServerChannel,
    canConnect: Boolean,
    myProfile: UserProfile?,
    callViewModel: CallViewModel
) {
    val colors = LocalTalkbackColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Headset,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Text(channel.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    text = "Canal de voz do servidor ${server.name}. Conecte-se por áudio, vídeo ou compartilhe a tela do Android via WebRTC/SFU.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            myProfile?.let { me ->
                                callViewModel.startCall(
                                    caller = me,
                                    contextType = "voice_channel",
                                    contextId = channel.id,
                                    serverId = server.id,
                                    callType = "audio",
                                    participantUids = server.memberUids
                                )
                            }
                        },
                        enabled = canConnect
                    ) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Conectar Voz")
                    }
                    OutlinedButton(
                        onClick = {
                            myProfile?.let { me ->
                                callViewModel.startCall(
                                    caller = me,
                                    contextType = "voice_channel",
                                    contextId = channel.id,
                                    serverId = server.id,
                                    callType = "video",
                                    participantUids = server.memberUids
                                )
                            }
                        },
                        enabled = canConnect
                    ) {
                        Icon(Icons.Default.Videocam, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Vídeo / Tela")
                    }
                }
            }
        }
    }
}
