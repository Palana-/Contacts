package com.palana.phonebook.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ContactEntity::class], version = 1, exportSchema = false)
abstract class ContactDatabase : RoomDatabase() {
    abstract fun contactDao(): ContactDao

    companion object {
        @Volatile private var instance: ContactDatabase? = null

        fun getInstance(context: Context): ContactDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ContactDatabase::class.java,
                    "phone_book.db"
                ).build().also { instance = it }
            }
        }
    }
}
