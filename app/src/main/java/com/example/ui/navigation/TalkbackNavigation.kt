package com.example.ui.navigation

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.model.AudioProcessingSettings
import com.example.data.model.SfuConfiguration
import com.example.data.model.ThemeMode
import com.example.data.model.UiState
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.remote.AuthRepository
import com.example.data.remote.CallRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.ProfileRepository
import com.example.data.remote.ServerClock
import com.example.data.remote.ServerRepository
import com.example.data.remote.SocialRepository
import com.example.ui.screens.ActiveCallOverlay
import com.example.ui.screens.CallsScreen
import com.example.ui.screens.FriendsScreen
import com.example.ui.screens.MessagesScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.ServersScreen
import com.example.ui.theme.LocalTalkbackColors
import com.example.ui.viewmodel.CallViewModel
import com.example.ui.viewmodel.ServerViewModel
import com.example.ui.viewmodel.SocialChatViewModel
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.serialization.Serializable

@Serializable data object MessagesRoute
@Serializable data object ServersRoute
@Serializable data object FriendsRoute
@Serializable data object CallsRoute
@Serializable data object ProfileRoute

private data class TopNavItem<T : Any>(
    val route: T,
    val label: String,
    val icon: ImageVector,
    val testTag: String
)

private val TALKBACK_DESTINATIONS = listOf(
    TopNavItem(MessagesRoute, "Mensagens", Icons.AutoMirrored.Filled.Chat, "nav_messages"),
    TopNavItem(ServersRoute, "Servidores", Icons.Default.Dns, "nav_servers"),
    TopNavItem(FriendsRoute, "Amigos", Icons.Default.Group, "nav_friends"),
    TopNavItem(CallsRoute, "Chamadas", Icons.Default.Call, "nav_calls"),
    TopNavItem(ProfileRoute, "Perfil", Icons.Default.Person, "nav_profile")
)

