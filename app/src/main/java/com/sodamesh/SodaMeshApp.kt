package com.sodamesh

import android.app.Application
import com.sodamesh.notify.OrderNotifier
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class SodaMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        OrderNotifier(this).ensureChannels()
    }
}
