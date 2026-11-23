package com.chimu

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's auto-log choices, kept on this phone in the app's preferences (no Supabase column, so
 * no migration): which apps' notifications are read, which apps have posted a notification since
 * access was granted (the list to choose from), which SMS senders are allowed, and whether the Home
 * card that explains auto-logging was dismissed. Nothing here is synced.
 *
 * Reads are plain state flows for the screens. The listener service calls [recordSeen] from another
 * thread, so every change is under one lock.
 */
class AutoLogSettings(private val prefs: SharedPreferences) {
    private val _enabled = MutableStateFlow(prefs.getStringSet(KEY_ENABLED, null).orEmpty().toSet())
    private val _seen = MutableStateFlow(prefs.getStringSet(KEY_SEEN, null).orEmpty().toSet())
    private val _senders = MutableStateFlow(readSenders())
    private val _onboardingDismissed = MutableStateFlow(prefs.getBoolean(KEY_ONBOARDING_DISMISSED, false))

    /** Package names of the apps whose notifications are read. */
    val enabledPackages: StateFlow<Set<String>> = _enabled.asStateFlow()

    /** Package names of apps that have posted a notification (only the name is kept), to pick from. */
    val seenPackages: StateFlow<Set<String>> = _seen.asStateFlow()

    /** SMS senders that may be read. */
    val senders: StateFlow<List<String>> = _senders.asStateFlow()

    val onboardingDismissed: StateFlow<Boolean> = _onboardingDismissed.asStateFlow()

    /** True once the user has turned on any app or allowed any sender. */
    fun hasAnySource(): Boolean = _enabled.value.isNotEmpty() || _senders.value.isNotEmpty()

    fun isEnabled(packageName: String): Boolean = packageName in _enabled.value

    @Synchronized
    fun setEnabled(packageName: String, enabled: Boolean) {
        val next = if (enabled) _enabled.value + packageName else _enabled.value - packageName
        _enabled.value = next
        prefs.edit().putStringSet(KEY_ENABLED, next).apply()
    }

    /** Called for every notification the listener sees. Writes only when the package is new. */
    @Synchronized
    fun recordSeen(packageName: String?) {
        val next = SeenApps.record(_seen.value, packageName) ?: return
        _seen.value = next
        prefs.edit().putStringSet(KEY_SEEN, next).apply()
    }

    /** Adds an SMS sender. False if it is not a valid sender, already there, or the list is full. */
    @Synchronized
    fun addSender(input: String?): Boolean {
        val next = SenderIds.add(_senders.value, input) ?: return false
        _senders.value = next
        prefs.edit().putString(KEY_SENDERS, next.joinToString(SEPARATOR)).apply()
        return true
    }

    @Synchronized
    fun removeSender(sender: String) {
        val next = _senders.value.filter { !it.equals(sender, ignoreCase = true) }
        _senders.value = next
        prefs.edit().putString(KEY_SENDERS, next.joinToString(SEPARATOR)).apply()
    }

    @Synchronized
    fun dismissOnboarding() {
        _onboardingDismissed.value = true
        prefs.edit().putBoolean(KEY_ONBOARDING_DISMISSED, true).apply()
    }

    private fun readSenders(): List<String> =
        prefs.getString(KEY_SENDERS, null).orEmpty().split(SEPARATOR).mapNotNull { SenderIds.normalize(it) }

    private companion object {
        const val KEY_ENABLED = "autolog_enabled_packages"
        const val KEY_SEEN = "autolog_seen_packages"
        const val KEY_SENDERS = "autolog_sms_senders"
        const val KEY_ONBOARDING_DISMISSED = "autolog_onboarding_dismissed"
        const val SEPARATOR = "\n"
    }
}
