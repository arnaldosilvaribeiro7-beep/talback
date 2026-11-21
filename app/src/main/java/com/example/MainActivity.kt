package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.model.ThemeMode
import com.example.data.preferences.AppPreferencesRepository
import com.example.data.remote.AuthRepository
import com.example.data.remote.CallRepository
import com.example.data.remote.ChatRepository
import com.example.data.remote.ProfileRepository
import com.example.data.remote.ServerRepository
import com.example.data.remote.SocialRepository
import com.example.data.remote.authStateFlow
import com.example.notifications.TalkbackFirebaseMessagingService
import com.example.ui.navigation.TalkbackMainShell
import com.example.ui.screens.AuthScreen
import com.example.ui.theme.TalkbackTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        TalkbackFirebaseMessagingService.createChannels(applicationContext)

        val prefsRepo = AppPreferencesRepository(applicationContext)
        val auth = Firebase.auth
        val db = FirebaseFirestore.getInstance(getString(R.string.firestore_database_id))
        val storage = FirebaseStorage.getInstance()

        val authRepo = AuthRepository(db = db, auth = auth)
        val profileRepo = ProfileRepository(db = db, storage = storage, auth = auth)
        val socialRepo = SocialRepository(db = db, auth = auth)
        val chatRepo = ChatRepository(db = db, auth = auth)
        val serverRepo = ServerRepository(db = db, auth = auth)
        val callRepo = CallRepository(db = db, auth = auth)

        setContent {
            val themeMode by prefsRepo.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.DARK)
            val currentUser by remember { auth.authStateFlow() }
                .collectAsStateWithLifecycle(initialValue = auth.currentUser)

            LaunchedEffect(currentUser?.uid) {
                val user = currentUser ?: return@LaunchedEffect
                authRepo.ensureUserInitialized(user)
                runCatching {
                    val token = FirebaseMessaging.getInstance().token.await()
                    if (!token.isNullOrBlank()) {
                        profileRepo.updateFcmToken(token)
                    }
                }
            }

            TalkbackTheme(themeMode = themeMode) {
                val uid = currentUser?.uid
                if (uid == null) {
                    AuthScreen(
                        authRepository = authRepo,
                        onAuthSuccess = {}
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
                            prefsRepo = prefsRepo
                        )
                    }
                }
            }
        }
    }
}
