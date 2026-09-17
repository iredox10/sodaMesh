package com.sodamesh.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [OrderEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class SodaDb : RoomDatabase() {
    abstract fun orderDao(): OrderDao

    companion object {
        const val DB_NAME = "soda.db"
    }
}
