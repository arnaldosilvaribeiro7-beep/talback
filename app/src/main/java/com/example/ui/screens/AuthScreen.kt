package com.example.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.remote.EmailAuthValidator
import com.example.ui.theme.LocalTalkbackColors

enum class EmailAuthMode {
    LOGIN,
    SIGN_UP
}

@Composable
fun AuthScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onClearError: () -> Unit,
    onLoginSubmit: (email: String, password: String) -> Unit,
    onSignUpSubmit: (email: String, password: String) -> Unit
) {
    val colors = LocalTalkbackColors.current
    val focusManager = LocalFocusManager.current

    var mode by rememberSaveable { mutableStateOf(EmailAuthMode.LOGIN) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var localValidationError by rememberSaveable { mutableStateOf<String?>(null) }

    fun submit() {
        focusManager.clearFocus()
        val validationError = when (mode) {
            EmailAuthMode.LOGIN -> EmailAuthValidator.validateLogin(email, password)
            EmailAuthMode.SIGN_UP -> EmailAuthValidator.validateSignUp(email, password)
        }
        if (validationError != null) {
            localValidationError = validationError
            return
        }
        localValidationError = null
        onClearError()
        when (mode) {
            EmailAuthMode.LOGIN -> onLoginSubmit(email, password)
            EmailAuthMode.SIGN_UP -> onSignUpSubmit(email, password)
        }
    }

    val displayedError = localValidationError ?: errorMessage

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
                        SingleChoiceSegmentedButtonRow(
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            SegmentedButton(
                                selected = mode == EmailAuthMode.LOGIN,
                                onClick = {
                                    if (!isLoading) {
                                        mode = EmailAuthMode.LOGIN
                                        localValidationError = null
                                        onClearError()
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                enabled = !isLoading,
                                modifier = Modifier.testTag("auth_tab_login")
                            ) {
                                Text("Fazer login", fontWeight = FontWeight.Medium)
                            }
                            SegmentedButton(
                                selected = mode == EmailAuthMode.SIGN_UP,
                                onClick = {
                                    if (!isLoading) {
                                        mode = EmailAuthMode.SIGN_UP
                                        localValidationError = null
                                        onClearError()
                                    }
                                },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                enabled = !isLoading,
                                modifier = Modifier.testTag("auth_tab_signup")
                            ) {
                                Text("Criar conta", fontWeight = FontWeight.Medium)
                            }
                        }

                        Text(
                            text = if (mode == EmailAuthMode.LOGIN) {
                                "Informe seu e-mail e senha cadastrados para entrar na sua conta."
                            } else {
                                "Informe um e-mail válido (ex.: example@gmail.com) e uma senha com pelo menos 6 caracteres."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                localValidationError = null
                                onClearError()
                            },
                            label = { Text("E-mail") },
                            placeholder = { Text("example@gmail.com") },
                            leadingIcon = {
                                Icon(Icons.Default.Email, contentDescription = null)
                            },
                            singleLine = true,
                            enabled = !isLoading,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_email_input")
                        )

                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                localValidationError = null
                                onClearError()
                            },
                            label = { Text("Senha") },
                            placeholder = {
                                Text(
                                    if (mode == EmailAuthMode.SIGN_UP) {
                                        "Mínimo de 6 caracteres"
                                    } else {
                                        "Digite sua senha"
                                    }
                                )
                            },
                            leadingIcon = {
                                Icon(Icons.Default.Lock, contentDescription = null)
                            },
                            trailingIcon = {
                                IconButton(
                                    onClick = { passwordVisible = !passwordVisible },
                                    modifier = Modifier.testTag("auth_password_visibility_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (passwordVisible) {
                                            Icons.Default.VisibilityOff
                                        } else {
                                            Icons.Default.Visibility
                                        },
                                        contentDescription = if (passwordVisible) {
                                            "Ocultar senha"
                                        } else {
                                            "Mostrar senha"
                                        }
                                    )
                                }
                            },
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            singleLine = true,
                            enabled = !isLoading,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = { submit() }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("auth_password_input")
                        )

                        if (!displayedError.isNullOrBlank()) {
                            Text(
                                text = displayedError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("auth_error_text")
                            )
                        }

                        Spacer(modifier = Modifier.height(2.dp))

                        Button(
                            onClick = { submit() },
                            enabled = !isLoading,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("auth_submit_button")
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Text(
                                    text = if (mode == EmailAuthMode.LOGIN) "Entrar" else "Criar conta",
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (mode == EmailAuthMode.SIGN_UP) {
                            Text(
                                text = "Apenas 2 contas podem ser cadastradas no total.",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }
    }
}