@Composable
fun TalkbackMainShell(
    currentUserId: String,
    themeMode: ThemeMode,
    authRepository: AuthRepository,
    profileRepo: ProfileRepository,
    socialRepo: SocialRepository,
    chatRepo: ChatRepository,
    serverRepo: ServerRepository,
    callRepo: CallRepository,
    prefsRepo: AppPreferencesRepository,
    serverClock: ServerClock = ServerClock()
) {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val colors = LocalTalkbackColors.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }

    val socialViewModel: SocialChatViewModel = viewModel(
        key = "social_$currentUserId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                SocialChatViewModel(currentUserId, profileRepo, socialRepo, chatRepo, serverClock) as T
        }
    )

    val serverViewModel: ServerViewModel = viewModel(
        key = "server_$currentUserId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ServerViewModel(currentUserId, serverRepo, profileRepo, serverClock) as T
        }
    )

    val callViewModel: CallViewModel = viewModel(
        key = "call_$currentUserId",
        factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CallViewModel(appContext, currentUserId, callRepo, prefsRepo) as T
        }
    )

    // Collect toast events from ViewModels
    LaunchedEffect(socialViewModel) {
        socialViewModel.toastEvents.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(serverViewModel) {
        serverViewModel.toastEvents.collect { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(callViewModel) {
        callViewModel.toastEvents.collect { snackbarHostState.showSnackbar(it) }
    }

    // Request notification permission on Android 13+ & sync FCM token
    val notifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(currentUserId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val myProfileState by socialViewModel.myProfile.collectAsStateWithLifecycle()
    val myProfile = (myProfileState as? UiState.Success)?.data
    val privateAccount by socialViewModel.privateAccount.collectAsStateWithLifecycle()
    val friendshipsState by socialViewModel.friendships.collectAsStateWithLifecycle()
    val requestsState by socialViewModel.friendRequests.collectAsStateWithLifecycle()
    val conversationsState by socialViewModel.conversations.collectAsStateWithLifecycle()
    val selectedConversationId by socialViewModel.selectedConversationId.collectAsStateWithLifecycle()
    val activeMessagesState by socialViewModel.activeMessages.collectAsStateWithLifecycle()
    val peerProfiles by socialViewModel.peerProfiles.collectAsStateWithLifecycle()

    val serversState by serverViewModel.servers.collectAsStateWithLifecycle()
    val selectedServerId by serverViewModel.selectedServerId.collectAsStateWithLifecycle()
    val channelsState by serverViewModel.channels.collectAsStateWithLifecycle()
    val selectedChannelId by serverViewModel.selectedChannelId.collectAsStateWithLifecycle()
    val channelMessagesState by serverViewModel.channelMessages.collectAsStateWithLifecycle()
    val myMembership by serverViewModel.myMembership.collectAsStateWithLifecycle()
    val roles by serverViewModel.roles.collectAsStateWithLifecycle()
    val members by serverViewModel.serverMembers.collectAsStateWithLifecycle()
    val moderationLogs by serverViewModel.moderationLogs.collectAsStateWithLifecycle()

    val callHistoryState by callViewModel.callHistory.collectAsStateWithLifecycle()
    val activeCall by callViewModel.activeCall.collectAsStateWithLifecycle()
    val callDuration by callViewModel.durationSeconds.collectAsStateWithLifecycle()
    val webRtcState by callViewModel.webRtcState.collectAsStateWithLifecycle()

    val audioSettings by prefsRepo.audioSettings.collectAsStateWithLifecycle(initialValue = AudioProcessingSettings())
    val sfuConfig by prefsRepo.sfuConfiguration.collectAsStateWithLifecycle(initialValue = SfuConfiguration())

    val pendingRequestsCount = ((requestsState as? UiState.Success)?.data ?: emptyList())
        .count { it.toUid == currentUserId }
    val incomingCalls = ((callHistoryState as? UiState.Success)?.data ?: emptyList())
        .filter { it.status == "ringing" && it.callerUid != currentUserId }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.railBackground)
    ) {
        val isWideScreen = maxWidth >= 600.dp

        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = colors.chatBackground,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar(
                        containerColor = colors.railBackground,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        TALKBACK_DESTINATIONS.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            val badgeCount = when (item.route) {
                                FriendsRoute -> pendingRequestsCount
                                CallsRoute -> incomingCalls.size
                                else -> 0
                            }
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge { Text(badgeCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .consumeWindowInsets(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        containerColor = colors.railBackground,
                        modifier = Modifier
                            .fillMaxHeight()
                            .windowInsetsPadding(
                                WindowInsets.safeDrawing.only(
                                    WindowInsetsSides.Vertical + WindowInsetsSides.Start
                                )
                            )
                    ) {
                        TALKBACK_DESTINATIONS.forEach { item ->
                            val selected = currentDestination?.hasRoute(item.route::class) == true
                            val badgeCount = when (item.route) {
                                FriendsRoute -> pendingRequestsCount
                                CallsRoute -> incomingCalls.size
                                else -> 0
                            }
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    BadgedBox(
                                        badge = {
                                            if (badgeCount > 0) {
                                                Badge { Text(badgeCount.toString()) }
                                            }
                                        }
                                    ) {
                                        Icon(item.icon, contentDescription = item.label)
                                    }
                                },
                                label = { Text(item.label, maxLines = 1) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    if (activeCall == null && incomingCalls.isNotEmpty()) {
                        val ring = incomingCalls.first()
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Icon(Icons.Default.Call, contentDescription = null, tint = Color.White)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${ring.callerName} está chamando...",
                                    color = Color.White,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { callViewModel.declineIncomingCall(ring.id) }) {
                                    Text("Recusar", color = Color.White)
                                }
                                TextButton(onClick = { navigateTopLevel(CallsRoute) }) {
                                    Text("Abrir", color = Color.White)
                                }
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = MessagesRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        composable<MessagesRoute> {
                            MessagesScreen(
                                currentUserId = currentUserId,
                                myProfile = myProfile,
                                conversationsState = conversationsState,
                                friendshipsState = friendshipsState,
                                selectedConversationId = selectedConversationId,
                                messagesState = activeMessagesState,
                                peerProfiles = peerProfiles,
                                isWideScreen = isWideScreen,
                                socialViewModel = socialViewModel,
                                callViewModel = callViewModel
                            )
                        }

                        composable<ServersRoute> {
                            ServersScreen(
                                currentUserId = currentUserId,
                                myProfile = myProfile,
                                serversState = serversState,
                                selectedServerId = selectedServerId,
                                channelsState = channelsState,
                                selectedChannelId = selectedChannelId,
                                channelMessagesState = channelMessagesState,
                                myMembership = myMembership,
                                roles = roles,
                                members = members,
                                moderationLogs = moderationLogs,
                                isWideScreen = isWideScreen,
                                serverViewModel = serverViewModel,
                                socialViewModel = socialViewModel,
                                callViewModel = callViewModel
                            )
                        }

                        composable<FriendsRoute> {
                            FriendsScreen(
                                currentUserId = currentUserId,
                                myProfile = myProfile,
                                privateAccount = privateAccount,
                                friendshipsState = friendshipsState,
                                requestsState = requestsState,
                                peerProfiles = peerProfiles,
                                socialViewModel = socialViewModel,
                                callViewModel = callViewModel,
                                onOpenConversation = { _ -> navigateTopLevel(MessagesRoute) }
                            )
                        }

                        composable<CallsRoute> {
                            CallsScreen(
                                currentUserId = currentUserId,
                                myProfile = myProfile,
                                callHistoryState = callHistoryState,
                                friendshipsState = friendshipsState,
                                callViewModel = callViewModel
                            )
                        }

                        composable<ProfileRoute> {
                            ProfileScreen(
                                currentUserId = currentUserId,
                                myProfile = myProfile,
                                privateAccount = privateAccount,
                                themeMode = themeMode,
                                audioSettings = audioSettings,
                                sfuConfiguration = sfuConfig,
                                prefsRepo = prefsRepo,
                                authRepository = authRepository,
                                socialViewModel = socialViewModel
                            )
                        }
                    }
                }
            }
        }

        // Active Call / Screen Sharing Overlay
        activeCall?.let { call ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                ActiveCallOverlay(
                    call = call,
                    durationSeconds = callDuration,
                    webRtcState = webRtcState,
                    eglBaseContext = callViewModel.eglBaseContext,
                    callViewModel = callViewModel
                )
            }
        }
    }
}
