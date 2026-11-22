package com.example.data.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsClient
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthResult
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWebException
import com.google.firebase.auth.OAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class OAuthUiState {
    data object Idle : OAuthUiState()
    data object AwaitingBrowser : OAuthUiState()
    data object ExchangingTokens : OAuthUiState()
    data class Error(val message: String) : OAuthUiState()
}

object GoogleOAuthProviderConfig {
    const val PROVIDER_ID = "google.com"
    const val PROMPT_PARAMETER_KEY = "prompt"
    const val PROMPT_SELECT_ACCOUNT = "select_account"

    fun buildProvider(auth: FirebaseAuth, loginHint: String? = null): OAuthProvider {
        val builder = OAuthProvider.newBuilder(PROVIDER_ID, auth)
            .addCustomParameter(PROMPT_PARAMETER_KEY, PROMPT_SELECT_ACCOUNT)
        val trimmedHint = loginHint?.trim()
        if (!trimmedHint.isNullOrBlank()) {
            builder.addCustomParameter("login_hint", trimmedHint)
        }
        return builder.build()
    }

    fun hasCompatibleBrowser(context: Context): Boolean {
        val customTabPackage = runCatching {
            CustomTabsClient.getPackageName(context, emptyList())
        }.getOrNull()
        if (!customTabPackage.isNullOrBlank()) return true

        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://accounts.google.com"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        return browserIntent.resolveActivity(context.packageManager) != null
    }

    fun mapAuthException(throwable: Throwable): OAuthUiState {
        if (throwable is CancellationException) {
            return OAuthUiState.Idle
        }

        val errorCode = (throwable as? FirebaseAuthException)?.errorCode.orEmpty()
        val rawMessage = throwable.message.orEmpty()
        val isNetwork = throwable is FirebaseNetworkException
        val isCollision = throwable is FirebaseAuthUserCollisionException
        val isWeb = throwable is FirebaseAuthWebException

        return mapErrorCodeAndMessage(
            errorCode = errorCode,
            rawMessage = rawMessage,
            isWebException = isWeb,
            isNetworkException = isNetwork,
            isUserCollision = isCollision
        )
    }

    internal fun mapErrorCodeAndMessage(
        errorCode: String,
        rawMessage: String,
        isWebException: Boolean = false,
        isNetworkException: Boolean = false,
        isUserCollision: Boolean = false
    ): OAuthUiState {
        if (isWebException) {
            return when (errorCode) {
                "ERROR_WEB_CONTEXT_CANCELED" -> OAuthUiState.Idle
                "ERROR_WEB_CONTEXT_ALREADY_PRESENTED" -> OAuthUiState.AwaitingBrowser
                "ERROR_WEB_INTERNAL_ERROR" -> OAuthUiState.Error(
                    "Falha interna ao comunicar com o navegador para login Google. Tente novamente."
                )
                "ERROR_WEB_STORAGE_UNSUPPORTED" -> OAuthUiState.Error(
                    "Seu navegador está com armazenamento/cookies bloqueados para concluir o login Google."
                )
                else -> OAuthUiState.Error(mapCommonErrorMessage(errorCode, rawMessage))
            }
        }

        if (errorCode == "ERROR_WEB_CONTEXT_CANCELED" ||
            rawMessage.contains("WEB_CONTEXT_CANCELED", ignoreCase = true) ||
            rawMessage.contains("canceled by user", ignoreCase = true) ||
            rawMessage.contains("cancelled by user", ignoreCase = true)
        ) {
            return OAuthUiState.Idle
        }

        if (isNetworkException || errorCode == "ERROR_NETWORK_REQUEST_FAILED") {
            return OAuthUiState.Error(
                "Falha de conexão com a internet durante a autenticação com o Google."
            )
        }

        if (isUserCollision || errorCode == "ERROR_ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL") {
            return OAuthUiState.Error(
                "Já existe uma conta vinculada a este e-mail com outro método de acesso."
            )
        }

        return OAuthUiState.Error(mapCommonErrorMessage(errorCode, rawMessage))
    }

