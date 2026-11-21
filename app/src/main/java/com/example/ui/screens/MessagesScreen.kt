package com.example.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.ChatMessage
import com.example.data.model.Conversation
import com.example.data.model.Friendship
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.ui.components.MessageListAndComposer
import com.example.ui.components.UserAvatar
import com.example.ui.components.rememberCallPermissionLauncher
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.viewmodel.CallViewModel
import com.example.ui.viewmodel.SocialChatViewModel
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessagesScreen(
    currentUserId: String,
    myProfile: UserProfile?,
    conversationsState: UiState<List<Conversation>>,
    friendshipsState: UiState<List<Friendship>>,
    selectedConversationId: String?,
    messagesState: UiState<List<ChatMessage>>,
    peerProfiles: Map<String, UserProfile>,
    isWideScreen: Boolean,
    socialViewModel: SocialChatViewModel,
    callViewModel: CallViewModel
) {
    val colors = LocalTalkbackColors.current
    val launchWithCallPermissions = rememberCallPermissionLauncher()
    var searchQuery by remember { mutableStateOf("") }
    var showCreateGroupDialog by remember { mutableStateOf(false) }
    var showEditGroupDialog by remember { mutableStateOf<Conversation?>(null) }
    var reportTarget by remember { mutableStateOf<ChatMessage?>(null) }

    val conversations = (conversationsState as? UiState.Success)?.data ?: emptyList()
    val friendships = (friendshipsState as? UiState.Success)?.data ?: emptyList()
    val activeConv = conversations.firstOrNull { it.id == selectedConversationId }

    LaunchedEffect(conversations) {
        conversations.flatMap { it.memberUids }
            .filter { it != currentUserId }
            .distinct()
            .forEach { socialViewModel.ensurePeerProfileLoaded(it) }
    }

    if (!isWideScreen && activeConv != null) {
        BackHandler { socialViewModel.selectConversation(null) }
    }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.chatBackground)
    ) {
        if (isWideScreen || activeConv == null) {
            Column(
                modifier = if (isWideScreen) {
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .background(colors.sidebarBackground)
                } else {
                    Modifier
                        .fillMaxSize()
                        .background(colors.sidebarBackground)
                }
            ) {
                TopAppBar(
                    title = { Text("Mensagens", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(
                            onClick = { showCreateGroupDialog = true },
                            modifier = Modifier.testTag("new_group_button")
                        ) {
                            Icon(Icons.Default.GroupAdd, contentDescription = "Criar grupo rápido")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = colors.sidebarBackground
                    )
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Buscar conversas...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )

                when (conversationsState) {
                    UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is UiState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(conversationsState.message, color = MaterialTheme.colorScheme.error)
                    }
                    is UiState.Success -> {
                        val filtered = conversations.filter { conv ->
                            val title = resolveConversationTitle(conv, currentUserId, peerProfiles)
                            searchQuery.isBlank() || title.contains(searchQuery, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text("Sem conversas ainda", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Adicione amigos na aba Amigos ou crie um grupo rápido para começar.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                items(filtered, key = { it.id }) { conv ->
                                    val title = resolveConversationTitle(conv, currentUserId, peerProfiles)
                                    val peerUid = conv.memberUids.firstOrNull { it != currentUserId }
                                    val peer = peerUid?.let { peerProfiles[it] }
                                    val isSelected = conv.id == selectedConversationId
                                    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
                                    val timeStr = conv.lastMessageAt?.toDate()?.let { timeFmt.format(it) } ?: ""

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isSelected) colors.elevatedBackground else colors.sidebarBackground)
                                            .clickable { socialViewModel.selectConversation(conv.id) }
                                            .padding(10.dp)
                                            .testTag("conversation_item_${conv.id}")
                                    ) {
                                        if (conv.type == "group") {
                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                modifier = Modifier.size(44.dp)
                                            ) {
                                                Box(contentAlignment = Alignment.Center) {
                                                    Icon(Icons.Default.Groups, contentDescription = null)
                                                }
                                            }
                                        } else {
                                            UserAvatar(
                                                displayName = title,
                                                avatarUrl = peer?.avatarUrl,
                                                presence = peer?.presence ?: "offline",
                                                size = 44.dp
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = title,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    color = colors.textPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f)
                                                )
                                                Text(
                                                    text = timeStr,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.textMuted
                                                )
                                            }
                                            Text(
                                                text = conv.lastMessageText.ifBlank { "Nova conversa" },
                                                style = MaterialTheme.typography.bodySmall,
                                                color = colors.textSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (activeConv != null) {
            if (isWideScreen) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colors.subtleBorder)
                )
            }
            val title = resolveConversationTitle(activeConv, currentUserId, peerProfiles)
            val otherTyping = activeConv.typingUids.filter { it != currentUserId }
            val typingLabel = when {
                otherTyping.isEmpty() -> null
                otherTyping.size == 1 -> {
                    val name = peerProfiles[otherTyping.first()]?.displayName ?: "Alguém"
                    "$name está digitando..."
                }
                else -> "Várias pessoas estão digitando..."
            }

            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                TopAppBar(
                    navigationIcon = {
                        if (!isWideScreen) {
                            IconButton(onClick = { socialViewModel.selectConversation(null) }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                            }
                        }
                    },
                    title = {
                        Column {
                            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (activeConv.type == "group") {
                                Text(
                                    "${activeConv.memberUids.size} participantes",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                myProfile?.let { me ->
                                    launchWithCallPermissions(false) {
                                        callViewModel.startCall(
                                            caller = me,
                                            contextType = activeConv.type,
                                            contextId = activeConv.id,
                                            callType = "audio",
                                            participantUids = activeConv.memberUids
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.testTag("start_audio_call_button")
                        ) {
                            Icon(Icons.Default.Call, contentDescription = "Chamada de voz")
                        }
                        IconButton(
                            onClick = {
                                myProfile?.let { me ->
                                    launchWithCallPermissions(true) {
                                        callViewModel.startCall(
                                            caller = me,
                                            contextType = activeConv.type,
                                            contextId = activeConv.id,
                                            callType = "video",
                                            participantUids = activeConv.memberUids
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.testTag("start_video_call_button")
                        ) {
                            Icon(Icons.Default.Videocam, contentDescription = "Chamada de vídeo")
                        }
                        if (activeConv.type == "group") {
                            IconButton(onClick = { showEditGroupDialog = activeConv }) {
                                Icon(Icons.Default.Settings, contentDescription = "Gerenciar grupo")
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.sidebarBackground)
                )
                HorizontalDivider(color = colors.subtleBorder)

                when (messagesState) {
                    UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is UiState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(messagesState.message, color = MaterialTheme.colorScheme.error)
                    }
                    is UiState.Success -> {
                        val context = androidx.compose.ui.platform.LocalContext.current
                        MessageListAndComposer(
                            messages = messagesState.data,
                            currentUserId = currentUserId,
                            typingIndicatorText = typingLabel,
                            canModerateMessages = true,
                            onLoadMore = { socialViewModel.loadMoreMessages() },
                            onSendMessage = { text, reply ->
                                socialViewModel.sendMessage(activeConv, text, reply)
                            },
                            onAttachMedia = { uri, isImage, caption, reply ->
                                socialViewModel.sendMediaAttachment(context, activeConv, uri, isImage, caption, reply)
                            },
                            onTypingChanged = { typing ->
                                socialViewModel.onTypingChanged(activeConv.id, typing)
                            },
                            onToggleReaction = { msg, emoji ->
                                socialViewModel.toggleReaction(activeConv.id, msg, emoji)
                            },
                            onEditMessage = { msg, newText ->
                                socialViewModel.editMessage(activeConv.id, msg.id, newText)
                            },
                            onDeleteMessage = { msg ->
                                socialViewModel.deleteMessage(activeConv.id, msg)
                            },
                            onReportMessage = { msg -> reportTarget = msg }
                        )
                    }
                }
            }
        }
    }

    if (showCreateGroupDialog) {
        GroupEditorDialog(
            title = "Criar Grupo Rápido",
            initialName = "",
            initialMembers = emptyList(),
            friendships = friendships,
            currentUserId = currentUserId,
            onDismiss = { showCreateGroupDialog = false },
            onConfirm = { name, selectedUids ->
                socialViewModel.createQuickGroup(name, selectedUids) {
                    showCreateGroupDialog = false
                }
            }
        )
    }

    showEditGroupDialog?.let { group ->
        GroupEditorDialog(
            title = "Gerenciar Grupo",
            initialName = group.name ?: "",
            initialMembers = group.memberUids,
            friendships = friendships,
            currentUserId = currentUserId,
            onDismiss = { showEditGroupDialog = null },
            onConfirm = { name, selectedUids ->
                socialViewModel.updateGroup(group.id, name, selectedUids + currentUserId)
                showEditGroupDialog = null
            }
        )
    }

    reportTarget?.let { target ->
        var reason by remember { mutableStateOf("Spam ou abuso") }
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
                    socialViewModel.reportContent(target.senderUid, target.id, null, reason, target.text)
                    reportTarget = null
                }) { Text("Enviar denúncia") }
            },
            dismissButton = {
                TextButton(onClick = { reportTarget = null }) { Text("Cancelar") }
            }
        )
    }
}

private fun resolveConversationTitle(
    conv: Conversation,
    myUid: String,
    peerProfiles: Map<String, UserProfile>
): String {
    if (conv.type == "group") return conv.name?.ifBlank { "Grupo sem nome" } ?: "Grupo"
    val peerUid = conv.memberUids.firstOrNull { it != myUid }
    return peerUid?.let { peerProfiles[it]?.displayName } ?: "Conversa Privada"
}

@Composable
private fun GroupEditorDialog(
    title: String,
    initialName: String,
    initialMembers: List<String>,
    friendships: List<Friendship>,
    currentUserId: String,
    onDismiss: () -> Unit,
    onConfirm: (String, List<String>) -> Unit
) {
    var groupName by remember { mutableStateOf(initialName) }
    val selected = remember { mutableStateListOf<String>().apply { addAll(initialMembers) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = groupName,
                    onValueChange = { groupName = it },
                    label = { Text("Nome do grupo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Participantes (amigos)", style = MaterialTheme.typography.labelMedium)
                if (friendships.isEmpty()) {
                    Text("Adicione amigos primeiro para incluir participantes.", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(modifier = Modifier.height(180.dp)) {
                        items(friendships, key = { it.id }) { f ->
                            val peerUid = f.peerUid(currentUserId)
                            val checked = selected.contains(peerUid)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        if (checked) selected.remove(peerUid) else selected.add(peerUid)
                                    }
                                    .padding(vertical = 4.dp)
                            ) {
                                Checkbox(checked = checked, onCheckedChange = {
                                    if (it) selected.add(peerUid) else selected.remove(peerUid)
                                })
                                Text("${f.peerName(currentUserId)} (@${f.peerUsername(currentUserId)})")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(groupName, selected.toList()) },
                enabled = groupName.isNotBlank()
            ) { Text("Salvar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
