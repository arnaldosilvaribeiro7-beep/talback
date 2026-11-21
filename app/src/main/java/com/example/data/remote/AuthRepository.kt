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
import com.example.BuildConfig
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

object GmailAuthValidator {
    private val GMAIL_REGEX = Regex("^[a-z0-9._%+-]+@gmail\\.com$")
    private val USERNAME_REGEX = Regex("^[a-z0-9_.]{3,30}$")
    private val CODE_REGEX = Regex("^\\d{6}$")

    fun normalizeGmail(rawEmail: String): String? {
        val cleaned = rawEmail.trim().lowercase()
        if (cleaned.isBlank() || cleaned.length > 160) return null
        return cleaned.takeIf { GMAIL_REGEX.matches(it) }
    }

    fun isValidGmail(rawEmail: String): Boolean = normalizeGmail(rawEmail) != null

    fun normalizeUsername(rawUsername: String): String? {
        val cleaned = rawUsername.trim().lowercase().removePrefix("@")
        return cleaned.takeIf { USERNAME_REGEX.matches(it) }
    }

    fun isValidSixDigitCode(rawCode: String): Boolean = CODE_REGEX.matches(rawCode.trim())
}

@Serializable
data class GmailOtpChallenge(
    val challengeId: String,
    val email: String,
    val expiresInSeconds: Int = 600,
    val resendAfterSeconds: Int = 60
)

@Serializable
private data class RequestOtpBody(
    val email: String,
    val username: String? = null,
    val displayName: String? = null,
    val isNewAccount: Boolean
)

@Serializable
private data class VerifyOtpBody(
    val challengeId: String,
    val email: String,
    val code: String,
    val username: String? = null,
    val displayName: String? = null
)

@Serializable
private data class VerifyOtpResponse(
    val customToken: String,
    val uid: String = "",
    val email: String = ""
)

@Serializable
private data class OtpErrorResponse(
    val error: String? = null
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
        .build(),
    private val functionsBaseUrl: String = BuildConfig.AUTH_FUNCTIONS_BASE_URL.trimEnd('/')
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

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
                onAuthError("Nenhuma conta Google encontrada neste dispositivo. Adicione uma conta Google ou use @gmail.com com código de 6 dígitos.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Auth", "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "Falha na autenticação com Google.")
            }
        }
    }

    suspend fun requestGmailVerificationCode(
        rawEmail: String,
        rawUsername: String? = null,
        rawDisplayName: String? = null,
        isNewAccount: Boolean
    ): Result<GmailOtpChallenge> {
        val normalizedEmail = GmailAuthValidator.normalizeGmail(rawEmail)
            ?: return Result.failure(
                IllegalArgumentException("Use exclusivamente um endereço @gmail.com válido.")
            )
        var normalizedUsername: String? = null
        var cleanedDisplayName: String? = null
        if (isNewAccount) {
            normalizedUsername = GmailAuthValidator.normalizeUsername(rawUsername.orEmpty())
                ?: return Result.failure(
                    IllegalArgumentException("Escolha um @usuário válido (3 a 30 caracteres: a-z, 0-9, _ ou .).")
                )
            cleanedDisplayName = rawDisplayName?.trim()?.take(50)?.takeIf { it.isNotBlank() }
                ?: normalizedUsername
        }

        return try {
            val payload = json.encodeToString(
                RequestOtpBody(
                    email = normalizedEmail,
                    username = normalizedUsername,
                    displayName = cleanedDisplayName,
                    isNewAccount = isNewAccount
                )
            )
            val responseBody = postJson("$functionsBaseUrl/requestEmailCode", payload)
            val challenge = json.decodeFromString<GmailOtpChallenge>(responseBody)
            Result.success(challenge)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun verifyGmailCodeAndSignIn(
        challengeId: String,
        rawEmail: String,
        rawCode: String,
        rawUsername: String? = null,
        rawDisplayName: String? = null
    ): Result<Unit> {
        val normalizedEmail = GmailAuthValidator.normalizeGmail(rawEmail)
            ?: return Result.failure(IllegalArgumentException("Use um endereço @gmail.com válido."))
        val cleanCode = rawCode.trim()
        if (!GmailAuthValidator.isValidSixDigitCode(cleanCode)) {
            return Result.failure(IllegalArgumentException("Digite o código numérico de 6 dígitos."))
        }
        val cleanUsername = rawUsername?.let { GmailAuthValidator.normalizeUsername(it) }
        val cleanDisplayName = rawDisplayName?.trim()?.take(50)?.takeIf { it.isNotBlank() }

        return try {
            val payload = json.encodeToString(
                VerifyOtpBody(
                    challengeId = challengeId,
                    email = normalizedEmail,
                    code = cleanCode,
                    username = cleanUsername,
                    displayName = cleanDisplayName
                )
            )
            val responseBody = postJson("$functionsBaseUrl/verifyEmailCode", payload)
            val verified = json.decodeFromString<VerifyOtpResponse>(responseBody)
            val authResult = auth.signInWithCustomToken(verified.customToken).await()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("Não foi possível concluir a sessão no Firebase Auth."))
            ensureUserInitialized(user, preferredUsername = cleanUsername, preferredDisplayName = cleanDisplayName)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun postJson(url: String, jsonBody: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(jsonMediaType))
            .build()
        httpClient.newCall(req).execute().use { resp ->
            val rawBody = resp.body?.string().orEmpty()
            if (resp.isSuccessful) {
                return@withContext rawBody
            }
            val parsedMessage = runCatching {
                json.decodeFromString<OtpErrorResponse>(rawBody).error
            }.getOrNull()
            val message = when {
                !parsedMessage.isNullOrBlank() -> parsedMessage
                resp.code == 404 -> "Serviço de código por e-mail ainda não implantado no Firebase Functions (deploy functions)."
                else -> "Erro ao comunicar com servidor de verificação (${resp.code})."
            }
            throw IllegalStateException(message)
        }
    }

    suspend fun ensureUserInitialized(
        user: FirebaseUser,
        preferredUsername: String? = null,
        preferredDisplayName: String? = null
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
