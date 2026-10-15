package io.github.gccammelli.liberpet

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(androidStorage(this))
    }
}
