package com.sodamesh.mesh

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
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MeshBindings {

    @Binds
    abstract fun bindMeshSender(impl: BleMeshSender): MeshSender
}
