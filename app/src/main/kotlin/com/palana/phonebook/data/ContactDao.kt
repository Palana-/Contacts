package com.palana.phonebook.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts WHERE syncState != 'DELETED' ORDER BY CASE WHEN avatarUri IS NULL OR avatarUri = '' THEN 1 ELSE 0 END, name COLLATE NOCASE ASC, phone ASC")
    suspend fun getAll(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE syncState != 'SYNCED'")
    suspend fun getPendingSync(): List<ContactEntity>

    @Query("SELECT * FROM contacts WHERE syncState = 'DELETED'")
    suspend fun getDeleted(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<ContactEntity>)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()

    @Query("UPDATE contacts SET syncState = 'DELETED', updatedAt = :updatedAt WHERE id = :id")
    suspend fun markDeleted(id: String, updatedAt: Long)

    @Query("DELETE FROM contacts WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Transaction
    suspend fun replaceAll(contacts: List<ContactEntity>) {
        deleteAll()
        upsertAll(contacts)
    }
}
