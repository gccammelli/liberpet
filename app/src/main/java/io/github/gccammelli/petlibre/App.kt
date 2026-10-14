package io.github.gccammelli.petlibre

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(androidStorage(this))
    }
}
