package com.chimu

import android.app.Application

/** Holds the app-wide objects so they survive screen rotation and are created once. */
class ChimuApp : Application() {
    lateinit var repo: Repo
        private set

    lateinit var account: Account
        private set

    lateinit var sync: SyncManager
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("chimu", MODE_PRIVATE)
        val db = Db(this)
        account = Account(prefs)
        // The manager reloads the screens' data through `repo` after a sync; the repo asks the
        // manager to sync after a save. `repo` is only read when a sync finishes, never during setup.
        sync = SyncManager(LocalStore(db, prefs), prefs, account, onSynced = { repo.refresh() })
        repo = Repo(db, prefs, onChanged = sync::onLocalChange)
        if (Supa.isConfigured) SyncWorker.schedule(this)
    }
}
