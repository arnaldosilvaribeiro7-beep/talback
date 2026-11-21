package com.example.data.remote

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

@Serializable
private data class GoogleTokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("id_token") val idToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0L
)

@Serializable
private data class GoogleTokenErrorResponse(
    val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null
)

fun FirebaseAuth.authStateFlow(): Flow<FirebaseUser?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
        trySend(firebaseAuth.currentUser)
    }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

class AuthRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun completeGoogleOAuthCodeExchange(
        authorizationCode: String,
        pendingSession: PendingOAuthSession
    ): Result<Unit> {
        return try {
            val tokenResponse = exchangeAuthorizationCodeForTokens(
                code = authorizationCode,
                clientId = pendingSession.clientId,
                codeVerifier = pendingSession.codeVerifier,
                redirectUri = pendingSession.redirectUri
            )

            if (tokenResponse.idToken.isBlank() || tokenResponse.accessToken.isBlank()) {
                return Result.failure(
                    IllegalStateException("Resposta OAuth incompleta do Google (tokens ausentes).")
                )
            }

            val claims = GoogleOAuthProtocol.validateGoogleIdTokenPayload(
                idToken = tokenResponse.idToken,
                expectedClientId = pendingSession.clientId,
                expectedNonce = pendingSession.nonce
            ).getOrElse { err ->
                return Result.failure(err)
            }

            val authResult = try {
                val credential = GoogleAuthProvider.getCredential(
                    tokenResponse.idToken,
                    tokenResponse.accessToken
                )
                auth.signInWithCredential(credential).await()
            } catch (e: FirebaseAuthInvalidCredentialsException) {
                // If Firebase Auth Google provider only safelists the Web Client ID for id_token audience,
                // authenticate via verified OAuth access_token after our strict local OIDC ID token checks.
                Log.w(
                    "Auth",
                    "Firebase rejected id_token aud (${e.errorCode}); retrying with verified OAuth accessToken",
                    e
                )
                val accessCredential = GoogleAuthProvider.getCredential(
                    null,
                    tokenResponse.accessToken
                )
                auth.signInWithCredential(accessCredential).await()
            }

            val user = authResult.user
                ?: return Result.failure(
                    IllegalStateException("Sessão Firebase não foi criada após o login.")
                )

            ensureUserInitialized(
                user = user,
                preferredDisplayName = claims.name,
                preferredAvatarUrl = claims.picture
            )
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e("Auth", "Network failure during OAuth token exchange", e)
            Result.failure(
                IllegalStateException("Falha de conexão ao validar autenticação com o Google.")
            )
        } catch (e: Exception) {
            Log.e("Auth", "OAuth sign-in failed", e)
            Result.failure(e)
        }
    }

    private suspend fun exchangeAuthorizationCodeForTokens(
        code: String,
        clientId: String,
        codeVerifier: String,
        redirectUri: String
    ): GoogleTokenResponse = withContext(Dispatchers.IO) {
        val formBody = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .add("redirect_uri", redirectUri)
            .build()

        val request = Request.Builder()
            .url(GoogleOAuthProtocol.TOKEN_ENDPOINT)
            .post(formBody)
            .build()

        httpClient.newCall(request).execute().use { response ->
            val rawBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val errPayload = runCatching {
                    json.decodeFromString<GoogleTokenErrorResponse>(rawBody)
                }.getOrNull()
                val msg = errPayload?.errorDescription?.takeIf { it.isNotBlank() }
                    ?: errPayload?.error?.let { "Erro OAuth ($it) ao trocar código por token." }
                    ?: "Erro HTTP ${response.code} ao trocar código OAuth."
                throw IllegalStateException(msg)
            }
            json.decodeFromString<GoogleTokenResponse>(rawBody)
        }
    }

    suspend fun ensureUserInitialized(
        user: FirebaseUser,
        preferredUsername: String? = null,
        preferredDisplayName: String? = null,
        preferredAvatarUrl: String? = null
    ): Result<Unit> {
        val uid = user.uid
        val userDocRef = db.collection("users").document(uid)
        return try {
            val snapshot = userDocRef.get().await()
            if (!snapshot.exists()) {
                val rawDisplay = preferredDisplayName?.takeIf { it.isNotBlank() }
                    ?: user.displayName?.takeIf { it.isNotBlank() }
                    ?: "Usuário Talkback"
                val displayName = rawDisplay.take(50)
                val baseHandle = rawDisplay.lowercase()
                    .replace(Regex("[^a-z0-9_]"), "")
                    .ifBlank { "talkback" }
                    .take(18)
                val username = preferredUsername?.takeIf { it.matches(Regex("^[a-z0-9_.]{3,30}$")) }
                    ?: "${baseHandle}_${uid.take(6).lowercase()}"
                val avatarUrl = (preferredAvatarUrl?.takeIf { it.isNotBlank() }
                    ?: user.photoUrl?.toString())?.take(500)

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
                } finally {
                    auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
