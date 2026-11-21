package com.example.data.remote

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import com.example.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

@Serializable
data class PendingOAuthSession(
    val state: String,
    val nonce: String,
    val codeVerifier: String,
    val clientId: String,
    val redirectUri: String,
    val redirectScheme: String,
    val usedCustomTabsFallback: Boolean,
    val createdAtMillis: Long
)

data class GoogleIdTokenClaims(
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val name: String?,
    val picture: String?
)

sealed class OAuthRedirectParseResult {
    data class Success(val code: String) : OAuthRedirectParseResult()
    data class Cancelled(val reason: String) : OAuthRedirectParseResult()
    data class Error(val message: String) : OAuthRedirectParseResult()
}

sealed class OAuthUiState {
    data object Idle : OAuthUiState()
    data class AwaitingBrowser(val usedCustomTabFallback: Boolean) : OAuthUiState()
    data object ExchangingTokens : OAuthUiState()
    data class Error(val message: String) : OAuthUiState()
}

object GoogleOAuthProtocol {
    const val AUTHORIZATION_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
    const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"
    const val REDIRECT_PATH = "/oauth2redirect"
    private const val OIDC_SCOPE = "openid email profile"
    private val secureRandom = SecureRandom()
    private val json = Json { ignoreUnknownKeys = true }

    private val BASE64_URL_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray()

    fun generateCodeVerifier(): String {
        val bytes = ByteArray(64)
        secureRandom.nextBytes(bytes)
        return base64UrlEncodeNoPadding(bytes)
    }

    fun computeCodeChallengeS256(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(codeVerifier.toByteArray(Charsets.US_ASCII))
        return base64UrlEncodeNoPadding(digest)
    }

    fun generateOpaqueToken(byteCount: Int = 32): String {
        val bytes = ByteArray(byteCount)
        secureRandom.nextBytes(bytes)
        return base64UrlEncodeNoPadding(bytes)
    }

    fun deriveRedirectScheme(androidClientId: String): String {
        val prefix = androidClientId.trim().substringBefore('.')
        return "com.googleusercontent.apps.$prefix"
    }

    fun deriveRedirectUri(androidClientId: String): String {
        return "${deriveRedirectScheme(androidClientId)}:$REDIRECT_PATH"
    }

    fun buildAuthorizationUrl(
        clientId: String,
        redirectUri: String,
        state: String,
        nonce: String,
        codeChallenge: String
    ): String {
        val params = listOf(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to OIDC_SCOPE,
            "code_challenge" to codeChallenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "nonce" to nonce,
            "prompt" to "select_account"
        )
        val encodedQuery = params.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
        return "$AUTHORIZATION_ENDPOINT?$encodedQuery"
    }

    fun parseAndValidateRedirect(
        uriString: String?,
        expectedScheme: String,
        expectedPath: String = REDIRECT_PATH,
        expectedState: String
    ): OAuthRedirectParseResult {
        if (uriString.isNullOrBlank()) {
            return OAuthRedirectParseResult.Error("Resposta de redirecionamento vazia.")
        }
        val parsed = runCatching { URI(uriString) }.getOrNull()
            ?: return OAuthRedirectParseResult.Error("URI de retorno OAuth inválida.")

        if (!parsed.scheme.equals(expectedScheme, ignoreCase = true) || parsed.path != expectedPath) {
            return OAuthRedirectParseResult.Error("URI de redirecionamento não autorizada.")
        }

        val queryParams = parseQueryParams(parsed.rawQuery)
        val oauthError = queryParams["error"]
        if (!oauthError.isNullOrBlank()) {
            return if (oauthError == "access_denied") {
                OAuthRedirectParseResult.Cancelled("Login com Google cancelado pelo usuário.")
            } else {
                val desc = queryParams["error_description"]?.takeIf { it.isNotBlank() }
                OAuthRedirectParseResult.Error(desc ?: "Erro OAuth do Google: $oauthError")
            }
        }

        val returnedState = queryParams["state"].orEmpty()
        if (returnedState.isBlank() ||
            !MessageDigest.isEqual(
                returnedState.toByteArray(Charsets.UTF_8),
                expectedState.toByteArray(Charsets.UTF_8)
            )
        ) {
            return OAuthRedirectParseResult.Error(
                "Falha na validação de segurança (parâmetro state inválido)."
            )
        }

        val code = queryParams["code"].orEmpty()
        if (code.isBlank()) {
            return OAuthRedirectParseResult.Error("Código de autorização não retornado pelo Google.")
        }
        return OAuthRedirectParseResult.Success(code)
    }

