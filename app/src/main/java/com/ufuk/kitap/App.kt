package com.ufuk.kitap

import android.app.Application
import com.ufuk.kitap.data.Prefs
import com.ufuk.kitap.data.Repo
import com.ufuk.kitap.net.Firebase
import com.ufuk.kitap.net.Sync

class App : Application() {
    lateinit var prefs: Prefs
    lateinit var repo: Repo
    lateinit var firebase: Firebase
    lateinit var sync: Sync

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repo = Repo(this, prefs)
        repo.load()
        firebase = Firebase(prefs)
        sync = Sync(this, repo, firebase)
    }
}