    private fun mapCommonErrorMessage(errorCode: String, rawMessage: String): String {
        return when {
            errorCode == "ERROR_OPERATION_NOT_ALLOWED" ||
                rawMessage.contains("OPERATION_NOT_ALLOWED", ignoreCase = true) -> {
                "Login com Google não está habilitado no Firebase Authentication deste projeto."
            }
            errorCode == "ERROR_INVALID_CERT_HASH" ||
                rawMessage.contains("certificate hash", ignoreCase = true) ||
                rawMessage.contains("SHA-1", ignoreCase = true) -> {
                "Assinatura SHA-1 do APK não autorizada no projeto Firebase."
            }
            rawMessage.contains("access_denied", ignoreCase = true) ||
                rawMessage.contains("403", ignoreCase = true) -> {
                "Acesso negado pelo Google. Se o app estiver em modo de teste na tela de consentimento, adicione seu e-mail aos usuários de teste."
            }
            rawMessage.contains("redirect_uri_mismatch", ignoreCase = true) -> {
                "URI de redirecionamento OAuth do Firebase não autorizada no cliente Web do Google Cloud."
            }
            else -> rawMessage.takeIf { it.isNotBlank() }
                ?: "Não foi possível concluir o login com o Google pelo navegador."
        }
    }
}

class GoogleOAuthCoordinator(
    context: Context,
    private val auth: FirebaseAuth,
    private val authRepository: AuthRepository
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _uiState = MutableStateFlow<OAuthUiState>(OAuthUiState.Idle)
    val uiState: StateFlow<OAuthUiState> = _uiState.asStateFlow()

    private var attachedTaskIdentity: Int? = null

    fun checkPendingAuthResult() {
        if (auth.currentUser != null) {
            if (_uiState.value !is OAuthUiState.Idle) {
                _uiState.value = OAuthUiState.Idle
            }
            return
        }

        val pendingTask = auth.pendingAuthResult ?: return
        _uiState.value = OAuthUiState.ExchangingTokens
        attachAuthTask(pendingTask)
    }

    fun startGoogleOAuth(activity: Activity) {
        val current = _uiState.value
        if (current is OAuthUiState.AwaitingBrowser || current is OAuthUiState.ExchangingTokens) {
            return
        }

        val pendingTask = auth.pendingAuthResult
        if (pendingTask != null) {
            _uiState.value = OAuthUiState.ExchangingTokens
            attachAuthTask(pendingTask)
            return
        }

        if (!GoogleOAuthProviderConfig.hasCompatibleBrowser(activity)) {
            _uiState.value = OAuthUiState.Error(
                "Nenhum navegador compatível encontrado neste dispositivo para concluir o login."
            )
            return
        }

        _uiState.value = OAuthUiState.AwaitingBrowser
        try {
            val provider = GoogleOAuthProviderConfig.buildProvider(auth)
            val task = auth.startActivityForSignInWithProvider(activity, provider)
            attachAuthTask(task)
        } catch (e: ActivityNotFoundException) {
            Log.e("GoogleOAuth", "Nenhum navegador disponível para autenticação", e)
            _uiState.value = OAuthUiState.Error(
                "Nenhum navegador compatível encontrado neste dispositivo para concluir o login."
            )
        } catch (e: Exception) {
            Log.e("GoogleOAuth", "Falha ao iniciar fluxo OAuth com navegador", e)
            _uiState.value = GoogleOAuthProviderConfig.mapAuthException(e)
        }
    }

    private fun attachAuthTask(task: Task<AuthResult>) {
        val taskId = System.identityHashCode(task)
        if (attachedTaskIdentity == taskId) {
            return
        }
        attachedTaskIdentity = taskId

        task.addOnSuccessListener { authResult ->
            if (attachedTaskIdentity == taskId) {
                attachedTaskIdentity = null
            }
            scope.launch {
                _uiState.value = OAuthUiState.ExchangingTokens
                val user = authResult.user
                if (user == null) {
                    _uiState.value = OAuthUiState.Error(
                        "Sessão Firebase não foi criada após o retorno do navegador."
                    )
                    return@launch
                }

                val profileMap = authResult.additionalUserInfo?.profile
                val preferredName = profileMap?.get("name") as? String
                val preferredAvatar = profileMap?.get("picture") as? String

                val initResult = authRepository.ensureUserInitialized(
                    user = user,
                    preferredDisplayName = preferredName,
                    preferredAvatarUrl = preferredAvatar
                )
                initResult.onFailure { err ->
                    Log.w("GoogleOAuth", "User profile initialization warning after OAuth", err)
                }
                _uiState.value = OAuthUiState.Idle
            }
        }.addOnFailureListener { exception ->
            if (attachedTaskIdentity == taskId) {
                attachedTaskIdentity = null
            }
            Log.e("GoogleOAuth", "Firebase browser OAuth failed", exception)
            _uiState.value = GoogleOAuthProviderConfig.mapAuthException(exception)
        }
    }

    fun clearError() {
        if (_uiState.value is OAuthUiState.Error) {
            _uiState.value = OAuthUiState.Idle
        }
    }
}
