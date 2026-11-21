package com.example.ui.components

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.R
import com.example.data.model.ChatMessage
import com.example.data.model.PresenceStatus
import com.example.data.model.UserProfile
import com.example.ui.theme.LocalTalkbackColors
import java.text.SimpleDateFormat
import java.util.Locale

private val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🔥")

@Composable
fun rememberCallPermissionLauncher(): (needCamera: Boolean, onGranted: () -> Unit) -> Unit {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            pendingAction?.invoke()
        }
        pendingAction = null
    }
    return remember(context, launcher) {
        { needCamera, onGranted ->
            val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            val hasCam = !needCamera || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            if (hasAudio && hasCam) {
                onGranted()
            } else {
                pendingAction = onGranted
                val perms = if (needCamera) {
                    arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
                } else {
                    arrayOf(Manifest.permission.RECORD_AUDIO)
                }
                launcher.launch(perms)
            }
        }
    }
}

@Composable
fun UserAvatar(
    displayName: String,
    avatarUrl: String?,
    presence: String? = null,
    size: Dp = 42.dp,
    modifier: Modifier = Modifier
) {
    val colors = LocalTalkbackColors.current
    val presenceStatus = presence?.let { PresenceStatus.fromWire(it) }
    val badgeColor = when (presenceStatus) {
        PresenceStatus.ONLINE -> colors.online
        PresenceStatus.IDLE -> colors.idle
        PresenceStatus.DND -> colors.dnd
        PresenceStatus.OFFLINE -> colors.offline
        null -> Color.Transparent
    }

    Box(modifier = modifier.size(size)) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = "Avatar de $displayName",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer)
            )
        } else {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = displayName.take(1).uppercase().ifBlank { "T" },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (presenceStatus != null) {
            Box(
                modifier = Modifier
                    .size(size * 0.32f)
                    .align(Alignment.BottomEnd)
                    .clip(CircleShape)
                    .background(colors.sidebarBackground)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
            )
        }
    }
}