    fun validateGoogleIdTokenPayload(
        idToken: String,
        expectedClientId: String,
        expectedNonce: String,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000L
    ): Result<GoogleIdTokenClaims> {
        val parts = idToken.split('.')
        if (parts.size != 3 || parts.any { it.isBlank() }) {
            return Result.failure(IllegalStateException("ID Token OpenID Connect malformado."))
        }
        val payloadBytes = runCatching { base64UrlDecode(parts[1]) }.getOrNull()
            ?: return Result.failure(IllegalStateException("Falha ao decodificar ID Token."))
        val payloadObj = runCatching {
            json.decodeFromString<JsonObject>(payloadBytes.toString(Charsets.UTF_8))
        }.getOrNull()
            ?: return Result.failure(IllegalStateException("Payload JSON do ID Token inválido."))

        val iss = payloadObj["iss"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (iss != "https://accounts.google.com" && iss != "accounts.google.com") {
            return Result.failure(IllegalStateException("Emissor do ID Token não confiável ($iss)."))
        }

        val aud = payloadObj["aud"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val azp = payloadObj["azp"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (aud != expectedClientId && azp != expectedClientId) {
            return Result.failure(IllegalStateException("Audience do ID Token não corresponde ao app."))
        }

        val nonce = payloadObj["nonce"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (nonce.isBlank() || !MessageDigest.isEqual(
                nonce.toByteArray(Charsets.UTF_8),
                expectedNonce.toByteArray(Charsets.UTF_8)
            )
        ) {
            return Result.failure(IllegalStateException("Nonce do ID Token inválido."))
        }

        val exp = payloadObj["exp"]?.jsonPrimitive?.longOrNull ?: 0L
        if (exp <= nowEpochSeconds - 60L) {
            return Result.failure(IllegalStateException("ID Token OpenID Connect expirado."))
        }

        val sub = payloadObj["sub"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sub.isBlank()) {
            return Result.failure(IllegalStateException("Identificador do usuário (sub) ausente no ID Token."))
        }

        val email = payloadObj["email"]?.jsonPrimitive?.contentOrNull
        val emailVerified = payloadObj["email_verified"]?.jsonPrimitive?.booleanOrNull ?: false
        val name = payloadObj["name"]?.jsonPrimitive?.contentOrNull
        val picture = payloadObj["picture"]?.jsonPrimitive?.contentOrNull

        return Result.success(
            GoogleIdTokenClaims(
                subject = sub,
                email = email,
                emailVerified = emailVerified,
                name = name,
                picture = picture
            )
        )
    }

    private fun parseQueryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = linkedMapOf<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isBlank()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            result[URLDecoder.decode(key, "UTF-8")] = URLDecoder.decode(value, "UTF-8")
        }
        return result
    }

    fun base64UrlEncodeNoPadding(input: ByteArray): String {
        if (input.isEmpty()) return ""
        val sb = StringBuilder((input.size * 4 + 2) / 3)
        var i = 0
        while (i < input.size) {
            val b0 = input[i++].toInt() and 0xFF
            val hasB1 = i < input.size
            val b1 = if (hasB1) input[i++].toInt() and 0xFF else 0
            val hasB2 = i < input.size
            val b2 = if (hasB2) input[i++].toInt() and 0xFF else 0

            val triple = (b0 shl 16) or (b1 shl 8) or b2
            sb.append(BASE64_URL_ALPHABET[(triple ushr 18) and 0x3F])
            sb.append(BASE64_URL_ALPHABET[(triple ushr 12) and 0x3F])
            if (hasB1) sb.append(BASE64_URL_ALPHABET[(triple ushr 6) and 0x3F])
            if (hasB2) sb.append(BASE64_URL_ALPHABET[triple and 0x3F])
        }
        return sb.toString()
    }

    fun base64UrlDecode(encoded: String): ByteArray {
        val clean = encoded.trimEnd('=')
        if (clean.isEmpty()) return ByteArray(0)
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bitsLeft = 0
        for (ch in clean) {
            val val6 = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '-' -> 62
                '_' -> 63
                else -> throw IllegalArgumentException("Invalid Base64URL character: $ch")
            }
            buffer = (buffer shl 6) or val6
            bitsLeft += 6
            if (bitsLeft >= 8) {
                bitsLeft -= 8
                out.add(((buffer ushr bitsLeft) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}

class OAuthSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun save(session: PendingOAuthSession) {
        prefs.edit().putString(KEY_PENDING, json.encodeToString(session)).commit()
    }

    fun peek(nowMillis: Long = System.currentTimeMillis()): PendingOAuthSession? {
        val raw = prefs.getString(KEY_PENDING, null) ?: return null
        val parsed = runCatching { json.decodeFromString<PendingOAuthSession>(raw) }.getOrNull()
        if (parsed == null || nowMillis - parsed.createdAtMillis > MAX_AGE_MILLIS) {
            clear()
            return null
        }
        return parsed
    }

    fun consume(nowMillis: Long = System.currentTimeMillis()): PendingOAuthSession? {
        val session = peek(nowMillis)
        clear()
        return session
    }

    fun clear() {
        prefs.edit().remove(KEY_PENDING).commit()
    }

    companion object {
        private const val PREFS_NAME = "talkback_oauth_pending"
        private const val KEY_PENDING = "pending_oauth_session"
        private const val MAX_AGE_MILLIS = 15L * 60L * 1000L
    }
}

class GoogleOAuthCoordinator(
    context: Context,
    private val authRepository: AuthRepository
) {
    private val appContext = context.applicationContext
    private val sessionStore = OAuthSessionStore(appContext)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val exchangeMutex = Mutex()

    private val _uiState = MutableStateFlow<OAuthUiState>(OAuthUiState.Idle)
    val uiState: StateFlow<OAuthUiState> = _uiState.asStateFlow()

    private var authTabLauncher: ActivityResultLauncher<Intent>? = null

    fun registerAuthTabLauncher(activity: ComponentActivity) {
        authTabLauncher = AuthTabIntent.registerActivityResultLauncher(activity) { result ->
            onAuthTabResult(result)
        }
    }

    fun startGoogleOAuth(activity: Activity) {
        val current = _uiState.value
        if (current is OAuthUiState.ExchangingTokens || current is OAuthUiState.AwaitingBrowser) {
            return
        }

        val androidClientId = runCatching {
            appContext.getString(R.string.google_android_oauth_client_id).trim()
        }.getOrDefault("")
        val redirectScheme = runCatching {
            appContext.getString(R.string.google_oauth_redirect_scheme).trim()
        }.getOrDefault("")

        if (androidClientId.isBlank() || redirectScheme.isBlank()) {
            _uiState.value = OAuthUiState.Error(
                "Credencial OAuth Android não configurada em google-services.json."
            )
            return
        }

        val codeVerifier = GoogleOAuthProtocol.generateCodeVerifier()
        val codeChallenge = GoogleOAuthProtocol.computeCodeChallengeS256(codeVerifier)
        val state = GoogleOAuthProtocol.generateOpaqueToken()
        val nonce = GoogleOAuthProtocol.generateOpaqueToken()
        val redirectUri = "$redirectScheme:${GoogleOAuthProtocol.REDIRECT_PATH}"

        val authUrl = GoogleOAuthProtocol.buildAuthorizationUrl(
            clientId = androidClientId,
            redirectUri = redirectUri,
            state = state,
            nonce = nonce,
            codeChallenge = codeChallenge
        )
        val authUri = Uri.parse(authUrl)

        val browserPackage = runCatching {
            CustomTabsClient.getPackageName(activity, emptyList())
        }.getOrNull()

        val supportsAuthTab = !browserPackage.isNullOrBlank() && runCatching {
            CustomTabsClient.isAuthTabSupported(activity, browserPackage)
        }.getOrDefault(false) && authTabLauncher != null

        val session = PendingOAuthSession(
            state = state,
            nonce = nonce,
            codeVerifier = codeVerifier,
            clientId = androidClientId,
            redirectUri = redirectUri,
            redirectScheme = redirectScheme,
            usedCustomTabsFallback = !supportsAuthTab,
            createdAtMillis = System.currentTimeMillis()
        )
        sessionStore.save(session)

        try {
            if (supportsAuthTab) {
                _uiState.value = OAuthUiState.AwaitingBrowser(usedCustomTabFallback = false)
                val authTabIntent = AuthTabIntent.Builder().build()
                authTabIntent.launch(authTabLauncher!!, authUri, redirectScheme)
            } else if (!browserPackage.isNullOrBlank()) {
                _uiState.value = OAuthUiState.AwaitingBrowser(usedCustomTabFallback = true)
                val customTabsIntent = CustomTabsIntent.Builder()
                    .setShowTitle(true)
                    .build()
                customTabsIntent.intent.setPackage(browserPackage)
                customTabsIntent.launchUrl(activity, authUri)
            } else {
                _uiState.value = OAuthUiState.AwaitingBrowser(usedCustomTabFallback = true)
                val browserIntent = Intent(Intent.ACTION_VIEW, authUri)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                activity.startActivity(browserIntent)
            }
        } catch (e: ActivityNotFoundException) {
            Log.e("GoogleOAuth", "Nenhum navegador disponível para autenticação", e)
            sessionStore.clear()
            _uiState.value = OAuthUiState.Error(
                "Nenhum navegador compatível encontrado neste dispositivo para concluir o login."
            )
        } catch (e: Exception) {
            Log.e("GoogleOAuth", "Falha ao abrir navegador para OAuth", e)
            sessionStore.clear()
            _uiState.value = OAuthUiState.Error(
                e.localizedMessage ?: "Não foi possível abrir o navegador seguro."
            )
        }
    }

    private fun onAuthTabResult(result: AuthTabIntent.AuthResult) {
        when (result.resultCode) {
            AuthTabIntent.RESULT_OK -> handleRedirectUri(result.resultUri)
            AuthTabIntent.RESULT_CANCELED -> {
                sessionStore.clear()
                if (_uiState.value !is OAuthUiState.ExchangingTokens) {
                    _uiState.value = OAuthUiState.Idle
                }
            }
            AuthTabIntent.RESULT_VERIFICATION_FAILED,
            AuthTabIntent.RESULT_VERIFICATION_TIMED_OUT -> {
                sessionStore.clear()
                _uiState.value = OAuthUiState.Error(
                    "Falha ao verificar o redirecionamento seguro do navegador."
                )
            }
            else -> {
                sessionStore.clear()
                _uiState.value = OAuthUiState.Error(
                    "Autenticação via navegador interrompida."
                )
            }
        }
    }

    fun handleRedirectUri(uri: Uri?) {
        if (uri == null) return
        val expectedScheme = runCatching {
            appContext.getString(R.string.google_oauth_redirect_scheme).trim()
        }.getOrDefault("")
        if (!uri.scheme.equals(expectedScheme, ignoreCase = true) ||
            uri.path != GoogleOAuthProtocol.REDIRECT_PATH
        ) {
            return
        }

        scope.launch {
            exchangeMutex.withLock {
                val pending = sessionStore.consume() ?: return@withLock
                val parsed = GoogleOAuthProtocol.parseAndValidateRedirect(
                    uriString = uri.toString(),
                    expectedScheme = pending.redirectScheme,
                    expectedState = pending.state
                )
                when (parsed) {
                    is OAuthRedirectParseResult.Cancelled -> {
                        _uiState.value = OAuthUiState.Idle
                    }
                    is OAuthRedirectParseResult.Error -> {
                        _uiState.value = OAuthUiState.Error(parsed.message)
                    }
                    is OAuthRedirectParseResult.Success -> {
                        _uiState.value = OAuthUiState.ExchangingTokens
                        val result = authRepository.completeGoogleOAuthCodeExchange(
                            authorizationCode = parsed.code,
                            pendingSession = pending
                        )
                        _uiState.value = result.fold(
                            onSuccess = { OAuthUiState.Idle },
                            onFailure = { err ->
                                OAuthUiState.Error(
                                    err.localizedMessage
                                        ?: "Não foi possível validar o login com Google."
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun onHostActivityResumed() {
        val current = _uiState.value
        if (current is OAuthUiState.AwaitingBrowser && current.usedCustomTabFallback) {
            scope.launch {
                delay(450L)
                val afterWait = _uiState.value
                if (afterWait is OAuthUiState.AwaitingBrowser &&
                    afterWait.usedCustomTabFallback &&
                    !exchangeMutex.isLocked
                ) {
                    sessionStore.clear()
                    _uiState.value = OAuthUiState.Idle
                }
            }
        }
    }

    fun clearError() {
        if (_uiState.value is OAuthUiState.Error) {
            _uiState.value = OAuthUiState.Idle
        }
    }
}
