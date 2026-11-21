package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PresentToAll
import androidx.compose.material.icons.filled.SpeakerPhone
import androidx.compose.material.icons.filled.StopScreenShare
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.data.model.AudioOutputRoute
import com.example.data.model.CallSession
import com.example.data.model.Friendship
import com.example.data.model.ScreenAudioSource
import com.example.data.model.UiState
import com.example.data.model.UserProfile
import com.example.media.WebRtcRuntimeState
import com.example.ui.components.UserAvatar
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.viewmodel.CallViewModel
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallsScreen(
    currentUserId: String,
    myProfile: UserProfile?,
    callHistoryState: UiState<List<CallSession>>,
    friendshipsState: UiState<List<Friendship>>,
    callViewModel: CallViewModel
) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current
    val calls = (callHistoryState as? UiState.Success)?.data ?: emptyList()
    val friendships = (friendshipsState as? UiState.Success)?.data ?: emptyList()

    var pendingCallAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val avPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results[Manifest.permission.RECORD_AUDIO] == true) {
            pendingCallAction?.invoke()
        }
        pendingCallAction = null
    }

    fun launchWithPermissions(needCamera: Boolean, action: () -> Unit) {
        val hasAudio = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val hasCam = !needCamera || ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (hasAudio && hasCam) {
            action()
        } else {
            pendingCallAction = action
            val perms = if (needCamera) {
                arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA)
            } else {
                arrayOf(Manifest.permission.RECORD_AUDIO)
            }
            avPermissionLauncher.launch(perms)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.chatBackground)
    ) {
        TopAppBar(
            title = { Text("Chamadas", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.sidebarBackground)
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val ringing = calls.filter { it.status == "ringing" && it.callerUid != currentUserId }
            if (ringing.isNotEmpty()) {
                item(key = "incoming_header") {
                    Text("CHAMADA RECEBIDA", style = MaterialTheme.typography.labelSmall, color = colors.online)
                }
                items(ringing, key = { "ring_${it.id}" }) { call ->
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(14.dp)
                        ) {
                            UserAvatar(displayName = call.callerName, avatarUrl = null, presence = "online")
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(call.callerName, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (call.callType == "video") "Chamada de vídeo recebida" else "Chamada de voz recebida",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.textSecondary
                                )
                            }
                            IconButton(onClick = { callViewModel.declineIncomingCall(call.id) }) {
                                Icon(Icons.Default.CallEnd, contentDescription = "Recusar", tint = colors.dnd)
                            }
                            IconButton(onClick = {
                                launchWithPermissions(call.callType == "video") {
                                    callViewModel.acceptIncomingCall(call)
                                }
                            }) {
                                Icon(Icons.Default.Call, contentDescription = "Aceitar", tint = colors.online)
                            }
                        }
                    }
                }
            }

            if (friendships.isNotEmpty()) {
                item(key = "quick_call_header") {
                    Text("INICIAR CHAMADA RÁPIDA", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                }
                items(friendships, key = { "fc_${it.id}" }) { f ->
                    val peerUid = f.peerUid(currentUserId)
                    val peerName = f.peerName(currentUserId)
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(12.dp)
                        ) {
                            UserAvatar(displayName = peerName, avatarUrl = f.peerAvatarUrl(currentUserId))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(peerName, style = MaterialTheme.typography.titleSmall)
                                Text("@${f.peerUsername(currentUserId)}", style = MaterialTheme.typography.bodySmall, color = colors.textSecondary)
                            }
                            IconButton(onClick = {
                                launchWithPermissions(false) {
                                    myProfile?.let { me ->
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
                                Icon(Icons.Default.Call, contentDescription = "Chamada de voz")
                            }
                            IconButton(onClick = {
                                launchWithPermissions(true) {
                                    myProfile?.let { me ->
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
                                Icon(Icons.Default.Videocam, contentDescription = "Chamada de vídeo")
                            }
                        }
                    }
                }
            }

            item(key = "history_header") {
                Spacer(Modifier.height(6.dp))
                Text("HISTÓRICO DE CHAMADAS", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
            }

            when (callHistoryState) {
                UiState.Loading -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                is UiState.Error -> item(key = "error") {
                    Text(callHistoryState.message, color = MaterialTheme.colorScheme.error)
                }
                is UiState.Success -> {
                    if (calls.isEmpty()) {
                        item(key = "empty_calls") {
                            Text("Nenhuma chamada registrada ainda.", color = colors.textMuted)
                        }
                    } else {
                        items(calls, key = { it.id }) { call ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                                ) {
                                    Icon(
                                        imageVector = if (call.callType == "video") Icons.Default.Videocam else Icons.Default.Call,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${call.callerName} (${call.contextType})",
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                        Text(
                                            "Status: ${call.status} • ${call.participantUids.size} participantes",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.textSecondary
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
}

@Composable
fun ActiveCallOverlay(
    call: CallSession,
    durationSeconds: Long,
    webRtcState: WebRtcRuntimeState,
    eglBaseContext: EglBase.Context?,
    callViewModel: CallViewModel
) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current
    var showAudioOutputDialog by remember { mutableStateOf(false) }
    var showScreenAudioChoice by remember { mutableStateOf(false) }
    var chosenScreenAudio by remember { mutableStateOf(ScreenAudioSource.MICROPHONE) }
    var minimized by remember { mutableStateOf(false) }

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            callViewModel.startScreenShare(result.resultCode, result.data!!, chosenScreenAudio)
        }
    }

    if (minimized) {
        Surface(
            color = colors.railBackground,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
                .clickable { minimized = false }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Text(
                    "Em chamada • ${formatDuration(durationSeconds)}" +
                        if (webRtcState.isScreenSharing) " • Compartilhando tela" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.online,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { callViewModel.endActiveCall() }) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Encerrar", tint = colors.dnd)
                }
            }
        }
        return
    }

    BackHandler { minimized = true }

    Surface(
        color = colors.railBackground,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top call header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = call.callerName,
                        style = MaterialTheme.typography.titleLarge,
                        color = colors.textPrimary
                    )
                    Text(
                        text = "${webRtcState.connectionStateLabel} • ${formatDuration(durationSeconds)}" +
                            if (webRtcState.sfuConnected) " • SFU Ativo" else " • WebRTC P2P",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (webRtcState.isConnected) colors.online else colors.textSecondary
                    )
                }
                TextButton(onClick = { minimized = true }) {
                    Text("Minimizar")
                }
            }

            // Screen share indicator banner
            if (webRtcState.isScreenSharing) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.PresentToAll, contentDescription = null, tint = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Tela compartilhada (${webRtcState.screenAudioSource.labelPt})",
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { callViewModel.stopScreenShare() }) {
                            Text("Parar", color = Color.White)
                        }
                    }
                }
            }

            // Video / Participant Grid
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val remoteEntry = webRtcState.remoteVideoTracks.entries.firstOrNull()
                if (remoteEntry != null && eglBaseContext != null) {
                    WebRtcVideoView(
                        videoTrack = remoteEntry.value,
                        eglBaseContext = eglBaseContext,
                        mirror = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(16.dp))
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        UserAvatar(displayName = call.callerName, avatarUrl = null, presence = "online", size = 96.dp)
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "${call.activeUids.size} ativo(s) na chamada",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.textSecondary
                        )
                    }
                }

                if (webRtcState.localVideoTrack != null && eglBaseContext != null) {
                    WebRtcVideoView(
                        videoTrack = webRtcState.localVideoTrack,
                        eglBaseContext = eglBaseContext,
                        mirror = webRtcState.isUsingFrontCamera && !webRtcState.isScreenSharing,
                        modifier = Modifier
                            .size(width = 110.dp, height = 160.dp)
                            .align(Alignment.BottomEnd)
                            .padding(8.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            // Bottom Controls
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(colors.sidebarBackground)
                    .padding(vertical = 12.dp, horizontal = 8.dp)
            ) {
                IconButton(
                    onClick = { callViewModel.toggleMute() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (webRtcState.isMicMuted) colors.dnd else colors.elevatedBackground)
                        .testTag("call_mute_button")
                ) {
                    Icon(
                        imageVector = if (webRtcState.isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "Silenciar microfone",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { callViewModel.toggleCamera() },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (webRtcState.isCameraEnabled) MaterialTheme.colorScheme.primary else colors.elevatedBackground)
                        .testTag("call_camera_button")
                ) {
                    Icon(
                        imageVector = if (webRtcState.isCameraEnabled) Icons.Default.Videocam else Icons.Default.VideocamOff,
                        contentDescription = "Câmera",
                        tint = Color.White
                    )
                }

                if (webRtcState.isCameraEnabled) {
                    IconButton(
                        onClick = { callViewModel.switchCamera() },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(colors.elevatedBackground)
                    ) {
                        Icon(Icons.Default.Cameraswitch, contentDescription = "Alternar câmera", tint = Color.White)
                    }
                }

                IconButton(
                    onClick = {
                        if (webRtcState.isScreenSharing) {
                            callViewModel.stopScreenShare()
                        } else {
                            showScreenAudioChoice = true
                        }
                    },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (webRtcState.isScreenSharing) MaterialTheme.colorScheme.primary else colors.elevatedBackground)
                        .testTag("call_screen_share_button")
                ) {
                    Icon(
                        imageVector = if (webRtcState.isScreenSharing) Icons.Default.StopScreenShare else Icons.Default.PresentToAll,
                        contentDescription = "Compartilhar tela",
                        tint = Color.White
                    )
                }

                IconButton(
                    onClick = { showAudioOutputDialog = true },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(colors.elevatedBackground)
                ) {
                    Icon(Icons.Default.SpeakerPhone, contentDescription = "Saída de áudio", tint = Color.White)
                }

                Button(
                    onClick = { callViewModel.endActiveCall() },
                    colors = ButtonDefaults.buttonColors(containerColor = colors.dnd),
                    shape = CircleShape,
                    modifier = Modifier.size(52.dp).testTag("call_end_button")
                ) {
                    Icon(Icons.Default.CallEnd, contentDescription = "Encerrar chamada", tint = Color.White)
                }
            }
        }
    }

    if (showAudioOutputDialog) {
        AlertDialog(
            onDismissRequest = { showAudioOutputDialog = false },
            title = { Text("Saída de Áudio") },
            text = {
                Column {
                    AudioOutputRoute.entries.forEach { route ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    callViewModel.setAudioOutput(route)
                                    showAudioOutputDialog = false
                                }
                                .padding(vertical = 6.dp)
                        ) {
                            RadioButton(
                                selected = webRtcState.audioOutput == route,
                                onClick = {
                                    callViewModel.setAudioOutput(route)
                                    showAudioOutputDialog = false
                                }
                            )
                            Text(route.labelPt)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showAudioOutputDialog = false }) { Text("Fechar") } }
        )
    }

    if (showScreenAudioChoice) {
        AlertDialog(
            onDismissRequest = { showScreenAudioChoice = false },
            title = { Text("Áudio do Compartilhamento de Tela") },
            text = {
                Column {
                    ScreenAudioSource.entries.forEach { src ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { chosenScreenAudio = src }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(selected = chosenScreenAudio == src, onClick = { chosenScreenAudio = src })
                            Text(src.labelPt)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScreenAudioChoice = false
                    val mpm = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                }) { Text("Continuar") }
            },
            dismissButton = { TextButton(onClick = { showScreenAudioChoice = false }) { Text("Cancelar") } }
        )
    }
}

@Composable
fun WebRtcVideoView(
    videoTrack: VideoTrack,
    eglBaseContext: EglBase.Context,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    val rendererRef = remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(videoTrack, rendererRef.value) {
        val view = rendererRef.value
        if (view != null) {
            videoTrack.addSink(view)
        }
        onDispose {
            if (view != null) {
                runCatching { videoTrack.removeSink(view) }
            }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                rendererRef.value = this
            }
        },
        update = { view -> view.setMirror(mirror) },
        onRelease = { view ->
            runCatching { videoTrack.removeSink(view) }
            view.release()
        },
        modifier = modifier
    )
}

private fun formatDuration(seconds: Long): String {
    val mins = seconds / 60
    val secs = seconds % 60
    return "%02d:%02d".format(mins, secs)
}
