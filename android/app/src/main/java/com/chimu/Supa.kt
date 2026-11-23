package com.chimu

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.functions.Functions
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.storage.Storage

/**
 * The one Supabase client for the whole app.
 *
 * The URL and anon key come from `android/local.properties` through BuildConfig (never committed,
 * RULES 13 and 15). Only the anon key is ever in the app: every request runs as the signed-in
 * user and RLS decides what they can see.
 *
 * Auth persists the session on the phone by default and restores it at launch (supabase-kt's
 * `autoLoadFromStorage`), which is what keeps the user signed in across restarts.
 *
 * Postgrest, Storage and Functions are installed here, once, because the sync engine (RFC-003),
 * receipt photos and the `extract` function (RFC-004) all share this client.
 */
object Supa {
    /** False until both values are in `local.properties`. Screens show setup help instead of crashing. */
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()

    val client: SupabaseClient by lazy {
        check(isConfigured) { "SUPABASE_URL and SUPABASE_ANON_KEY are missing from android/local.properties" }
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY,
        ) {
            install(Auth)
            install(Postgrest)
            install(Storage)
            install(Functions)
        }
    }
}
