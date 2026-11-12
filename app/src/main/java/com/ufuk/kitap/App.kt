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
        // Çökme olursa nedenini kaydet; bir sonraki açılışta gösterilir
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                java.io.File(filesDir, "crash.txt").writeText(
                    "Sürüm: " + packageManager.getPackageInfo(packageName, 0).versionName + "\n" + android.util.Log.getStackTraceString(e).take(4000)
                )
            } catch (_: Throwable) {
            }
            prev?.uncaughtException(t, e)
        }
        prefs = Prefs(this)
        repo = Repo(this, prefs)
        repo.load()
        firebase = Firebase(prefs)
        sync = Sync(this, repo, firebase)
    }
}
