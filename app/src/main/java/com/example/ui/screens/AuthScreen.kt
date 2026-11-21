package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import com.example.R
import com.example.data.remote.AuthRepository
import com.example.data.remote.GmailAuthValidator
import com.example.data.remote.GmailOtpChallenge
import com.example.ui.theme.LocalTalkbackColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuthScreen(
    authRepository: AuthRepository,
    onAuthSuccess: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }
    val colors = LocalTalkbackColors.current

    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var infoMessage by remember { mutableStateOf<String?>(null) }

    var isCreateAccount by remember { mutableStateOf(false) }
    var emailInput by remember { mutableStateOf("") }
    var usernameInput by remember { mutableStateOf("") }
    var displayNameInput by remember { mutableStateOf("") }
    var otpCodeInput by remember { mutableStateOf("") }
    var activeChallenge by remember { mutableStateOf<GmailOtpChallenge?>(null) }
    var resendCooldownSeconds by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        authRepository.attemptAutoSignIn(
            context = context,
            credentialManager = credentialManager,
            scope = scope,
            onAuthSuccess = onAuthSuccess,
            onUnauthenticated = {}
        )
    }

    LaunchedEffect(resendCooldownSeconds) {
        if (resendCooldownSeconds > 0) {
            delay(1000L)
            resendCooldownSeconds -= 1
        }
    }

    fun requestOtpCode() {
        isLoading = true
        errorMessage = null
        infoMessage = null
        scope.launch {
            authRepository.requestGmailVerificationCode(
                rawEmail = emailInput,
                rawUsername = usernameInput.takeIf { isCreateAccount },
                rawDisplayName = displayNameInput.takeIf { isCreateAccount },
                isNewAccount = isCreateAccount
            ).onSuccess { challenge ->
                isLoading = false
                activeChallenge = challenge
                otpCodeInput = ""
                resendCooldownSeconds = challenge.resendAfterSeconds
                infoMessage = "Código de 6 dígitos enviado para ${challenge.email} (válido por 10 min)."
            }.onFailure { err ->
                isLoading = false
                errorMessage = err.message ?: "Não foi possível enviar o código de verificação."
            }
        }
    }

    Scaffold(
        containerColor = colors.railBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
                .padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_talkback_icon_fg),
                    contentDescription = "Logotipo do Talkback",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(22.dp))
                )

                Text(
                    text = "Boas-vindas ao Talkback",
                    style = MaterialTheme.typography.headlineMedium,
                    color = colors.textPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "Comunidades, canais de texto e voz, chamadas WebRTC e compartilhamento de tela em um só lugar.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center
                )

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = colors.sidebarBackground),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(
                            onClick = {
                                isLoading = true
                                errorMessage = null
                                infoMessage = null
                                authRepository.onGoogleSignInClicked(
                                    context = context,
                                    credentialManager = credentialManager,
                                    scope = scope,
                                    onAuthSuccess = {
                                        isLoading = false
                                        onAuthSuccess()
                                    },
                                    onAuthError = { msg ->
                                        isLoading = false
                                        errorMessage = msg
                                    },
                                    onAuthCancelled = {
                                        isLoading = false
                                    }
                                )
                            },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("google_sign_in_button")
                        ) {
                            if (isLoading && activeChallenge == null) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.AccountCircle, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Entrar com Google", fontWeight = FontWeight.SemiBold)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            HorizontalDivider(modifier = Modifier.weight(1f), color = colors.textMuted.copy(alpha = 0.25f))
                            Text(
                                text = "OU VIA @GMAIL.COM",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textMuted
                            )
                            HorizontalDivider(modifier = Modifier.weight(1f), color = colors.textMuted.copy(alpha = 0.25f))
                        }

                        if (activeChallenge == null) {
                            SingleChoiceSegmentedButtonRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_mode_selector")
                            ) {
                                SegmentedButton(
                                    selected = !isCreateAccount,
                                    onClick = {
                                        isCreateAccount = false
                                        errorMessage = null
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    enabled = !isLoading
                                ) {
                                    Text("Entrar")
                                }
                                SegmentedButton(
                                    selected = isCreateAccount,
                                    onClick = {
                                        isCreateAccount = true
                                        errorMessage = null
                                    },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    enabled = !isLoading
                                ) {
                                    Text("Criar conta")
                                }
                            }

                            val emailTrimmed = emailInput.trim()
                            val emailInvalid = emailTrimmed.isNotEmpty() && !GmailAuthValidator.isValidGmail(emailTrimmed)

                            OutlinedTextField(
                                value = emailInput,
                                onValueChange = { emailInput = it },
                                label = { Text("E-mail (@gmail.com)") },
                                placeholder = { Text("seunome@gmail.com") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                                singleLine = true,
                                isError = emailInvalid,
                                supportingText = {
                                    Text(
                                        if (emailInvalid) "Somente endereços @gmail.com são aceitos."
                                        else "Enviaremos um código de 6 dígitos para confirmar."
                                    )
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("gmail_email_input")
                            )

                            AnimatedVisibility(visible = isCreateAccount) {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedTextField(
                                        value = usernameInput,
                                        onValueChange = { usernameInput = it.lowercase() },
                                        label = { Text("Nome de usuário (@usuário)") },
                                        placeholder = { Text("ex: maria.silva") },
                                        leadingIcon = { Icon(Icons.Default.AlternateEmail, contentDescription = null) },
                                        singleLine = true,
                                        enabled = !isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("gmail_username_input")
                                    )
                                    OutlinedTextField(
                                        value = displayNameInput,
                                        onValueChange = { displayNameInput = it },
                                        label = { Text("Nome de exibição (opcional)") },
                                        placeholder = { Text("Como você aparece nos chats") },
                                        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null) },
                                        singleLine = true,
                                        enabled = !isLoading,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("gmail_displayname_input")
                                    )
                                }
                            }

                            OutlinedButton(
                                onClick = { requestOtpCode() },
                                enabled = !isLoading &&
                                    GmailAuthValidator.isValidGmail(emailInput) &&
                                    (!isCreateAccount || GmailAuthValidator.normalizeUsername(usernameInput) != null),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("send_otp_button")
                            ) {
                                Icon(Icons.Default.MarkEmailRead, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isCreateAccount) "Enviar código para criar conta"
                                    else "Enviar código de 6 dígitos"
                                )
                            }
                        } else {
                            val challenge = activeChallenge!!
                            Text(
                                text = "Confirme o código de 6 dígitos enviado para ${challenge.email}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.textPrimary,
                                fontWeight = FontWeight.Medium
                            )

                            OutlinedTextField(
                                value = otpCodeInput,
                                onValueChange = { input ->
                                    val digits = input.filter { it.isDigit() }.take(6)
                                    otpCodeInput = digits
                                },
                                label = { Text("Código de 6 dígitos") },
                                placeholder = { Text("000000") },
                                leadingIcon = { Icon(Icons.Default.Pin, contentDescription = null) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = !isLoading,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("otp_code_input")
                            )

                            Button(
                                onClick = {
                                    isLoading = true
                                    errorMessage = null
                                    scope.launch {
                                        authRepository.verifyGmailCodeAndSignIn(
                                            challengeId = challenge.challengeId,
                                            rawEmail = challenge.email,
                                            rawCode = otpCodeInput,
                                            rawUsername = usernameInput.takeIf { isCreateAccount },
                                            rawDisplayName = displayNameInput.takeIf { isCreateAccount }
                                        ).onSuccess {
                                            isLoading = false
                                            onAuthSuccess()
                                        }.onFailure { err ->
                                            isLoading = false
                                            errorMessage = err.message ?: "Código inválido ou expirado."
                                        }
                                    }
                                },
                                enabled = !isLoading && GmailAuthValidator.isValidSixDigitCode(otpCodeInput),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("verify_otp_button")
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        color = Color.White,
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text("Confirmar código e entrar", fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        activeChallenge = null
                                        otpCodeInput = ""
                                        errorMessage = null
                                        infoMessage = null
                                    },
                                    enabled = !isLoading
                                ) {
                                    Text("Alterar e-mail")
                                }
                                TextButton(
                                    onClick = { requestOtpCode() },
                                    enabled = !isLoading && resendCooldownSeconds == 0
                                ) {
                                    Text(
                                        if (resendCooldownSeconds > 0) "Reenviar (${resendCooldownSeconds}s)"
                                        else "Reenviar código"
                                    )
                                }
                            }
                        }

                        if (!infoMessage.isNullOrBlank()) {
                            Text(
                                text = infoMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.online
                            )
                        }

                        if (!errorMessage.isNullOrBlank()) {
                            Text(
                                text = errorMessage!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }
}
