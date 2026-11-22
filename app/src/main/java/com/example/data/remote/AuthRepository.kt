package com.example.data.remote

import com.google.firebase.Firebase
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

class AccountLimitReachedException(
    message: String = EmailAuthValidator.ACCOUNT_LIMIT_ERROR_MESSAGE
) : IllegalStateException(message)

object EmailAuthValidator {
    const val MAX_ACCOUNTS = 2
    const val MIN_PASSWORD_LENGTH = 6
    const val MAX_EMAIL_LENGTH = 160
    const val MAX_PASSWORD_LENGTH = 128

    const val ACCOUNT_LIMIT_ERROR_MESSAGE =
        "Limite máximo de 2 contas atingido. Este aplicativo permite no máximo 2 contas cadastradas no total."

    private val EMAIL_REGEX = Regex("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun normalizeEmail(raw: String): String = raw.trim().lowercase()

    fun isValidEmailFormat(raw: String): Boolean {
        val email = raw.trim()
        return email.isNotEmpty() &&
            email.length <= MAX_EMAIL_LENGTH &&
            !email.contains(' ') &&
            EMAIL_REGEX.matches(email)
    }

    fun validateEmail(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            return "Informe seu endereço de e-mail."
        }
        if (!isValidEmailFormat(trimmed)) {
            return "Formato de e-mail inválido. Use um endereço válido como example@gmail.com."
        }
        return null
    }

    fun validateSignUp(email: String, password: String): String? {
        validateEmail(email)?.let { return it }
        if (password.isEmpty()) {
            return "Informe uma senha para criar a conta."
        }
        if (password.length < MIN_PASSWORD_LENGTH) {
            return "A senha deve ter pelo menos $MIN_PASSWORD_LENGTH caracteres."
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            return "A senha deve ter no máximo $MAX_PASSWORD_LENGTH caracteres."
        }
        return null
    }

    fun validateLogin(email: String, password: String): String? {
        validateEmail(email)?.let { return it }
        if (password.isEmpty()) {
            return "Informe sua senha para entrar."
        }
        if (password.length > MAX_PASSWORD_LENGTH) {
            return "A senha deve ter no máximo $MAX_PASSWORD_LENGTH caracteres."
        }
        return null
    }

    fun mapAuthException(throwable: Throwable): String {
        if (throwable is AccountLimitReachedException) {
            return throwable.message ?: ACCOUNT_LIMIT_ERROR_MESSAGE
        }
        if (throwable is IllegalArgumentException && !throwable.message.isNullOrBlank()) {
            return throwable.message!!
        }
        if (throwable is FirebaseNetworkException) {
            return "Sem conexão com a internet. Verifique sua rede e tente novamente."
        }
        if (throwable is FirebaseAuthException) {
            return mapFirebaseAuthErrorCode(throwable.errorCode, throwable.message)
        }
        if (throwable is FirebaseFirestoreException &&
            throwable.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
        ) {
            return ACCOUNT_LIMIT_ERROR_MESSAGE
        }
        return mapFirebaseAuthErrorCode(errorCode = null, rawMessage = throwable.message)
    }

    fun mapFirebaseAuthErrorCode(errorCode: String?, rawMessage: String?): String {
        val code = errorCode.orEmpty().uppercase()
        val msg = rawMessage.orEmpty()
        return when (code) {
            "ERROR_INVALID_EMAIL" ->
                "Formato de e-mail inválido. Use um endereço válido como example@gmail.com."
            "ERROR_WEAK_PASSWORD" ->
                "A senha é muito fraca. Escolha uma senha com pelo menos $MIN_PASSWORD_LENGTH caracteres."
            "ERROR_EMAIL_ALREADY_IN_USE" ->
                "Este e-mail já possui cadastro. Selecione \"Fazer login\" para entrar na sua conta."
            "ERROR_WRONG_PASSWORD",
            "ERROR_USER_NOT_FOUND",
            "ERROR_INVALID_CREDENTIAL" ->
                "E-mail ou senha incorretos. Verifique suas credenciais e tente novamente."
            "ERROR_USER_DISABLED" ->
                "Esta conta foi desativada."
            "ERROR_OPERATION_NOT_ALLOWED" ->
                "O login por e-mail e senha não está ativado no Firebase Authentication. Ative o provedor E-mail/senha no Console do Firebase."
            "ERROR_TOO_MANY_REQUESTS" ->
                "Muitas tentativas seguidas. Aguarde alguns instantes e tente novamente."
            "ERROR_NETWORK_REQUEST_FAILED" ->
                "Sem conexão com a internet. Verifique sua rede e tente novamente."
            else -> when {
                msg.contains("INVALID_LOGIN_CREDENTIALS", ignoreCase = true) ||
                    msg.contains("wrong-password", ignoreCase = true) ||
                    msg.contains("user-not-found", ignoreCase = true) ->
                    "E-mail ou senha incorretos. Verifique suas credenciais e tente novamente."
                msg.contains("EMAIL_EXISTS", ignoreCase = true) ->
                    "Este e-mail já possui cadastro. Selecione \"Fazer login\" para entrar na sua conta."
                msg.contains("WEAK_PASSWORD", ignoreCase = true) ->
                    "A senha deve ter pelo menos $MIN_PASSWORD_LENGTH caracteres."
                msg.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) ->
                    "O login por e-mail e senha não está ativado no Firebase Authentication. Ative o provedor E-mail/senha no Console do Firebase."
                msg.contains("network", ignoreCase = true) ->
                    "Sem conexão com a internet. Verifique sua rede e tente novamente."
                msg.contains("PERMISSION_DENIED", ignoreCase = true) ->
                    ACCOUNT_LIMIT_ERROR_MESSAGE
                else -> "Não foi possível concluir a autenticação. Tente novamente."
            }
        }
    }
}

class AuthRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val _isCompletingAuthGate = MutableStateFlow(false)
    val isCompletingAuthGate: StateFlow<Boolean> = _isCompletingAuthGate.asStateFlow()

    private val accountRegistryRef
        get() = db.collection("app_meta").document("account_registry")

    suspend fun signUpWithEmailAndPassword(
        rawEmail: String,
        password: String
    ): Result<Unit> {
        EmailAuthValidator.validateSignUp(rawEmail, password)?.let { validationError ->
            return Result.failure(IllegalArgumentException(validationError))
        }
        val email = EmailAuthValidator.normalizeEmail(rawEmail)

        _isCompletingAuthGate.value = true
        return try {
            val authResult = auth.createUserWithEmailAndPassword(email, password).await()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("Não foi possível concluir o cadastro."))

            val claimResult = claimAccountSlot(user.uid)
            if (claimResult.isFailure) {
                withContext(NonCancellable) {
                    runCatching { user.delete().await() }
                    runCatching { auth.signOut() }
                }
                val err = claimResult.exceptionOrNull() ?: AccountLimitReachedException()
                val mapped = if (err is FirebaseFirestoreException &&
                    err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                ) {
                    AccountLimitReachedException()
                } else {
                    err
                }
                return Result.failure(mapped)
            }

            val defaultName = defaultDisplayNameForEmail(email)
            val initResult = ensureUserInitialized(
                user = user,
                preferredDisplayName = defaultName
            )
            if (initResult.isFailure) {
                val initErr = initResult.exceptionOrNull() ?: IllegalStateException("Erro ao criar perfil.")
                return Result.failure(initErr)
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isCompletingAuthGate.value = false
        }
    }

    suspend fun signInWithEmailAndPassword(
        rawEmail: String,
        password: String
    ): Result<Unit> {
        EmailAuthValidator.validateLogin(rawEmail, password)?.let { validationError ->
            return Result.failure(IllegalArgumentException(validationError))
        }
        val email = EmailAuthValidator.normalizeEmail(rawEmail)

        _isCompletingAuthGate.value = true
        return try {
            val authResult = auth.signInWithEmailAndPassword(email, password).await()
            val user = authResult.user
                ?: return Result.failure(IllegalStateException("Não foi possível iniciar a sessão."))

            val userSnapshot = db.collection("users").document(user.uid).get().await()
            if (!userSnapshot.exists()) {
                val claimResult = claimAccountSlot(user.uid)
                if (claimResult.isFailure) {
                    withContext(NonCancellable) {
                        runCatching { auth.signOut() }
                    }
                    val err = claimResult.exceptionOrNull() ?: AccountLimitReachedException()
                    val mapped = if (err is FirebaseFirestoreException &&
                        err.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                    ) {
                        AccountLimitReachedException()
                    } else {
                        err
                    }
                    return Result.failure(mapped)
                }
            } else {
                // Best-effort sync for legacy accounts created before account_registry existed.
                runCatching { claimAccountSlot(user.uid) }
            }

            val initResult = ensureUserInitialized(
                user = user,
                preferredDisplayName = defaultDisplayNameForEmail(email)
            )
            if (initResult.isFailure) {
                val initErr = initResult.exceptionOrNull() ?: IllegalStateException("Erro ao carregar perfil.")
                return Result.failure(initErr)
            }

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            _isCompletingAuthGate.value = false
        }
    }

    private suspend fun claimAccountSlot(uid: String): Result<Unit> {
        return try {
            db.runTransaction { transaction ->
                val snapshot = transaction.get(accountRegistryRef)
                if (!snapshot.exists()) {
                    transaction.set(
                        accountRegistryRef,
                        mapOf(
                            "registeredUids" to listOf(uid),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                } else {
                    val rawList = snapshot.get("registeredUids") as? List<*> ?: emptyList<Any?>()
                    val existingUids = rawList.filterIsInstance<String>()
                    if (uid !in existingUids) {
                        if (existingUids.size >= EmailAuthValidator.MAX_ACCOUNTS) {
                            throw AccountLimitReachedException()
                        }
                        transaction.update(
                            accountRegistryRef,
                            mapOf(
                                "registeredUids" to (existingUids + uid),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                    }
                }
                null
            }.await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun defaultDisplayNameForEmail(email: String?): String {
        val localPart = email?.substringBefore('@')?.trim().orEmpty()
        return localPart.ifBlank { "Usuário Talkback" }.take(50)
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
                    ?: defaultDisplayNameForEmail(user.email)
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
