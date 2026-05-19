package com.palana.phonebook.data

import android.content.Context
import com.palana.phonebook.ContactSyncState
import com.palana.phonebook.PhoneContact
import com.palana.phonebook.normalizeMainlandMobileNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

class ContactRepository private constructor(
    context: Context,
    private val syncDataSource: ContactSyncDataSource
) {
    private val appContext = context.applicationContext
    private val dao = ContactDatabase.getInstance(appContext).contactDao()

    suspend fun getContacts(): List<PhoneContact> = withContext(Dispatchers.IO) {
        migrateLegacyContactsIfNeeded()
        dao.getAll().map { it.toContact() }.dedupeByPhone()
    }

    suspend fun getContact(id: String): PhoneContact? = withContext(Dispatchers.IO) {
        dao.getById(id)?.toContact()
    }

    suspend fun getDeletedPhoneKeys(): Set<String> = withContext(Dispatchers.IO) {
        dao.getDeleted().map { normalizePhoneKey(it.phone) }.filter { it.isNotEmpty() }.toSet()
    }

    suspend fun getDeletedContactMarkers(): List<DeletedContactMarker> = withContext(Dispatchers.IO) {
        dao.getDeleted()
            .map { DeletedContactMarker(id = it.id, phoneKey = normalizePhoneKey(it.phone)) }
            .filter { it.phoneKey.isNotEmpty() }
    }

    suspend fun replaceContacts(contacts: List<PhoneContact>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.upsertAll(contacts.map { it.copy(updatedAt = now, syncState = ContactSyncState.PENDING).toEntity() })
    }

    suspend fun upsertContact(contact: PhoneContact) = withContext(Dispatchers.IO) {
        dao.upsert(contact.copy(updatedAt = System.currentTimeMillis(), syncState = ContactSyncState.PENDING).toEntity())
    }

    suspend fun deleteContact(id: String) = withContext(Dispatchers.IO) {
        dao.markDeleted(id, System.currentTimeMillis())
    }

    suspend fun syncNow(): SyncResult = withContext(Dispatchers.IO) {
        val pending = dao.getPendingSync().map { it.toContact() }
        val result = syncDataSource.pushChanges(pending)
        if (result.success && pending.isNotEmpty()) {
            val syncedAt = System.currentTimeMillis()
            val deletedIds = pending.filter { it.syncState == ContactSyncState.DELETED }.map { it.id }
            if (deletedIds.isNotEmpty()) {
                dao.deleteByIds(deletedIds)
            }
            val activeChanges = pending.filter { it.syncState != ContactSyncState.DELETED }
            if (activeChanges.isNotEmpty()) {
                dao.upsertAll(
                    activeChanges.map {
                    it.copy(
                        lastSyncedAt = syncedAt,
                        syncState = ContactSyncState.SYNCED
                    ).toEntity(ContactSyncState.SYNCED)
                    }
                )
            }
        }
        result
    }

    private fun List<PhoneContact>.dedupeByPhone(): List<PhoneContact> {
        return groupBy { normalizePhoneKey(it.phone).ifBlank { it.id } }
            .values
            .map { group ->
                group.maxWith(
                    compareBy<PhoneContact> { !it.avatarUri.isNullOrBlank() }
                        .thenBy { it.updatedAt }
                        .thenBy { it.createdAt }
                )
            }
    }

    private fun normalizePhoneKey(phone: String): String {
        return normalizeMainlandMobileNumber(phone).ifBlank { phone.filter { it.isDigit() || it == '+' } }
    }

    private suspend fun migrateLegacyContactsIfNeeded() {
        val prefs = appContext.getSharedPreferences(LEGACY_PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(LEGACY_MIGRATED_KEY, false)) return

        val raw = prefs.getString(LEGACY_CONTACTS_KEY, "[]").orEmpty()
        val migrated = mutableListOf<PhoneContact>()
        runCatching {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                migrated.add(
                    PhoneContact(
                        id = item.getString("id"),
                        name = item.optString("name"),
                        phone = item.optString("phone"),
                        avatarUri = item.optString("avatarUri").ifBlank { null },
                        createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                        recentAt = item.optLong("recentAt", 0L),
                        updatedAt = System.currentTimeMillis(),
                        syncState = ContactSyncState.PENDING
                    )
                )
            }
        }
        if (migrated.isNotEmpty() && dao.getAll().isEmpty()) {
            dao.upsertAll(migrated.map { it.toEntity(ContactSyncState.PENDING) })
        }
        prefs.edit().putBoolean(LEGACY_MIGRATED_KEY, true).apply()
    }

    companion object {
        private const val LEGACY_PREFS_NAME = "phone_book"
        private const val LEGACY_CONTACTS_KEY = "contacts"
        private const val LEGACY_MIGRATED_KEY = "contacts_room_migrated"

        @Volatile private var instance: ContactRepository? = null

        fun getInstance(
            context: Context,
            syncDataSource: ContactSyncDataSource = NoOpContactSyncDataSource
        ): ContactRepository {
            return instance ?: synchronized(this) {
                instance ?: ContactRepository(context.applicationContext, syncDataSource).also { instance = it }
            }
        }
    }
}

interface ContactSyncDataSource {
    suspend fun pushChanges(changes: List<PhoneContact>): SyncResult
    suspend fun pullChanges(since: Long?): List<PhoneContact>
}

object NoOpContactSyncDataSource : ContactSyncDataSource {
    override suspend fun pushChanges(changes: List<PhoneContact>): SyncResult {
        return SyncResult(success = true, pushedCount = changes.size, pulledCount = 0)
    }

    override suspend fun pullChanges(since: Long?): List<PhoneContact> = emptyList()
}

data class SyncResult(
    val success: Boolean,
    val pushedCount: Int,
    val pulledCount: Int,
    val message: String? = null
)

data class DeletedContactMarker(
    val id: String,
    val phoneKey: String
)
