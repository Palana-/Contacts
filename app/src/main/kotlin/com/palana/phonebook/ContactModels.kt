package com.palana.phonebook

import java.util.UUID

data class PhoneContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val recentAt: Long = 0L,
    val updatedAt: Long = System.currentTimeMillis(),
    val remoteId: String? = null,
    val lastSyncedAt: Long = 0L,
    val syncState: ContactSyncState = ContactSyncState.PENDING
)

enum class ContactSyncState {
    SYNCED,
    PENDING,
    DELETED
}

fun PhoneContact.displayName(): String = name.ifBlank { phone }
