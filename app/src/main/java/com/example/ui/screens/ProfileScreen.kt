package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.data.model.AudioOutputRoute
import com.example.data.model.AudioProcessingSettings
import com.example.data.model.PresenceStatus
import com.example.data.model.PrivateAccount
import com.example.data.model.SfuConfiguration
import com.example.data.model.ThemeMode
import com.example.data.model.UserProfile
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.remote.AuthRepository
import com.example.ui.components.ProfileHeaderCard
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.theme.ProfileColorPresets
import com.example.ui.theme.ProfileSingleSwatches
import com.example.ui.viewmodel.SocialChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen(
    currentUserId: String,
    myProfile: UserProfile?,
    privateAccount: PrivateAccount?,
    themeMode: ThemeMode,
    audioSettings: AudioProcessingSettings,
    sfuConfiguration: SfuConfiguration,
    prefsRepo: AppPreferencesRepository,
    authRepository: AuthRepository,
    socialViewModel: SocialChatViewModel
) {
    val colors = LocalTalkbackColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    var displayName by remember(myProfile?.userId) { mutableStateOf(myProfile?.displayName ?: "") }
    var username by remember(myProfile?.userId) { mutableStateOf(myProfile?.username ?: "") }
    var bio by remember(myProfile?.userId) { mutableStateOf(myProfile?.bio ?: "") }
    var statusText by remember(myProfile?.userId) { mutableStateOf(myProfile?.statusText ?: "") }
    var presence by remember(myProfile?.userId) {
        mutableStateOf(PresenceStatus.fromWire(myProfile?.presence))
    }
    var primaryColorHex by remember(myProfile?.userId) {
        mutableLongStateOf(myProfile?.primaryColorHex ?: 0xFF5865F2L)
    }
    var secondaryColorHex by remember(myProfile?.userId) {
        mutableLongStateOf(myProfile?.secondaryColorHex ?: 0xFF4752C4L)
    }
    var gradientEnabled by remember(myProfile?.userId) {
        mutableStateOf(myProfile?.gradientEnabled ?: false)
    }
    var avatarUrl by remember(myProfile?.userId) { mutableStateOf(myProfile?.avatarUrl) }
    var bannerUrl by remember(myProfile?.userId) { mutableStateOf(myProfile?.bannerUrl) }
    var bannerVideoUrl by remember(myProfile?.userId) { mutableStateOf(myProfile?.bannerVideoUrl) }

    LaunchedEffect(myProfile) {
        if (myProfile != null && displayName.isBlank()) {
            displayName = myProfile.displayName
            username = myProfile.username
            bio = myProfile.bio
            statusText = myProfile.statusText
            presence = PresenceStatus.fromWire(myProfile.presence)
            primaryColorHex = myProfile.primaryColorHex
            secondaryColorHex = myProfile.secondaryColorHex
            gradientEnabled = myProfile.gradientEnabled
            avatarUrl = myProfile.avatarUrl
            bannerUrl = myProfile.bannerUrl
            bannerVideoUrl = myProfile.bannerVideoUrl
        }
    }

    var notifyMessages by remember(privateAccount?.userId) { mutableStateOf(privateAccount?.notifyMessages ?: true) }
    var notifyFriends by remember(privateAccount?.userId) { mutableStateOf(privateAccount?.notifyFriendRequests ?: true) }
    var notifyInvites by remember(privateAccount?.userId) { mutableStateOf(privateAccount?.notifyInvites ?: true) }
    var notifyCalls by remember(privateAccount?.userId) { mutableStateOf(privateAccount?.notifyCalls ?: true) }
    var notifyMentions by remember(privateAccount?.userId) { mutableStateOf(privateAccount?.notifyMentions ?: true) }

    var sfuUrl by remember(sfuConfiguration) { mutableStateOf(sfuConfiguration.signalingUrl) }
    var turnUrl by remember(sfuConfiguration) { mutableStateOf(sfuConfiguration.turnServerUrl) }
    var turnUser by remember(sfuConfiguration) { mutableStateOf(sfuConfiguration.turnUsername) }
    var turnCred by remember(sfuConfiguration) { mutableStateOf(sfuConfiguration.turnCredential) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            socialViewModel.uploadProfileAsset(context, uri, "avatar") { url -> avatarUrl = url }
        }
    }
    val bannerPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            socialViewModel.uploadProfileAsset(context, uri, "banner") { url ->
                bannerUrl = url
                bannerVideoUrl = null
            }
        }
    }
    val bannerVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            socialViewModel.uploadProfileAsset(context, uri, "banner_video") { url -> bannerVideoUrl = url }
        }
    }

    val previewProfile = UserProfile(
        id = currentUserId,
        userId = currentUserId,
        username = username.ifBlank { "usuario" },
        displayName = displayName.ifBlank { "Usuário Talkback" },
        bio = bio,
        statusText = statusText,
        presence = presence.wire,
        avatarUrl = avatarUrl,
        bannerUrl = bannerUrl,
        bannerVideoUrl = bannerVideoUrl,
        primaryColorHex = primaryColorHex,
        secondaryColorHex = secondaryColorHex,
        gradientEnabled = gradientEnabled
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.chatBackground)
    ) {
        TopAppBar(
            title = { Text("Perfil e Configurações", fontWeight = FontWeight.Bold) },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.sidebarBackground)
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier.widthIn(max = 620.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Live Preview before saving
                Text("PRÉVIA DO PERFIL", style = MaterialTheme.typography.labelSmall, color = colors.textMuted)
                ProfileHeaderCard(profile = previewProfile)

                // Identity & Status
                SectionCard(title = "Identidade e Status") {
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = { if (it.length <= 50) displayName = it },
                        label = { Text("Nome de exibição") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_display_name_input")
                    )
                    OutlinedTextField(
                        value = username,
                        onValueChange = { next ->
                            val clean = next.lowercase().filter { it.isLetterOrDigit() || it == '_' || it == '.' }
                            if (clean.length <= 30) username = clean
                        },
                        label = { Text("Nome de usuário exclusivo (@)") },
                        prefix = { Text("@") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("profile_username_input")
                    )
                    OutlinedTextField(
                        value = statusText,
                        onValueChange = { if (it.length <= 80) statusText = it },
                        label = { Text("Status personalizado") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = bio,
                        onValueChange = { if (it.length <= 300) bio = it },
                        label = { Text("Biografia") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Presença", style = MaterialTheme.typography.labelMedium, color = colors.textSecondary)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PresenceStatus.entries.forEach { st ->
                            FilterChip(
                                selected = presence == st,
                                onClick = { presence = st },
                                label = { Text(st.labelPt) }
                            )
                        }
                    }
                }

                // Visual Profile Customization
                SectionCard(title = "Personalização Visual do Perfil") {
                    Text(
                        "Cores e mídia personalizadas afetam apenas seu cartão de perfil, mantendo o tema global do app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )

                    Text("Combinações predefinidas", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileColorPresets.forEach { (c1, c2) ->
                            val selected = primaryColorHex == c1 && secondaryColorHex == c2
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(c1))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else colors.subtleBorder,
                                        shape = CircleShape
                                    )
                                    .clickable {
                                        primaryColorHex = c1
                                        secondaryColorHex = c2
                                    }
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }

                    Text("Cor predominante", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileSingleSwatches.forEach { hex ->
                            val selected = primaryColorHex == hex
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(hex))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else colors.subtleBorder,
                                        shape = CircleShape
                                    )
                                    .clickable { primaryColorHex = hex }
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Text("Cor secundária", style = MaterialTheme.typography.labelMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ProfileSingleSwatches.forEach { hex ->
                            val selected = secondaryColorHex == hex
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(Color(hex))
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) Color.White else colors.subtleBorder,
                                        shape = CircleShape
                                    )
                                    .clickable { secondaryColorHex = hex }
                            ) {
                                if (selected) {
                                    Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Palette, contentDescription = null, tint = colors.textSecondary)
                        Spacer(Modifier.width(10.dp))
                        Text("Usar gradiente (2 cores) na capa", modifier = Modifier.weight(1f))
                        Switch(checked = gradientEnabled, onCheckedChange = { gradientEnabled = it })
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Foto (≤5MB)")
                        }
                        OutlinedButton(
                            onClick = {
                                bannerPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Capa (≤6MB)")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = {
                                bannerVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.VideoFile, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Vídeo curto de capa (≤15MB)")
                        }
                        if (!bannerUrl.isNullOrBlank() || !bannerVideoUrl.isNullOrBlank()) {
                            IconButton(onClick = {
                                bannerUrl = null
                                bannerVideoUrl = null
                            }) {
                                Icon(Icons.Default.Delete, contentDescription = "Remover capa")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            socialViewModel.updateProfile(
                                displayName = displayName,
                                username = username,
                                bio = bio,
                                statusText = statusText,
                                presence = presence,
                                primaryColorHex = primaryColorHex,
                                secondaryColorHex = secondaryColorHex,
                                gradientEnabled = gradientEnabled,
                                avatarUrl = avatarUrl,
                                bannerUrl = bannerUrl,
                                bannerVideoUrl = bannerVideoUrl
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("save_profile_button")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Salvar Perfil", fontWeight = FontWeight.SemiBold)
                    }
                }

                // App Theme
                SectionCard(title = "Tema do Aplicativo") {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { scope.launch { prefsRepo.setThemeMode(mode) } }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = themeMode == mode,
                                onClick = { scope.launch { prefsRepo.setThemeMode(mode) } }
                            )
                            Text(mode.labelPt)
                        }
                    }
                }

                // Notifications
                SectionCard(title = "Notificações Push (FCM)") {
                    ToggleRow("Mensagens diretas e grupos", notifyMessages) { notifyMessages = it }
                    ToggleRow("Solicitações de amizade", notifyFriends) { notifyFriends = it }
                    ToggleRow("Convites de servidores", notifyInvites) { notifyInvites = it }
                    ToggleRow("Chamadas de áudio e vídeo", notifyCalls) { notifyCalls = it }
                    ToggleRow("Menções em canais", notifyMentions) { notifyMentions = it }
                    Button(
                        onClick = {
                            socialViewModel.updateNotificationPrefs(
                                notifyMessages = notifyMessages,
                                notifyFriendRequests = notifyFriends,
                                notifyInvites = notifyInvites,
                                notifyCalls = notifyCalls,
                                notifyMentions = notifyMentions
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Salvar Preferências de Notificação")
                    }
                }

                // WebRTC Audio Processing
                SectionCard(title = "Áudio, Supressão de Ruído e Chamadas") {
                    ToggleRow("Supressão de ruído (NoiseSuppressor)", audioSettings.noiseSuppression) { enabled ->
                        scope.launch {
                            prefsRepo.updateAudioSettings(audioSettings.copy(noiseSuppression = enabled))
                        }
                    }
                    ToggleRow("Cancelamento de eco (AcousticEchoCanceler)", audioSettings.echoCancellation) { enabled ->
                        scope.launch {
                            prefsRepo.updateAudioSettings(audioSettings.copy(echoCancellation = enabled))
                        }
                    }
                    ToggleRow("Controle automático de ganho (AGC)", audioSettings.autoGainControl) { enabled ->
                        scope.launch {
                            prefsRepo.updateAudioSettings(audioSettings.copy(autoGainControl = enabled))
                        }
                    }
                    ToggleRow("Redução de ruído de fundo (WebRTC)", audioSettings.backgroundNoiseReduction) { enabled ->
                        scope.launch {
                            prefsRepo.updateAudioSettings(audioSettings.copy(backgroundNoiseReduction = enabled))
                        }
                    }
                    HorizontalDivider(color = colors.subtleBorder)
                    Text("Saída de áudio padrão", style = MaterialTheme.typography.labelMedium)
                    AudioOutputRoute.entries.forEach { route ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        prefsRepo.updateAudioSettings(audioSettings.copy(preferredOutput = route))
                                    }
                                }
                        ) {
                            RadioButton(
                                selected = audioSettings.preferredOutput == route,
                                onClick = {
                                    scope.launch {
                                        prefsRepo.updateAudioSettings(audioSettings.copy(preferredOutput = route))
                                    }
                                }
                            )
                            Text(route.labelPt)
                        }
                    }
                }

                // SFU & TURN config
                SectionCard(title = "Servidor SFU e TURN (Chamadas em Grupo)") {
                    Text(
                        "Para chamadas em grupo acima do limite P2P, configure seu servidor SFU (LiveKit/Mediasoup/Janus) e credenciais TURN.",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.textSecondary
                    )
                    OutlinedTextField(
                        value = sfuUrl,
                        onValueChange = { sfuUrl = it },
                        label = { Text("URL de sinalização SFU (wss://...)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = turnUrl,
                        onValueChange = { turnUrl = it },
                        label = { Text("Servidor TURN (turn:host:3478)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = turnUser,
                        onValueChange = { turnUser = it },
                        label = { Text("Usuário TURN") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = turnCred,
                        onValueChange = { turnCred = it },
                        label = { Text("Credencial TURN") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                prefsRepo.updateSfuConfiguration(
                                    SfuConfiguration(
                                        signalingUrl = sfuUrl,
                                        turnServerUrl = turnUrl,
                                        turnUsername = turnUser,
                                        turnCredential = turnCred
                                    )
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Salvar Configuração SFU/TURN")
                    }
                }

                // Account & Sign out
                SectionCard(title = "Conta") {
                    Text(
                        text = "Conectado via Google: ${privateAccount?.email ?: currentUserId}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.textSecondary
                    )
                    Button(
                        onClick = {
                            authRepository.signOut(credentialManager, scope)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = colors.dnd),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("sign_out_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Encerrar Sessão", fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    val colors = LocalTalkbackColors.current
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.textPrimary)
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
