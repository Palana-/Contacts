package com.palana.phonebook

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.ByteArrayOutputStream
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.math.abs

data class PhoneContact(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val avatarUri: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val recentAt: Long = 0L
)

private data class ContactListItem(
    val contact: PhoneContact,
    val hasAvatar: Boolean
)

class MainActivity : Activity() {
    private val contacts = mutableListOf<PhoneContact>()
    private lateinit var content: FrameLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var contactAdapter: ContactAdapter
    private lateinit var contactLayoutManager: GridLayoutManager
    private val imageUriCache = LinkedHashMap<String, Uri>()
    private val avatarBitmapCache = LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt())
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private var pendingCallNumber: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        loadContacts()
        buildHome()
        showContacts()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == EDIT_CONTACT_REQUEST && resultCode == RESULT_OK) {
            loadContacts()
            updateContactList()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            CALL_PERMISSION_REQUEST -> if (granted) pendingCallNumber?.let { makeCallNow(it) } else toast("需要电话权限才能直接拨号")
            READ_CONTACTS_REQUEST -> if (granted) importFromPhoneContacts() else toast("需要通讯录读取权限")
            WRITE_CONTACTS_REQUEST -> if (granted) exportToPhoneContacts() else toast("需要通讯录写入权限")
        }
    }

    private fun buildHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(0, statusBarHeight(), 0, 0)
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        topBar.addView(TextView(this).apply {
            text = "电话本"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        topBar.addView(topIcon("+") { openEditor(null) }, LinearLayout.LayoutParams(dp(48), dp(48)).withRight(dp(6)))
        topBar.addView(topIcon("⋯") { showMoreMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))

        content = FrameLayout(this)
        root.addView(topBar)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun topIcon(text: String, onClick: (View) -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = if (text == "+") 34f else 32f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = rounded(BRAND, 16)
            setOnClickListener { onClick(it) }
        }
    }

    private fun showMoreMenu(anchor: View) {
        PopupMenu(this, anchor).apply {
            menu.add("从手机通讯录导入")
            menu.add("写入手机通讯录")
            menu.add("导出 VCF 文件")
            menu.add("导出 CSV 文件")
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    "从手机通讯录导入" -> requestReadContactsThenImport()
                    "写入手机通讯录" -> requestWriteContactsThenExport()
                    "导出 VCF 文件" -> exportVcfFile()
                    "导出 CSV 文件" -> exportCsvFile()
                }
                true
            }
            show()
        }
    }

    private fun showContacts() {
        content.removeAllViews()
        if (contacts.isEmpty()) {
            content.addView(emptyView("还没有联系人", "点右上角 + 添加第一个联系人"))
            return
        }

        val items = contacts
            .filter { it.avatarUri != null }
            .map { ContactListItem(it, true) } +
            contacts
                .filter { it.avatarUri == null }
                .map { ContactListItem(it, false) }

        contactAdapter = ContactAdapter(items)
        contactLayoutManager = GridLayoutManager(this, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (contactAdapter.itemAt(position).hasAvatar) 1 else 2
                }
            }
        }

        recyclerView = RecyclerView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(28))
            clipToPadding = false
            layoutManager = contactLayoutManager
            adapter = contactAdapter
            setHasFixedSize(false)
            setItemViewCacheSize(16)
            itemAnimator = null
        }
        content.addView(recyclerView, FrameLayout.LayoutParams(-1, -1))
    }

    private fun updateContactList() {
        val items = buildContactItems()
        if (items.isEmpty()) {
            showContacts()
            return
        }
        if (!::contactAdapter.isInitialized || !::recyclerView.isInitialized || recyclerView.parent == null) {
            showContacts()
            return
        }
        contactAdapter.updateItems(items)
    }

    private fun buildContactItems(): List<ContactListItem> {
        return contacts
            .filter { it.avatarUri != null }
            .map { ContactListItem(it, true) } +
            contacts
                .filter { it.avatarUri == null }
                .map { ContactListItem(it, false) }
    }

    private fun visualCard(contact: PhoneContact): View {
        val frame = FrameLayout(this).apply {
            background = gradientFor(contact)
            clipToOutline = true
            setOnClickListener { showContactDialog(contact) }
            elevation = dp(2).toFloat()
        }
        frame.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(Uri.parse(contact.avatarUri))
            alpha = 0.96f
        }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
        frame.addView(TextView(this).apply {
            text = contact.name
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply { setColor(0x88000000.toInt()) }
        }, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        return frame
    }

    private fun listRow(contact: PhoneContact): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 18)
            elevation = dp(1).toFloat()
            setOnClickListener { showContactDialog(contact) }
        }
        row.addView(TextView(this).apply {
            text = contact.name.take(1).uppercase()
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = gradientFor(contact)
        }, LinearLayout.LayoutParams(dp(58), dp(58)).withRight(dp(14)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = contact.name
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            })
            addView(TextView(context).apply {
                text = contact.phone
                textSize = 14f
                setTextColor(MUTED)
            })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        return row.withBottomMargin(dp(10))
    }

    private inner class ContactAdapter(
        items: List<ContactListItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val items = items.toMutableList()

        fun itemAt(position: Int): ContactListItem = items[position]

        fun updateItems(newItems: List<ContactListItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position].hasAvatar) VIEW_TYPE_AVATAR else VIEW_TYPE_LIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == VIEW_TYPE_AVATAR) {
                AvatarContactHolder(createAvatarItemView(parent))
            } else {
                ListContactHolder(createListItemView(parent))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is AvatarContactHolder) {
                holder.bind(item.contact)
            } else if (holder is ListContactHolder) {
                holder.bind(item.contact)
            }
        }

        override fun getItemCount(): Int = items.size
    }

    private inner class AvatarContactHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {
        private val image: ImageView = itemView.findViewWithTag("avatarImage")
        private val name: TextView = itemView.findViewWithTag("avatarName")

        fun bind(contact: PhoneContact) {
            itemView.background = gradientFor(contact)
            itemView.setOnClickListener { showContactDialog(contact) }
            name.text = contact.name
            val avatar = contact.avatarUri
            if (avatar == null) {
                image.tag = null
                image.setImageDrawable(null)
            } else {
                loadAvatarAsync(avatar, image)
            }
        }
    }

    private inner class ListContactHolder(
        itemView: View
    ) : RecyclerView.ViewHolder(itemView) {
        private val initial: TextView = itemView.findViewWithTag("listInitial")
        private val name: TextView = itemView.findViewWithTag("listName")
        private val phone: TextView = itemView.findViewWithTag("listPhone")

        fun bind(contact: PhoneContact) {
            itemView.setOnClickListener { showContactDialog(contact) }
            initial.text = contact.name.take(1).uppercase()
            initial.background = gradientFor(contact)
            name.text = contact.name
            phone.text = contact.phone
        }
    }

    private fun createAvatarItemView(parent: ViewGroup): View {
        return FrameLayout(parent.context).apply {
            clipToOutline = true
            elevation = dp(2).toFloat()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(190)
            ).apply {
                setMargins(dp(5), dp(5), dp(5), dp(10))
            }
            addView(ImageView(context).apply {
                tag = "avatarImage"
                scaleType = ImageView.ScaleType.CENTER_CROP
                alpha = 0.96f
            }, FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = dp(48) })
            addView(TextView(context).apply {
                tag = "avatarName"
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                background = GradientDrawable().apply { setColor(0x88000000.toInt()) }
            }, FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM))
        }
    }

    private fun createListItemView(parent: ViewGroup): View {
        return LinearLayout(parent.context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = rounded(Color.WHITE, 18)
            elevation = dp(1).toFloat()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(5), 0, dp(10))
            }
            addView(TextView(context).apply {
                tag = "listInitial"
                gravity = Gravity.CENTER
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
            }, LinearLayout.LayoutParams(dp(58), dp(58)).withRight(dp(14)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(context).apply {
                    tag = "listName"
                    textSize = 18f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(TEXT)
                })
                addView(TextView(context).apply {
                    tag = "listPhone"
                    textSize = 14f
                    setTextColor(MUTED)
                })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
    }

    private fun showContactDialog(contact: PhoneContact) {
        val dialog = AlertDialog.Builder(this).create()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = rounded(Color.WHITE, 26)
        }
        body.addView(FrameLayout(this).apply {
            addView(TextView(context).apply {
                text = "×"
                textSize = 28f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(MUTED)
                setOnClickListener { dialog.dismiss() }
            }, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.RIGHT))
        }, LinearLayout.LayoutParams(-1, dp(44)).withBottom(dp(2)))
        body.addView(TextView(this).apply {
            text = contact.name.take(1).uppercase()
            textSize = 56f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            background = gradientFor(contact)
        }, LinearLayout.LayoutParams(dp(136), dp(136)).withBottom(dp(14)))
        body.addView(TextView(this).apply {
            text = contact.name
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = contact.phone
            textSize = 17f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-1, -2).withBottom(dp(18)))
        body.addView(primaryButton("拨号") {
            dialog.dismiss()
            callContact(contact)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(secondaryButton("编辑") {
            dialog.dismiss()
            openEditor(contact)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(dangerButton("删除") {
            confirmDelete(contact, dialog)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        dialog.setView(body)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun openEditor(contact: PhoneContact?) {
        val intent = Intent(this, EditContactActivity::class.java)
        contact?.let {
            intent.putExtra("id", it.id)
            intent.putExtra("name", it.name)
            intent.putExtra("phone", it.phone)
            intent.putExtra("avatarUri", it.avatarUri)
            intent.putExtra("createdAt", it.createdAt)
            intent.putExtra("recentAt", it.recentAt)
        }
        startActivityForResult(intent, EDIT_CONTACT_REQUEST)
        overridePendingTransition(0, 0)
    }

    private fun confirmDelete(contact: PhoneContact, detailDialog: AlertDialog) {
        AlertDialog.Builder(this)
            .setTitle("删除联系人")
            .setMessage("确定要删除“${contact.name}”吗？")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                detailDialog.dismiss()
                contacts.removeAll { it.id == contact.id }
                saveContacts()
                showContacts()
            }
            .show()
    }

    private fun requestReadContactsThenImport() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) importFromPhoneContacts()
        else requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), READ_CONTACTS_REQUEST)
    }

    private fun requestWriteContactsThenExport() {
        if (contacts.isEmpty()) {
            toast("暂无联系人可写入")
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) exportToPhoneContacts()
        else requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), WRITE_CONTACTS_REQUEST)
    }

    private fun importFromPhoneContacts() {
        val existingByPhone = contacts.associateBy { normalizePhone(it.phone) }.toMutableMap()
        var imported = 0
        var avatarUpdated = 0
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_URI
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val phone = cursor.getString(phoneIndex)?.trim().orEmpty()
                val normalized = normalizePhone(phone)
                if (normalized.isEmpty()) continue
                val phoneAvatar = cursor.getString(photoIndex)
                val existing = existingByPhone[normalized]
                if (existing != null) {
                    if (existing.avatarUri == null && !phoneAvatar.isNullOrBlank()) {
                        val localAvatar = copyAvatarToPrivateFile(phoneAvatar, existing.id) ?: phoneAvatar
                        val updated = existing.copy(avatarUri = localAvatar)
                        contacts.removeAll { it.id == existing.id }
                        contacts.add(updated)
                        existingByPhone[normalized] = updated
                        avatarUpdated++
                    }
                    continue
                }
                val id = UUID.randomUUID().toString()
                val localAvatar = if (!phoneAvatar.isNullOrBlank()) {
                    copyAvatarToPrivateFile(phoneAvatar, id) ?: phoneAvatar
                } else {
                    null
                }
                contacts.add(
                    PhoneContact(
                        id = id,
                        name = cursor.getString(nameIndex)?.trim().orEmpty().ifBlank { phone },
                        phone = phone,
                        avatarUri = localAvatar
                    )
                )
                existingByPhone[normalized] = contacts.last()
                imported++
            }
        }
        saveContacts()
        toast("已导入 $imported 个联系人，补齐 $avatarUpdated 个头像")
        showContacts()
    }

    private fun exportToPhoneContacts() {
        var exported = 0
        for (contact in contacts) {
            val rawContactId = findRawContactIdByPhone(contact.phone)
            if (rawContactId != null) {
                if (contact.avatarUri != null && upsertSystemContactPhoto(rawContactId, contact.avatarUri)) {
                    exported++
                }
                continue
            }

            val operations = arrayListOf<ContentProviderOperation>()
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build()
            )
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name)
                    .build()
            )
            operations.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phone)
                    .withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                    .build()
            )
            avatarBytes(contact.avatarUri)?.let { bytes ->
                operations.add(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
                        .build()
                )
            }
            try {
                contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                exported++
            } catch (_: Exception) {
            }
        }
        toast("已写入 $exported 个联系人到手机通讯录")
    }

    private fun findRawContactIdByPhone(phone: String): Long? {
        val target = normalizePhone(phone)
        if (target.isEmpty()) return null
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID
        )
        contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val rawIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            while (cursor.moveToNext()) {
                if (normalizePhone(cursor.getString(phoneIndex).orEmpty()) == target) {
                    return cursor.getLong(rawIdIndex)
                }
            }
        }
        return null
    }

    private fun upsertSystemContactPhoto(rawContactId: Long, avatarUri: String): Boolean {
        val bytes = avatarBytes(avatarUri) ?: return false
        val dataId = findPhotoDataId(rawContactId)
        return try {
            val operations = if (dataId != null) {
                arrayListOf(
                    ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                        .withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString()))
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
                        .build()
                )
            } else {
                arrayListOf(
                    ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                        .withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes)
                        .build()
                )
            }
            contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun findPhotoDataId(rawContactId: Long): Long? {
        val projection = arrayOf(ContactsContract.Data._ID)
        val selection = "${ContactsContract.Data.RAW_CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE}=?"
        val args = arrayOf(rawContactId.toString(), ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE)
        contentResolver.query(ContactsContract.Data.CONTENT_URI, projection, selection, args, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Data._ID))
            }
        }
        return null
    }

    private fun avatarBytes(avatarUri: String?): ByteArray? {
        if (avatarUri.isNullOrBlank()) return null
        return try {
            val bitmap = decodeAvatarThumbnail(avatarUri) ?: return null
            ByteArrayOutputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
                output.toByteArray()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun exportVcfFile() {
        if (contacts.isEmpty()) {
            toast("暂无联系人可导出")
            return
        }
        val text = buildString {
            contacts.forEach { c ->
                appendLine("BEGIN:VCARD")
                appendLine("VERSION:3.0")
                appendLine("FN:${escapeVcf(c.name)}")
                appendLine("TEL;TYPE=CELL:${escapeVcf(c.phone)}")
                appendLine("END:VCARD")
            }
        }
        shareExportFile("contacts.vcf", "text/vcard", text)
    }

    private fun exportCsvFile() {
        if (contacts.isEmpty()) {
            toast("暂无联系人可导出")
            return
        }
        val text = buildString {
            appendLine("姓名,手机号")
            contacts.forEach { appendLine("${csvCell(it.name)},${csvCell(it.phone)}") }
        }
        shareExportFile("contacts.csv", "text/csv", text)
    }

    private fun shareExportFile(fileName: String, mimeType: String, text: String) {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        toast("已生成：${file.absolutePath}")
        startActivity(Intent.createChooser(intent, "导出联系人"))
    }

    private fun callContact(contact: PhoneContact) {
        val updated = contact.copy(recentAt = System.currentTimeMillis())
        contacts.removeAll { it.id == contact.id }
        contacts.add(updated)
        saveContacts()
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) makeCallNow(contact.phone)
        else {
            pendingCallNumber = contact.phone
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST)
        }
    }

    private fun makeCallNow(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
        } catch (_: SecurityException) {
            toast("没有电话权限")
        }
    }

    private fun loadContacts() {
        contacts.clear()
        val raw = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(CONTACTS_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            contacts.add(
                PhoneContact(
                    id = item.getString("id"),
                    name = item.getString("name"),
                    phone = item.getString("phone"),
                    avatarUri = item.optString("avatarUri").ifBlank { null },
                    createdAt = item.optLong("createdAt"),
                    recentAt = item.optLong("recentAt")
                )
            )
        }
        contacts.sortWith(compareByDescending<PhoneContact> { it.avatarUri != null }.thenBy { it.name })
    }

    private fun saveContacts() {
        val array = JSONArray()
        contacts.forEach {
            array.put(JSONObject().apply {
                put("id", it.id)
                put("name", it.name)
                put("phone", it.phone)
                put("avatarUri", it.avatarUri ?: "")
                put("createdAt", it.createdAt)
                put("recentAt", it.recentAt)
            })
        }
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putString(CONTACTS_KEY, array.toString()).apply()
        loadContacts()
    }

    private fun sectionTitle(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            setPadding(dp(4), dp(12), dp(4), dp(10))
        }
    }

    private fun emptyView(heading: String, subheading: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = heading
                textSize = 22f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = subheading
                textSize = 15f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(0, dp(8), 0, 0)
            })
        }
    }

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            background = rounded(BRAND, 16)
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(BRAND)
            background = rounded(0xFFE0F2F1.toInt(), 16)
            setOnClickListener { onClick() }
        }
    }

    private fun dangerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            background = rounded(0xFFDC2626.toInt(), 16)
            setOnClickListener { onClick() }
        }
    }

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun gradientFor(contact: PhoneContact): GradientDrawable {
        val palettes = arrayOf(
            intArrayOf(0xFFEC4899.toInt(), 0xFFF472B6.toInt()),
            intArrayOf(0xFF06B6D4.toInt(), 0xFF67E8F9.toInt()),
            intArrayOf(0xFFF59E0B.toInt(), 0xFFFDE047.toInt()),
            intArrayOf(0xFF10B981.toInt(), 0xFF34D399.toInt()),
            intArrayOf(0xFF0891B2.toInt(), 0xFF22D3EE.toInt()),
            intArrayOf(0xFF7C3AED.toInt(), 0xFFA78BFA.toInt())
        )
        val colors = palettes[abs(contact.displayName().hashCode()) % palettes.size]
        return GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
            cornerRadius = dp(18).toFloat()
        }
    }

    private fun PhoneContact.displayName(): String = name.ifBlank { phone }

    private fun cachedUri(value: String): Uri {
        return imageUriCache.getOrPut(value) { Uri.parse(value) }
    }

    private fun loadAvatarAsync(uriText: String, target: ImageView) {
        target.tag = uriText
        avatarBitmapCache.get(uriText)?.let {
            target.setImageBitmap(it)
            return
        }
        target.setImageDrawable(null)
        imageExecutor.execute {
            val bitmap = decodeAvatarThumbnail(uriText)
            if (bitmap != null) {
                avatarBitmapCache.put(uriText, bitmap)
                target.post {
                    if (target.tag == uriText) {
                        target.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun decodeAvatarThumbnail(uriText: String): Bitmap? {
        val uri = cachedUri(uriText)
        return try {
            if (uri.scheme == "file") {
                return decodeSampledFile(uri.path ?: return null, dp(220), dp(220))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                contentResolver.loadThumbnail(uri, Size(dp(220), dp(220)), null)
                    ?: decodeSampledBitmap(uri, dp(220), dp(220))
            } else {
                decodeSampledBitmap(uri, dp(220), dp(220))
            }
        } catch (_: Exception) {
            try {
                if (uri.scheme == "file") decodeSampledFile(uri.path ?: return null, dp(220), dp(220))
                else decodeSampledBitmap(uri, dp(220), dp(220))
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun decodeSampledFile(path: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return BitmapFactory.decodeFile(path, options)
    }

    private fun copyAvatarToPrivateFile(sourceUri: String, contactId: String): String? {
        return try {
            val bitmap = decodeAvatarThumbnail(sourceUri) ?: return null
            val dir = File(filesDir, "avatars").apply { mkdirs() }
            val file = File(dir, "$contactId.jpg")
            FileOutputStream(file).use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output)
            }
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(uri: Uri, reqWidth: Int, reqHeight: Int): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight)
        options.inJustDecodeBounds = false
        options.inPreferredConfig = Bitmap.Config.RGB_565
        return contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun View.withBottomMargin(margin: Int): View {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = margin }
        return this
    }

    private fun LinearLayout.LayoutParams.withBottom(margin: Int): LinearLayout.LayoutParams {
        bottomMargin = margin
        return this
    }

    private fun LinearLayout.LayoutParams.withRight(margin: Int): LinearLayout.LayoutParams {
        rightMargin = margin
        return this
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() || it == '+' }

    private fun escapeVcf(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun csvCell(value: String): String = "\"" + value.replace("\"", "\"\"") + "\""

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        const val PREFS_NAME = "phone_book"
        const val CONTACTS_KEY = "contacts"
        private const val EDIT_CONTACT_REQUEST = 1000
        private const val CALL_PERMISSION_REQUEST = 1002
        private const val READ_CONTACTS_REQUEST = 1003
        private const val WRITE_CONTACTS_REQUEST = 1004
        private const val BRAND = 0xFF0891B2.toInt()
        private const val TEXT = 0xFF0F172A.toInt()
        private const val MUTED = 0xFF64748B.toInt()
        private const val SURFACE = 0xFFF8FAFC.toInt()
        private const val VIEW_TYPE_AVATAR = 1
        private const val VIEW_TYPE_LIST = 2
    }
}
