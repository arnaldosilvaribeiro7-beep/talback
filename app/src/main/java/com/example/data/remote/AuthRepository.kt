package com.example.data.remote

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class AuthRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit
    ) {
        if (auth.currentUser != null) {
            scope.launch {
                ensureUserInitialized(auth.currentUser!!)
                onAuthSuccess()
            }
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id).trim()
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        if (clientId.isBlank()) {
            onUnauthenticated()
            return
        }
        scope.launch {
            try {
                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(true)
                    .setServerClientId(clientId)
                    .setAutoSelectEnabled(true)
                    .build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()
                val cred = credentialManager.getCredential(context, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    result.user?.let { ensureUserInitialized(it) }
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: NoCredentialException) {
                onUnauthenticated()
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: () -> Unit = {}
    ) {
        val clientId = try {
            context.getString(R.string.default_web_client_id).trim()
        } catch (_: Exception) {
            onAuthError("Configuração Google Sign-In ausente (default_web_client_id).")
            return
        }
        if (clientId.isBlank()) {
            onAuthError("Configuração Google Sign-In vazia (default_web_client_id).")
            return
        }
        scope.launch {
            try {
                val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(signInOption)
                    .build()
                val activity = context as? Activity
                    ?: throw IllegalStateException("Contexto de Activity necessário para autenticação.")
                val cred = credentialManager.getCredential(activity, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    val result = auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
                    result.user?.let { ensureUserInitialized(it) }
                    onAuthSuccess()
                } else {
                    onAuthError("Tipo de credencial inesperado.")
                }
            } catch (e: GetCredentialCancellationException) {
                Log.w("Auth", "Google Sign-In cancelled or dismissed: ${e.message}", e)
                onAuthCancelled()
            } catch (e: NoCredentialException) {
                Log.w("Auth", "No Google credentials available on device: ${e.message}", e)
                onAuthError("Nenhuma conta Google encontrada neste dispositivo. Adicione uma conta Google nas configurações.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Falha na autenticação com Google.")
            }
        }
    }

    suspend fun ensureUserInitialized(user: FirebaseUser): Result<Unit> {
        val uid = user.uid
        val userDocRef = db.collection("users").document(uid)
        return try {
            val snapshot = userDocRef.get().await()
            if (!snapshot.exists()) {
                val rawDisplay = user.displayName?.takeIf { it.isNotBlank() } ?: "Usuário Talkback"
                val displayName = rawDisplay.take(50)
                val baseHandle = rawDisplay.lowercase()
                    .replace(Regex("[^a-z0-9_]"), "")
                    .ifBlank { "talkback" }
                    .take(18)
                val username = "${baseHandle}_${uid.take(6).lowercase()}"
                val avatarUrl = user.photoUrl?.toString()?.take(500)

                val profileMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "username" to username,
                    "displayName" to displayName,
                    "bio" to "Disponível no Talkback",
                    "statusText" to "",
                    "presence" to "online",
                    "primaryColorHex" to 0xFF5865F2L,
                    "secondaryColorHex" to 0xFF4752C4L,
                    "gradientEnabled" to false,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!avatarUrl.isNullOrBlank()) {
                    profileMap["avatarUrl"] = avatarUrl
                }

                val claimMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "username" to username,
                    "displayName" to displayName,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                if (!avatarUrl.isNullOrBlank()) {
                    claimMap["avatarUrl"] = avatarUrl
                }

                val privateMap = mutableMapOf<String, Any>(
                    "userId" to uid,
                    "blockedUids" to emptyList<String>(),
                    "notifyMessages" to true,
                    "notifyFriendRequests" to true,
                    "notifyInvites" to true,
                    "notifyCalls" to true,
                    "notifyMentions" to true,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                val email = user.email?.take(160)
                if (!email.isNullOrBlank()) {
                    privateMap["email"] = email
                }

                val batch = db.batch()
                batch.set(userDocRef, profileMap)
                batch.set(db.collection("usernames").document(username), claimMap)
                batch.set(userDocRef.collection("private").document("account"), privateMap)
                batch.commit().await()
            } else {
                userDocRef.update(
                    mapOf(
                        "presence" to "online",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, userDocRef.path)
            Result.failure(e)
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    val uid = auth.currentUser?.uid
                    if (!uid.isNullOrBlank()) {
                        runCatching {
                            db.collection("users").document(uid).update(
                                mapOf(
                                    "presence" to "offline",
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                        }
                    }
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w("Auth", "Failed to clear credential state: ${e.message}")
                } finally {
                    auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
