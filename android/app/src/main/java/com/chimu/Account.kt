package com.chimu

import android.content.SharedPreferences
import io.github.jan.supabase.auth.user.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Who is signed in, as shown in the app. */
data class Identity(val userId: String, val email: String?, val displayName: String?)

/**
 * The signed-in user, remembered on the phone.
 *
 * The app is offline-first, so being signed in must not depend on reaching Supabase. The session
 * itself is kept by supabase-kt (see [Supa]); this keeps just enough (id, email, name) to open the
 * app straight into the user's local data at launch, even with no network. It is cleared only when
 * the user signs out.
 */
class Account(private val prefs: SharedPreferences) {
    private val _identity = MutableStateFlow(load())

    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    /** Called whenever supabase-kt reports an authenticated session (sign-in, sign-up, restart). */
    fun signedIn(session: UserSession) {
        val user = session.user ?: return
        val name = (user.userMetadata?.get("display_name") as? JsonPrimitive)?.contentOrNull
        val identity = Identity(userId = user.id, email = user.email, displayName = name)
        prefs.edit()
            .putString(KEY_ID, identity.userId)
            .putString(KEY_EMAIL, identity.email)
            .putString(KEY_NAME, identity.displayName)
            .apply()
        _identity.value = identity
    }

    fun signedOut() {
        prefs.edit().remove(KEY_ID).remove(KEY_EMAIL).remove(KEY_NAME).apply()
        _identity.value = null
    }

    private fun load(): Identity? {
        val id = prefs.getString(KEY_ID, null) ?: return null
        return Identity(id, prefs.getString(KEY_EMAIL, null), prefs.getString(KEY_NAME, null))
    }

    private companion object {
        const val KEY_ID = "account_id"
        const val KEY_EMAIL = "account_email"
        const val KEY_NAME = "account_name"
    }
}
