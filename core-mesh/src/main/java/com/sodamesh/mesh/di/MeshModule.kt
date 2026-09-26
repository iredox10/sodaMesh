package com.sodamesh.mesh.di

import android.content.Context
import com.sodamesh.mesh.router.DedupCache
import com.sodamesh.mesh.router.MessageRouter
import com.sodamesh.mesh.router.RelayController
import com.sodamesh.mesh.store.Outbox
import com.sodamesh.mesh.transport.GattClientManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Scope for mesh-layer background work (GATT callbacks, routers, workers). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MeshScope

@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    fun provideDedupCache(): DedupCache = DedupCache()

    @Provides
    @Singleton
    fun provideMessageRouter(dedup: DedupCache, relay: RelayController): MessageRouter =
        MessageRouter(dedup, relay)

    @Provides
    @Singleton
    fun provideOutbox(): Outbox = Outbox()

    @Provides
    @Singleton
    @MeshScope
    fun provideMeshScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideGattClientManager(
        @ApplicationContext context: Context,
        @MeshScope scope: CoroutineScope,
    ): GattClientManager = GattClientManager(context, scope)
}