@Composable
fun ProfileHeaderCard(
    profile: UserProfile,
    modifier: Modifier = Modifier
) {
    val colors = LocalTalkbackColors.current
    val primary = Color(profile.primaryColorHex)
    val secondary = Color(profile.secondaryColorHex)
    val bannerBrush = if (profile.gradientEnabled) {
        Brush.linearGradient(listOf(primary, secondary))
    } else {
        Brush.linearGradient(listOf(primary, primary))
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(bannerBrush)
            ) {
                if (!profile.bannerVideoUrl.isNullOrBlank()) {
                    LoopingBannerVideo(
                        videoUrl = profile.bannerVideoUrl,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (!profile.bannerUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = profile.bannerUrl,
                        contentDescription = "Capa do perfil",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Image(
                        painter = painterResource(id = R.drawable.img_default_banner),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.45f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    UserAvatar(
                        displayName = profile.displayName,
                        avatarUrl = profile.avatarUrl,
                        presence = profile.presence,
                        size = 64.dp
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "@${profile.username}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary
                        )
                        if (profile.statusText.isNotBlank()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = colors.elevatedBackground,
                                modifier = Modifier.padding(top = 4.dp)
                            ) {
                                Text(
                                    text = profile.statusText,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.textPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }
                }

                if (profile.bio.isNotBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = colors.subtleBorder)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SOBRE MIM",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.textMuted
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = profile.bio,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
fun LoopingBannerVideo(videoUrl: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(videoUrl) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUrl))
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                this.player = player
            }
        },
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun MessageListAndComposer(
    messages: List<ChatMessage>,
    currentUserId: String,
    typingIndicatorText: String?,
    canSendMessages: Boolean = true,
    canModerateMessages: Boolean = false,
    onLoadMore: () -> Unit,
    onSendMessage: (text: String, replyTo: ChatMessage?) -> Unit,
    onAttachMedia: (uri: Uri, isImage: Boolean, caption: String, replyTo: ChatMessage?) -> Unit,
    onTypingChanged: (Boolean) -> Unit,
    onToggleReaction: (ChatMessage, String) -> Unit,
    onEditMessage: (ChatMessage, String) -> Unit,
    onDeleteMessage: (ChatMessage) -> Unit,
    onReportMessage: (ChatMessage) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val timeFormatter = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    var inputText by remember { mutableStateOf("") }
    var replyTarget by remember { mutableStateOf<ChatMessage?>(null) }
    var editingMessage by remember { mutableStateOf<ChatMessage?>(null) }
    var contextMenuMessage by remember { mutableStateOf<ChatMessage?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onAttachMedia(uri, true, inputText, replyTarget)
            inputText = ""
            replyTarget = null
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            onAttachMedia(uri, false, inputText, replyTarget)
            inputText = ""
            replyTarget = null
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.lastIndex)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.chatBackground)
            .imePadding()
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (messages.size >= 50) {
                item(key = "load_more") {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        TextButton(onClick = onLoadMore) {
                            Text("Carregar mensagens anteriores")
                        }
                    }
                }
            }

            if (messages.isEmpty()) {
                item(key = "empty") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp, horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Nenhuma mensagem ainda",
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.textPrimary
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Envie a primeira mensagem para começar a conversa.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textMuted
                        )
                    }
                }
            }

            items(messages, key = { it.id }) { msg ->
                val isOwn = msg.senderUid == currentUserId
                val timeStr = msg.createdAt?.toDate()?.let { timeFormatter.format(it) } ?: "Agora"
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .combinedClickable(
                            onClick = {},
                            onLongClick = { contextMenuMessage = msg }
                        )
                        .padding(vertical = 6.dp, horizontal = 6.dp)
                ) {
                    if (!msg.replyToPreview.isNullOrBlank()) {
                        val replyTargetExists = msg.replyToMessageId == null ||
                            messages.any { it.id == msg.replyToMessageId }
                        val replyLabel = if (replyTargetExists) {
                            msg.replyToPreview
                        } else {
                            "Respondendo a mensagem expirada ou removida"
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(start = 48.dp, bottom = 4.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.elevatedBackground)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Reply,
                                contentDescription = null,
                                tint = colors.textMuted,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = replyLabel,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        UserAvatar(
                            displayName = msg.senderName,
                            avatarUrl = msg.senderAvatarUrl,
                            size = 38.dp
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = msg.senderName,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isOwn) MaterialTheme.colorScheme.primary else colors.textPrimary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = timeStr,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textMuted
                                )
                                if (msg.edited && !msg.deleted) {
                                    Text(
                                        text = "(editado)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.textMuted
                                    )
                                }
                                if (isOwn && msg.readByUids.size > 1) {
                                    Text(
                                        text = "✓✓",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = colors.online
                                    )
                                }
                            }

                            if (msg.text.isNotBlank()) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = msg.text,
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = if (msg.deleted) colors.textMuted else colors.textPrimary
                                )
                            }

                            if (!msg.attachmentUrl.isNullOrBlank() && !msg.deleted) {
                                Spacer(modifier = Modifier.height(6.dp))
                                if (msg.attachmentType == "image") {
                                    AsyncImage(
                                        model = msg.attachmentUrl,
                                        contentDescription = msg.attachmentName ?: "Imagem anexada",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .widthIn(max = 260.dp)
                                            .height(180.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                    )
                                } else {
                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = colors.elevatedBackground,
                                        modifier = Modifier.clickable {
                                            runCatching {
                                                context.startActivity(
                                                    Intent(Intent.ACTION_VIEW, Uri.parse(msg.attachmentUrl))
                                                )
                                            }
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(10.dp)
                                        ) {
                                            Icon(Icons.Default.AttachFile, contentDescription = null)
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                text = msg.attachmentName ?: "Arquivo anexado",
                                                style = MaterialTheme.typography.bodyMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            if (!msg.linkUrl.isNullOrBlank() && !msg.deleted) {
                                Spacer(modifier = Modifier.height(6.dp))
                                LinkPreviewCard(msg = msg)
                            }

                            if (msg.reactions.isNotEmpty() && !msg.deleted) {
                                Spacer(modifier = Modifier.height(6.dp))
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    msg.reactions.forEach { (emoji, uids) ->
                                        val reactedByMe = uids.contains(currentUserId)
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (reactedByMe) {
                                                MaterialTheme.colorScheme.primaryContainer
                                            } else {
                                                colors.elevatedBackground
                                            },
                                            modifier = Modifier
                                                .border(
                                                    width = 1.dp,
                                                    color = if (reactedByMe) MaterialTheme.colorScheme.primary else colors.subtleBorder,
                                                    shape = RoundedCornerShape(12.dp)
                                                )
                                                .clickable { onToggleReaction(msg, emoji) }
                                        ) {
                                            Text(
                                                text = "$emoji ${uids.size}",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = colors.textPrimary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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

        if (!typingIndicatorText.isNullOrBlank()) {
            Text(
                text = typingIndicatorText,
                style = MaterialTheme.typography.labelMedium,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }

        if (replyTarget != null || editingMessage != null) {
            Surface(
                color = colors.sidebarBackground,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = if (editingMessage != null) {
                            "Editando mensagem"
                        } else {
                            "Respondendo a ${replyTarget?.senderName}"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            replyTarget = null
                            editingMessage = null
                            inputText = ""
                        },
                        modifier = Modifier.minimumInteractiveComponentSize()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancelar")
                    }
                }
            }
        }

        if (canSendMessages) {
            HorizontalDivider(color = colors.subtleBorder)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(colors.sidebarBackground)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                IconButton(
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.testTag("attach_image_button")
                ) {
                    Icon(Icons.Default.Image, contentDescription = "Enviar imagem", tint = colors.textSecondary)
                }
                IconButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    modifier = Modifier.testTag("attach_file_button")
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "Anexar arquivo", tint = colors.textSecondary)
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { next ->
                        inputText = next
                        onTypingChanged(next.isNotBlank())
                    },
                    placeholder = { Text("Enviar mensagem...") },
                    maxLines = 4,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("message_input")
                )
                IconButton(
                    onClick = {
                        val trimmed = inputText.trim()
                        if (trimmed.isNotEmpty()) {
                            val editing = editingMessage
                            if (editing != null) {
                                onEditMessage(editing, trimmed)
                                editingMessage = null
                            } else {
                                onSendMessage(trimmed, replyTarget)
                                replyTarget = null
                            }
                            inputText = ""
                        }
                    },
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("send_message_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Enviar",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }

    contextMenuMessage?.let { target ->
        ModalBottomSheet(onDismissRequest = { contextMenuMessage = null }) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    QUICK_REACTIONS.forEach { emoji ->
                        Surface(
                            shape = CircleShape,
                            color = colors.elevatedBackground,
                            modifier = Modifier
                                .size(48.dp)
                                .clickable {
                                    onToggleReaction(target, emoji)
                                    contextMenuMessage = null
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(emoji, style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                ListItem(
                    headlineContent = { Text("Responder") },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = null) },
                    modifier = Modifier.clickable {
                        replyTarget = target
                        contextMenuMessage = null
                    }
                )
                ListItem(
                    headlineContent = { Text("Copiar texto") },
                    leadingContent = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                    modifier = Modifier.clickable {
                        clipboard.setText(AnnotatedString(target.text))
                        contextMenuMessage = null
                    }
                )
                if (target.senderUid == currentUserId && !target.deleted) {
                    ListItem(
                        headlineContent = { Text("Editar") },
                        leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                        modifier = Modifier.clickable {
                            editingMessage = target
                            inputText = target.text
                            contextMenuMessage = null
                        }
                    )
                }
                if ((target.senderUid == currentUserId || canModerateMessages) && !target.deleted) {
                    ListItem(
                        headlineContent = { Text("Excluir", color = MaterialTheme.colorScheme.error) },
                        leadingContent = {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                        },
                        modifier = Modifier.clickable {
                            onDeleteMessage(target)
                            contextMenuMessage = null
                        }
                    )
                }
                if (target.senderUid != currentUserId) {
                    ListItem(
                        headlineContent = { Text("Denunciar mensagem") },
                        leadingContent = { Icon(Icons.Default.Flag, contentDescription = null) },
                        modifier = Modifier.clickable {
                            onReportMessage(target)
                            contextMenuMessage = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LinkPreviewCard(msg: ChatMessage) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current
    val url = msg.linkUrl ?: return
    val isDirectVideo = url.lowercase().let {
        it.endsWith(".mp4") || it.endsWith(".webm") || it.endsWith(".m3u8")
    }
    var playEmbedded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, colors.subtleBorder, RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                text = msg.linkPlatform ?: "Link",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = msg.linkTitle ?: url,
                style = MaterialTheme.typography.titleSmall,
                color = colors.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (!msg.linkDescription.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = msg.linkDescription,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.textSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (!msg.linkThumbnailUrl.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                AsyncImage(
                    model = msg.linkThumbnailUrl,
                    contentDescription = msg.linkTitle ?: "Miniatura do link",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            }
            if (playEmbedded && isDirectVideo) {
                Spacer(modifier = Modifier.height(8.dp))
                EmbeddedVideoPlayer(url = url, modifier = Modifier.fillMaxWidth().height(180.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isDirectVideo) {
                    AssistChip(
                        onClick = { playEmbedded = !playEmbedded },
                        label = { Text(if (playEmbedded) "Fechar player" else "Reproduzir aqui") },
                        leadingIcon = { Icon(Icons.Default.PlayArrow, contentDescription = null) }
                    )
                }
                AssistChip(
                    onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    },
                    label = { Text("Abrir link externo") },
                    leadingIcon = { Icon(Icons.Default.OpenInNew, contentDescription = null) }
                )
            }
        }
    }
}

@Composable
private fun EmbeddedVideoPlayer(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = modifier.clip(RoundedCornerShape(8.dp))
    )
}
