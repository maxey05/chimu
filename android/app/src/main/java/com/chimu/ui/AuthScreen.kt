package com.chimu.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chimu.Supa
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sign in or create an account. Nothing here moves on by itself: when Supabase reports a signed-in
 * session, the root of the app notices and swaps this screen for the main one.
 */
@Composable
fun AuthScreen() {
    var creating by rememberSaveable { mutableStateOf(false) }
    var nameText by rememberSaveable { mutableStateOf("") }
    var emailText by rememberSaveable { mutableStateOf("") }
    var passwordText by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var info by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun submit() {
        error = null
        info = null
        val name = nameText.trim()
        val emailValue = emailText.trim()
        if (creating && name.isEmpty()) {
            error = "Enter your name."
            return
        }
        if (!emailValue.contains("@") || emailValue.length < 5) {
            error = "Enter a valid email address."
            return
        }
        if (passwordText.length < MIN_PASSWORD_LENGTH) {
            error = "The password needs at least $MIN_PASSWORD_LENGTH characters."
            return
        }

        busy = true
        scope.launch {
            try {
                val auth = Supa.client.auth
                if (creating) {
                    val pendingUser = auth.signUpWith(Email) {
                        email = emailValue
                        password = passwordText
                        data = buildJsonObject { put("display_name", name) }
                    }
                    // A user came back without a session: the project asks people to confirm their email first.
                    if (pendingUser != null) {
                        creating = false
                        passwordText = ""
                        info = "Account created. Open the email we sent you to confirm it, then sign in here."
                    }
                } else {
                    auth.signInWith(Email) {
                        email = emailValue
                        password = passwordText
                    }
                }
                // On success supabase-kt now holds a session and the app root moves to the main screen.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = friendlyMessage(e, creating)
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("chimu", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Keep track of your receipts and spending.",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 24.dp),
        )
        Text(
            if (creating) "Create an account" else "Sign in",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        if (creating) {
            OutlinedTextField(
                value = nameText,
                onValueChange = { nameText = it },
                label = { Text("Your name") },
                singleLine = true,
                enabled = !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
            )
            Spacer(Modifier.height(16.dp))
        }
        OutlinedTextField(
            value = emailText,
            onValueChange = { emailText = it },
            label = { Text("Email") },
            singleLine = true,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = passwordText,
            onValueChange = { passwordText = it },
            label = { Text("Password") },
            singleLine = true,
            enabled = !busy,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
        )

        ErrorText(error, Modifier.padding(top = 12.dp))
        val infoText = info
        if (infoText != null) {
            Text(infoText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
        }

        Spacer(Modifier.height(24.dp))
        Button(
            onClick = { submit() },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Text(if (creating) "Create account" else "Sign in")
            }
        }
        TextButton(
            onClick = {
                creating = !creating
                error = null
                info = null
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchHeight),
        ) {
            Text(if (creating) "I already have an account" else "Create a new account")
        }
    }
}

/** Shown instead of the sign-in screen when the Supabase URL or key is missing from `local.properties`. */
@Composable
fun SetupNeededScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Setup needed", style = MaterialTheme.typography.titleLarge)
        Text(
            "This build has no Supabase project set. Add these two lines to android/local.properties " +
                "on the computer, then build and install the app again:",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 12.dp),
        )
        Text(
            "SUPABASE_URL=https://your-project.supabase.co\nSUPABASE_ANON_KEY=your-anon-key",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

private const val MIN_PASSWORD_LENGTH = 6

/** Turns the library's error into something a person can act on. */
private fun friendlyMessage(e: Exception, creating: Boolean): String {
    val raw = e.message.orEmpty()
    val kind = e::class.simpleName.orEmpty()
    return when {
        raw.contains("Invalid login credentials", ignoreCase = true) ->
            "That email and password don't match. Check them and try again."
        raw.contains("already registered", ignoreCase = true) || raw.contains("already been registered", ignoreCase = true) ->
            "That email already has an account. Try signing in instead."
        raw.contains("Email not confirmed", ignoreCase = true) ->
            "Please confirm your email first. Open the email we sent you, then sign in."
        raw.contains("password", ignoreCase = true) && creating ->
            "That password isn't accepted. Try a longer one."
        kind.contains("HttpRequest") || kind.contains("Timeout") || kind.contains("UnknownHost") ||
            kind.contains("IOException") || raw.contains("Unable to resolve host", ignoreCase = true) ->
            "Can't reach the server. Check your internet connection and try again."
        else -> "Something went wrong. ${raw.lineSequence().firstOrNull().orEmpty()}".trim()
    }
}
