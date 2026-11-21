package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PersonRemove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.FriendRequest
import com.example.data.model.Friendship
import com.example.data.model.PrivateAccount
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.ui.components.UserAvatar
import com.example.ui.components.rememberCallPermissionLauncher
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.viewmodel.CallViewModel
import com.example.ui.viewmodel.SocialChatViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FriendsScreen(
    currentUserId: String,
    myProfile: UserProfile?,
    privateAccount: PrivateAccount?,
    friendshipsState: UiState<List<Friendship>>,
    requestsState: UiState<List<FriendRequest>>,
    peerProfiles: Map<String, UserProfile>,
    favoriteFriendUids: Set<String> = emptySet(),
    socialViewModel: SocialChatViewModel,
    callViewModel: CallViewModel,
    onOpenConversation: (String) -> Unit
) {
    val colors = LocalTalkbackColors.current
    val launchWithCallPermissions = rememberCallPermissionLauncher()
    var selectedTab by remember { mutableIntStateOf(0) }
    var usernameInput by remember { mutableStateOf("") }
    var localSearchQuery by remember { mutableStateOf("") }

    val friendships = (friendshipsState as? UiState.Success)?.data ?: emptyList()
    val requests = (requestsState as? UiState.Success)?.data ?: emptyList()
    val blockedUids = privateAccount?.blockedUids ?: emptyList()

    LaunchedEffect(friendships, blockedUids) {
        (friendships.map { it.peerUid(currentUserId) } + blockedUids)
            .distinct()
            .forEach { socialViewModel.ensurePeerProfileLoaded(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.chatBackground)
    ) {
        TopAppBar(
            title = { Text("Amigos", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.sidebarBackground)
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.sidebarBackground)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = usernameInput,
                onValueChange = { usernameInput = it },
                placeholder = { Text("Adicionar por @usuário") },
                singleLine = true,
                modifier = Modifier
                    .weight(1f)
                    .testTag("add_friend_username_input")
            )
            Button(
                onClick = {
                    if (usernameInput.isNotBlank()) {
                        socialViewModel.sendFriendRequest(usernameInput)
                        usernameInput = ""
                    }
                },
                enabled = usernameInput.isNotBlank(),
                modifier = Modifier.testTag("send_friend_request_button")
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("Adicionar")
            }
        }

        val tabs = listOf("Online", "Todos (${friendships.size})", "Pendentes (${requests.size})", "Bloqueados")
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = colors.sidebarBackground
        ) {
            tabs.forEachIndexed { idx, label ->
                Tab(
                    selected = selectedTab == idx,
                    onClick = { selectedTab = idx },
                    text = { Text(label, maxLines = 1) }
                )
            }
        }

        when (selectedTab) {
            0, 1 -> {
                if (friendships.isNotEmpty()) {
                    OutlinedTextField(
                        value = localSearchQuery,
                        onValueChange = { localSearchQuery = it },
                        placeholder = { Text("Busca rápida nos amigos salvos localmente") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .testTag("friends_local_search_input")
                    )
                }

                when (friendshipsState) {
                    UiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    is UiState.Error -> Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                        Text(friendshipsState.message, color = MaterialTheme.colorScheme.error)
                    }
                    is UiState.Success -> {
                        val query = localSearchQuery.trim().lowercase().removePrefix("@")
                        val visible = friendships.filter { f ->
                            val peerUid = f.peerUid(currentUserId)
                            if (blockedUids.contains(peerUid)) return@filter false
                            if (selectedTab == 0) {
                                val presence = peerProfiles[peerUid]?.presence ?: "offline"
                                if (presence != "online" && presence != "idle" && presence != "dnd") return@filter false
                            }
                            if (query.isNotBlank()) {
                                val peer = peerProfiles[peerUid]
                                val name = (peer?.displayName ?: f.peerName(currentUserId)).lowercase()
                                val handle = (peer?.username ?: f.peerUsername(currentUserId)).lowercase()
                                name.contains(query) || handle.contains(query)
                            } else {
                                true
                            }
                        }.sortedWith(
                            compareByDescending<Friendship> { favoriteFriendUids.contains(it.peerUid(currentUserId)) }
                                .thenBy {
                                    val peerUid = it.peerUid(currentUserId)
                                    (peerProfiles[peerUid]?.displayName ?: it.peerName(currentUserId)).lowercase()
                                }
                        )
                        if (visible.isEmpty()) {
                            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        query.isNotBlank() -> "Nenhum amigo encontrado para \"$localSearchQuery\"."
                                        selectedTab == 0 -> "Nenhum amigo online no momento."
                                        else -> "Você ainda não adicionou amigos."
                                    },
                                    color = colors.textMuted
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(visible, key = { it.id }) { friendship ->
                                    val peerUid = friendship.peerUid(currentUserId)
                                    val peer = peerProfiles[peerUid]
                                    val name = peer?.displayName ?: friendship.peerName(currentUserId)
                                    val handle = peer?.username ?: friendship.peerUsername(currentUserId)
                                    val isFavorite = favoriteFriendUids.contains(peerUid)

                                    Card(
                                        shape = RoundedCornerShape(12.dp),
                                        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(12.dp)
                                        ) {
                                            UserAvatar(
                                                displayName = name,
                                                avatarUrl = peer?.avatarUrl ?: friendship.peerAvatarUrl(currentUserId),
                                                presence = peer?.presence ?: "offline"
                                            )
                                            Spacer(Modifier.width(12.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(name, style = MaterialTheme.typography.titleSmall)
                                                Text(
                                                    "@$handle" + (peer?.statusText?.takeIf { it.isNotBlank() }?.let { " • $it" } ?: ""),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = colors.textSecondary
                                                )
                                            }
                                            IconButton(onClick = {
                                                socialViewModel.toggleFavoriteFriend(peerUid)
                                            }) {
                                                Icon(
                                                    imageVector = if (isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                                                    contentDescription = if (isFavorite) "Remover dos favoritos locais" else "Fixar nos favoritos locais",
                                                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else colors.textMuted
                                                )
                                            }
                                            IconButton(onClick = {
                                                socialViewModel.openDirectChat(peerUid, onOpenConversation)
                                            }) {
                                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Mensagem")
                                            }
                                            IconButton(onClick = {
                                                myProfile?.let { me ->
                                                    launchWithCallPermissions(false) {
                                                        callViewModel.startCall(
                                                            caller = me,
                                                            contextType = "dm",
                                                            contextId = "dm_${listOf(currentUserId, peerUid).sorted().joinToString("_")}",
                                                            callType = "audio",
                                                            participantUids = listOf(currentUserId, peerUid)
                                                        )
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.Call, contentDescription = "Ligar")
                                            }
                                            IconButton(onClick = {
                                                myProfile?.let { me ->
                                                    launchWithCallPermissions(true) {
                                                        callViewModel.startCall(
                                                            caller = me,
                                                            contextType = "dm",
                                                            contextId = "dm_${listOf(currentUserId, peerUid).sorted().joinToString("_")}",
                                                            callType = "video",
                                                            participantUids = listOf(currentUserId, peerUid)
                                                        )
                                                    }
                                                }
                                            }) {
                                                Icon(Icons.Default.Videocam, contentDescription = "Vídeo")
                                            }
                                            IconButton(onClick = { socialViewModel.removeFriend(friendship.id) }) {
                                                Icon(Icons.Default.PersonRemove, contentDescription = "Remover amigo")
                                            }
                                            IconButton(onClick = { socialViewModel.blockUser(peerUid) }) {
                                                Icon(Icons.Default.Block, contentDescription = "Bloquear")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                if (requests.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Nenhuma solicitação pendente.", color = colors.textMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(requests, key = { it.id }) { req ->
                            val incoming = req.toUid == currentUserId
                            val title = if (incoming) req.fromDisplayName else req.toDisplayName
                            val handle = if (incoming) req.fromUsername else req.toUsername
                            val avatar = if (incoming) req.fromAvatarUrl else req.toAvatarUrl

                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    UserAvatar(displayName = title, avatarUrl = avatar)
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(title, style = MaterialTheme.typography.titleSmall)
                                        Text(
                                            if (incoming) "@$handle • Solicitação recebida" else "@$handle • Enviada",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
                                        )
                                    }
                                    if (incoming) {
                                        IconButton(onClick = { socialViewModel.acceptFriendRequest(req) }) {
                                            Icon(Icons.Default.Check, contentDescription = "Aceitar", tint = colors.online)
                                        }
                                    }
                                    IconButton(onClick = { socialViewModel.declineFriendRequest(req.id) }) {
                                        Icon(Icons.Default.Close, contentDescription = "Recusar / Cancelar", tint = colors.dnd)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            3 -> {
                if (blockedUids.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("Nenhum usuário bloqueado.", color = colors.textMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedUids, key = { it }) { uid ->
                            val p = peerProfiles[uid]
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp)
                                ) {
                                    UserAvatar(displayName = p?.displayName ?: uid, avatarUrl = p?.avatarUrl)
                                    Spacer(Modifier.width(12.dp))
                                    Text(p?.displayName ?: uid, modifier = Modifier.weight(1f))
                                    TextButton(onClick = { socialViewModel.unblockUser(uid) }) {
                                        Text("Desbloquear")
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
