package com.example

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ThemeMode
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.preferences.LocalFriendsCache
import com.example.data.remote.AuthRepository
import com.example.data.remote.CallRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.EmailAuthValidator
import com.example.data.remote.ProfileRepository
import com.example.data.remote.ServerClock
import com.example.data.remote.ServerRepository
import com.example.data.remote.SocialRepository
import com.example.data.remote.authStateFlow
import com.example.notifications.TalkbackFirebaseMessagingService
import com.example.ui.navigation.TalkbackMainShell
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.TalkbackTheme
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TalkbackFirebaseMessagingService.createChannels(applicationContext)

        val firebaseReady = ensureFirebaseInitialized()
        val prefsRepo = AppPreferencesRepository(applicationContext)

        if (!firebaseReady) {
            setContent {
                val themeMode by prefsRepo.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
                TalkbackTheme(themeMode = themeMode) {
                    Scaffold { innerPadding ->
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(innerPadding)
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Configuração Firebase ausente",
                                style = MaterialTheme.typography.titleLarge,
                                textAlign = TextAlign.Center
                            )
                            Text(
                                text = "Não foi possível inicializar o FirebaseApp. Verifique google-services.json e os recursos do projeto.",
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            return
        }

        val auth = Firebase.auth
        val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        val storage = runCatching { FirebaseStorage.getInstance() }.getOrNull()
        val serverClock = ServerClock()
        val localFriendsCache = LocalFriendsCache(applicationContext)

        val authRepo = AuthRepository(db = db, auth = auth)

        val profileRepo = ProfileRepository(db = db, storage = storage, auth = auth)
        val socialRepo = SocialRepository(
            db = db,
            auth = auth,
            serverClock = serverClock,
            localFriendsCache = localFriendsCache
        )
        val chatRepo = ChatRepository(db = db, auth = auth, serverClock = serverClock, profileRepo = profileRepo)
        val serverRepo = ServerRepository(db = db, auth = auth, serverClock = serverClock, profileRepo = profileRepo)
        val callRepo = CallRepository(db = db, auth = auth, serverClock = serverClock)

        setContent {
            val themeMode by prefsRepo.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val isDark = when (themeMode) {
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }

            LaunchedEffect(isDark) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    },
                    navigationBarStyle = if (isDark) {
                        SystemBarStyle.dark(Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    }
                )
            }

            val currentUser by remember { auth.authStateFlow() }
                .collectAsStateWithLifecycle(initialValue = auth.currentUser)
            val isCompletingAuthGate by authRepo.isCompletingAuthGate
                .collectAsStateWithLifecycle(initialValue = false)

            LaunchedEffect(currentUser?.uid, isCompletingAuthGate) {
                val user = currentUser
                if (user == null || isCompletingAuthGate) return@LaunchedEffect
                authRepo.ensureUserInitialized(user)
                serverClock.syncWithServer(db, user.uid)
                runCatching {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (!token.isNullOrBlank()) {
                        profileRepo.updateFcmToken(token)
                    }
                }
            }

            val scope = rememberCoroutineScope()
            var isAuthSubmitting by rememberSaveable { mutableStateOf(false) }
            var authErrorMessage by rememberSaveable { mutableStateOf<String?>(null) }

            TalkbackTheme(themeMode = themeMode) {
                val uid = if (isCompletingAuthGate) null else currentUser?.uid
                if (uid == null) {
                    AuthScreen(
                        isLoading = isAuthSubmitting || isCompletingAuthGate,
                        errorMessage = authErrorMessage,
                        onClearError = { authErrorMessage = null },
                        onLoginSubmit = { email, password ->
                            if (isAuthSubmitting) return@AuthScreen
                            scope.launch {
                                isAuthSubmitting = true
                                authErrorMessage = null
                                val result = authRepo.signInWithEmailAndPassword(email, password)
                                result.onFailure { err ->
                                    authErrorMessage = EmailAuthValidator.mapAuthException(err)
                                }
                                isAuthSubmitting = false
                            }
                        },
                        onSignUpSubmit = { email, password ->
                            if (isAuthSubmitting) return@AuthScreen
                            scope.launch {
                                isAuthSubmitting = true
                                authErrorMessage = null
                                val result = authRepo.signUpWithEmailAndPassword(email, password)
                                result.onFailure { err ->
                                    authErrorMessage = EmailAuthValidator.mapAuthException(err)
                                }
                                isAuthSubmitting = false
                            }
                        }
                    )
                } else {
                    key(uid) {
                        TalkbackMainShell(
                            currentUserId = uid,
                            themeMode = themeMode,
                            authRepository = authRepo,
                            profileRepo = profileRepo,
                            socialRepo = socialRepo,
                            chatRepo = chatRepo,
                            serverRepo = serverRepo,
                            callRepo = callRepo,
                            prefsRepo = prefsRepo,
                            serverClock = serverClock,
                            localFriendsCache = localFriendsCache
                        )
                    }
                }
            }
        }
    }

    private fun ensureFirebaseInitialized(): Boolean {
        if (FirebaseApp.getApps(applicationContext).isNotEmpty()) return true
        val defaultInit = runCatching { FirebaseApp.initializeApp(applicationContext) }.getOrNull()
        if (defaultInit != null) return true

        return runCatching {
            val appId = getString(R.string.google_app_id)
            val apiKey = getString(R.string.google_api_key)
            val projectId = getString(R.string.project_id)
            val senderId = getString(R.string.gcm_defaultSenderId)
            val storageBucket = getString(R.string.google_storage_bucket)
            val options = FirebaseOptions.Builder()
                .setApplicationId(appId)
                .setApiKey(apiKey)
                .setProjectId(projectId)
                .setGcmSenderId(senderId)
                .setStorageBucket(storageBucket)
                .build()
            FirebaseApp.initializeApp(applicationContext, options) != null
        }.onFailure {
            Log.e("MainActivity", "Failed to initialize FirebaseApp", it)
        }.getOrDefault(false)
    }
}
