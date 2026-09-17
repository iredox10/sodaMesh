package com.sodamesh.mesh.di

import com.sodamesh.mesh.router.DedupCache
import com.sodamesh.mesh.router.MessageRouter
import com.sodamesh.mesh.store.Outbox
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object MeshModule {

    @Provides
    @Singleton
    fun provideDedupCache(): DedupCache = DedupCache()

    @Provides
    @Singleton
    fun provideMessageRouter(): MessageRouter = MessageRouter()

    @Provides
    @Singleton
    fun provideOutbox(): Outbox = Outbox()
}
