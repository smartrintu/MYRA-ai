package com.myra.assistant

import android.app.Application
import com.myra.assistant.data.SecurePrefs

class MyraApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SecurePrefs.init(this)
    }
}
