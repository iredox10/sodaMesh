package com.sodamesh.di

import android.content.Context
import androidx.room.Room
import com.sodamesh.data.OrderDao
import com.sodamesh.data.OrderRepository
import com.sodamesh.data.RoomOrderRepository
import com.sodamesh.data.SodaDb
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DbModule {

    @Provides
    @Singleton
    fun provideSodaDb(@ApplicationContext appContext: Context): SodaDb =
        Room.databaseBuilder(appContext, SodaDb::class.java, "soda.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideOrderDao(db: SodaDb): OrderDao = db.orderDao()

    @Provides
    @Singleton
    fun provideOrderRepository(dao: OrderDao): OrderRepository =
        RoomOrderRepository(dao)
}
