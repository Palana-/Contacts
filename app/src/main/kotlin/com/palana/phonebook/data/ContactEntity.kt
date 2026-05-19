package com.palana.phonebook.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.palana.phonebook.ContactSyncState
import com.palana.phonebook.PhoneContact

@Entity(
    tableName = "contacts",
    indices = [
        Index(value = ["phone"]),
        Index(value = ["syncState"]),
        Index(value = ["updatedAt"])
    ]
)
data class ContactEntity(
    @PrimaryKey val id: String,
    val name: String,
    val phone: String,
    val avatarUri: String?,
    val createdAt: Long,
    val recentAt: Long,
    val updatedAt: Long,
    val remoteId: String?,
    val lastSyncedAt: Long,
    val syncState: String
)

fun ContactEntity.toContact(): PhoneContact {
    return PhoneContact(
        id = id,
        name = name,
        phone = phone,
        avatarUri = avatarUri,
        createdAt = createdAt,
        recentAt = recentAt,
        updatedAt = updatedAt,
        remoteId = remoteId,
        lastSyncedAt = lastSyncedAt,
        syncState = runCatching { ContactSyncState.valueOf(syncState) }.getOrDefault(ContactSyncState.PENDING)
    )
}

fun PhoneContact.toEntity(defaultSyncState: ContactSyncState = syncState): ContactEntity {
    return ContactEntity(
        id = id,
        name = name,
        phone = phone,
        avatarUri = avatarUri,
        createdAt = createdAt,
        recentAt = recentAt,
        updatedAt = updatedAt,
        remoteId = remoteId,
        lastSyncedAt = lastSyncedAt,
        syncState = defaultSyncState.name
    )
}
