package com.gothwad.mail.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gothwad.mail.auth.ApiNativeSignUpRequest
import com.gothwad.mail.ui.state.NativeAuthStep
import com.gothwad.mail.ui.state.NativeAuthUiState

/**
 * 100% native Jetpack Compose sign-in, sign-up, two-factor and password-recovery sheet.
 *
 * It talks to `https://accounts.gothwadtech.com/api/mobile/auth/{endpoint}` through the view model, so
 * Bansify Email can sign in on a device with no browser at all. The browser flow stays available
 * from the same screen as the secondary "Sign in with browser" button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeAuthSheet(
    state: NativeAuthUiState,
    onDismiss: () -> Unit,
    onSubmitIdentifier: (String) -> Unit,
    onSubmitPassword: (String) -> Unit,
    onSubmitMfa: (String) -> Unit,
    onSubmitSignUp: (ApiNativeSignUpRequest) -> Unit,
    onForgotPassword: (String) -> Unit,
    onResetPassword: (String, String) -> Unit,
    onSwitchToSignUp: () -> Unit,
    onSwitchToSignIn: () -> Unit,
    onUpdate: ((NativeAuthUiState) -> NativeAuthUiState) -> Unit,
    onBrowserSignIn: () -> Unit,
) {
    if (!state.visible) return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp, vertical = 8.dp)
                .testTag("native_auth_sheet"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.Lock,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = when (state.step) {
                    NativeAuthStep.IDENTIFIER -> "Sign in to Bansify"
                    NativeAuthStep.PASSWORD -> "Enter your password"
                    NativeAuthStep.MFA -> "Two-step verification"
                    NativeAuthStep.SIGN_UP -> "Create a Bansify Account"
                    NativeAuthStep.RECOVERY -> "Reset your password"
                },
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = state.hint ?: when (state.step) {
                    NativeAuthStep.IDENTIFIER -> "Use your username, email or phone number"
                    NativeAuthStep.PASSWORD -> "Your password never leaves this device unencrypted"
                    NativeAuthStep.MFA -> "Enter the 6-digit code from your authenticator app"
                    NativeAuthStep.SIGN_UP -> "Your new account is created on Bansify Accounts"
                    NativeAuthStep.RECOVERY -> "We will email a reset link to your recovery address"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(18.dp))

            when (state.step) {
                NativeAuthStep.IDENTIFIER -> IdentifierStep(state, onUpdate, onSubmitIdentifier)
                NativeAuthStep.PASSWORD -> PasswordStep(state, onUpdate, onSubmitPassword)
                NativeAuthStep.MFA -> MfaStep(state, onUpdate, onSubmitMfa)
                NativeAuthStep.SIGN_UP -> SignUpStep(state, onSubmitSignUp)
                NativeAuthStep.RECOVERY -> RecoveryStep(state, onSubmitIdentifier, onResetPassword)
            }

            if (state.error != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.testTag("native_auth_error"),
                )
            }

            if (state.busy) {
                Spacer(Modifier.height(14.dp))
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            }

            Spacer(Modifier.height(12.dp))

            when (state.step) {
                NativeAuthStep.IDENTIFIER -> {
                    TextButton(onClick = onSwitchToSignUp, enabled = !state.busy) {
                        Text("Create account")
                    }
                    TextButton(
                        onClick = { onUpdate { it.copy(step = NativeAuthStep.RECOVERY, error = null) } },
                        enabled = !state.busy,
                    ) {
                        Text("Forgot password?")
                    }
                }

                NativeAuthStep.PASSWORD -> {
                    TextButton(
                        onClick = { onForgotPassword(state.identifier) },
                        enabled = !state.busy,
                    ) {
                        Text("Forgot password?")
                    }
                    TextButton(
                        onClick = { onUpdate { it.copy(step = NativeAuthStep.IDENTIFIER, error = null) } },
                        enabled = !state.busy,
                    ) {
                        Text("Use a different account")
                    }
                }

                NativeAuthStep.MFA -> TextButton(
                    onClick = { onUpdate { it.copy(step = NativeAuthStep.PASSWORD, error = null, challenge = null) } },
                    enabled = !state.busy,
                ) {
                    Text("Back")
                }

                NativeAuthStep.SIGN_UP -> TextButton(onClick = onSwitchToSignIn, enabled = !state.busy) {
                    Text("I already have an account")
                }

                NativeAuthStep.RECOVERY -> TextButton(
                    onClick = { onUpdate { it.copy(step = NativeAuthStep.IDENTIFIER, error = null, hint = null) } },
                    enabled = !state.busy,
                ) {
                    Text("Back to sign in")
                }
            }

            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = onBrowserSignIn,
                enabled = !state.busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .testTag("sign_in_with_browser_button"),
                shape = RoundedCornerShape(23.dp),
            ) {
                Text("Sign in with browser")
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IdentifierStep(
    state: NativeAuthUiState,
    onUpdate: ((NativeAuthUiState) -> NativeAuthUiState) -> Unit,
    onSubmit: (String) -> Unit,
) {
    OutlinedTextField(
        value = state.identifier,
        onValueChange = { value -> onUpdate { it.copy(identifier = value) } },
        label = { Text("Username, email or phone") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Email),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("native_auth_identifier_field"),
    )
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { onSubmit(state.identifier) },
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("native_auth_continue_button"),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text("Continue")
    }
}

@Composable
private fun PasswordStep(
    state: NativeAuthUiState,
    onUpdate: ((NativeAuthUiState) -> NativeAuthUiState) -> Unit,
    onSubmit: (String) -> Unit,
) {
    var password by remember(state.identifier) { mutableStateOf("") }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("Password") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Password),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("native_auth_password_field"),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        text = state.identifier,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { onSubmit(password) },
        enabled = !state.busy,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("native_auth_sign_in_button"),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text("Sign in")
    }
}

@Composable
private fun MfaStep(
    state: NativeAuthUiState,
    onUpdate: ((NativeAuthUiState) -> NativeAuthUiState) -> Unit,
    onSubmit: (String) -> Unit,
) {
    var code by remember(state.challenge) { mutableStateOf("") }
    // Accounts accepts a 6-digit authenticator code OR a one-time recovery code, so the field must
    // take letters as well; an alphanumeric keyboard is the only one that can type a backup code.
    val digitsOnly = code.length <= 6 && code.all { it.isDigit() }
    OutlinedTextField(
        value = code,
        onValueChange = { value -> if (value.length <= 64) { code = value.trim() } },
        label = { Text("Authenticator or recovery code") },
        placeholder = { Text("123456 or a backup code") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Done,
            keyboardType = if (digitsOnly) KeyboardType.NumberPassword else KeyboardType.Text,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("native_auth_mfa_field"),
    )
    Spacer(Modifier.height(14.dp))
    Button(
        onClick = { onSubmit(code) },
        enabled = !state.busy && code.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .testTag("native_auth_mfa_button"),
        shape = RoundedCornerShape(24.dp),
    ) {
        Text("Verify")
    }
    TextButton(onClick = { onUpdate { it.copy(error = null) } }) {
        Text("Use a backup code")
    }
}

@Composable
private fun SignUpStep(
    state: NativeAuthUiState,
    onSubmit: (ApiNativeSignUpRequest) -> Unit,
) {
    var firstName by remember { mutableStateOf("") }
    var lastName by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var recoveryEmail by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var gender by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(firstName, { firstName = it }, label = { Text("First name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(lastName, { lastName = it }, label = { Text("Last name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            username,
            { username = it },
            label = { Text("Username") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("native_auth_signup_username"),
        )
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().testTag("native_auth_signup_password"),
        )
        OutlinedTextField(recoveryEmail, { recoveryEmail = it }, label = { Text("Recovery email") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(phone, { phone = it }, label = { Text("Phone number (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(dateOfBirth, { dateOfBirth = it }, label = { Text("Date of birth (YYYY-MM-DD)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(gender, { gender = it }, label = { Text("Gender (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = {
                onSubmit(
                    ApiNativeSignUpRequest(
                        clientId = "",
                        scope = "",
                        firstName = firstName.trim(),
                        lastName = lastName.trim(),
                        username = username.trim(),
                        password = password,
                        recoveryEmail = recoveryEmail.trim().takeIf { it.isNotEmpty() },
                        phoneNumber = phone.trim().takeIf { it.isNotEmpty() },
                        dateOfBirth = dateOfBirth.trim().takeIf { it.isNotEmpty() },
                        gender = gender.trim().takeIf { it.isNotEmpty() },
                    ),
                )
            },
            enabled = !state.busy && username.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("native_auth_signup_button"),
            shape = RoundedCornerShape(24.dp),
        ) {
            Text("Create account")
        }
    }
}

@Composable
private fun RecoveryStep(
    state: NativeAuthUiState,
    onForgot: (String) -> Unit,
    onReset: (String, String) -> Unit,
) {
    var identifier by remember(state.identifier) { mutableStateOf(state.identifier) }
    var token by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            identifier,
            { identifier = it },
            label = { Text("Username or email") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("native_auth_recovery_field"),
        )
        Button(
            onClick = { onForgot(identifier.trim()) },
            enabled = !state.busy && identifier.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(23.dp),
        ) {
            Text("Email me a reset link")
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Already have a reset token? Paste it below.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(token, { token = it }, label = { Text("Reset token") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { onReset(token.trim(), password) },
            enabled = !state.busy && token.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(23.dp),
        ) {
            Text("Set new password")
        }
    }
}

/** Small status row used by the welcome screen while a silent sign-in is in flight. */
@Composable
fun SilentSignInBanner(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 520.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.size(10.dp))
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
