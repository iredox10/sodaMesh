package com.sodamesh.mesh

import com.sodamesh.MeshRole
import com.sodamesh.data.MeshSender
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the BLE [MeshSender] implementation for the customer pipeline.
 *
 * Mirrors [com.sodamesh.di.AppModule] scoping: everything here lives in
 * the [SingletonComponent].
 *
 * Outbox retries: [OutboxWorker] is `@Singleton`-injectable through this
 * component (it only needs the [com.sodamesh.mesh.store.Outbox] +
 * [BleMeshSender] bindings). It does NOT self-start — the coordinator
 * starts it once from an application scope (see [OutboxWorker] KDoc);
 * neither `MeshService` nor `MainActivity` is touched by this module.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeshBindings {

    @Binds
    abstract fun bindMeshSender(impl: BleMeshSender): MeshSender

    /** Lets [com.sodamesh.MeshService] drive the real BLE role. */
    @Binds
    abstract fun bindMeshRole(impl: MeshRoleImpl): MeshRole
}
