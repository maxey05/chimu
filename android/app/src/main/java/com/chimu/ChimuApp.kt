package com.chimu

import android.app.Application

/** Holds the app-wide objects so they survive screen rotation and are created once. */
class ChimuApp : Application() {
    lateinit var repo: Repo
        private set

    lateinit var account: Account
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("chimu", MODE_PRIVATE)
        account = Account(prefs)
        repo = Repo(Db(this), prefs)
    }
}
