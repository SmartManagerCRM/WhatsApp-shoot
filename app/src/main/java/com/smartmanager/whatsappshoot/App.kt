package com.smartmanager.whatsappshoot

import android.app.Application
import com.smartmanager.whatsappshoot.platform.AppGraph

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }
}
