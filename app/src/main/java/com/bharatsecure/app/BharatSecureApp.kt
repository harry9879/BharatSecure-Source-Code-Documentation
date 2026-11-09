package com.bharatsecure.app

import android.app.Application

class BharatSecureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannels(this)
    }
}
