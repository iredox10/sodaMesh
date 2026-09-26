package com.sodamesh

import android.app.Application
import com.sodamesh.mesh.OutboxWorker
import com.sodamesh.notify.OrderNotifier
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class SodaMeshApp : Application() {

    @Inject
    lateinit var outboxWorker: OutboxWorker

    /** Application-scoped scope; lives as long as the process. */
    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        OrderNotifier(this).ensureChannels()
        // Background redelivery of un-ACKed orders (idempotent start).
        outboxWorker.start(appScope)
    }
}
