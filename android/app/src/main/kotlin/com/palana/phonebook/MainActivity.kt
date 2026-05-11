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
import android.widget.ProgressBar
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
import java.io.InputStream
import java.nio.charset.Charset
import java.text.Collator
import java.util.concurrent.Executors
import java.util.UUID
import java.util.Locale
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
    private lateinit var progressOverlay: FrameLayout
    private lateinit var progressText: TextView
    private val imageUriCache = LinkedHashMap<String, Uri>()
    private val avatarBitmapCache = LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 16).toInt())
    private val imageExecutor = Executors.newFixedThreadPool(2)
    private val workExecutor = Executors.newSingleThreadExecutor()
    private val gbkCharset = Charset.forName("GBK")
    private val chineseCollator = Collator.getInstance(Locale.CHINA)
    private var pendingCallNumber: String? = null
    @Volatile private var operationInProgress = false

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

    override fun onBackPressed() {
        if (operationInProgress) return
        super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            CALL_PERMISSION_REQUEST -> if (granted) pendingCallNumber?.let { makeCallNow(it) } else toast("\u9700\u8981\u7535\u8bdd\u6743\u9650\u624d\u80fd\u62e8\u53f7")
            READ_CONTACTS_REQUEST -> if (granted) runImportFromPhoneContacts() else toast("\u9700\u8981\u901a\u8baf\u5f55\u8bfb\u53d6\u6743\u9650")
            WRITE_CONTACTS_REQUEST -> if (granted) runExportToPhoneContacts() else toast("\u9700\u8981\u901a\u8baf\u5f55\u5199\u5165\u6743\u9650")
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
            text = "\u7535\u8bdd\u672c"
            textSize = 28f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        topBar.addView(topIcon("+") { openEditor(null) }, LinearLayout.LayoutParams(dp(48), dp(48)).withRight(dp(6)))
        topBar.addView(topIcon("...") { showMoreMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))

        content = FrameLayout(this)
        root.addView(topBar)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(-1, -1))
            progressOverlay = createProgressOverlay()
            addView(progressOverlay, FrameLayout.LayoutParams(-1, -1))
        })
    }

    private fun createProgressOverlay(): FrameLayout {
        return FrameLayout(this).apply {
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setBackgroundColor(0x66000000)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(22), dp(24), dp(22))
                background = rounded(Color.WHITE, 22)
                addView(ProgressBar(context), LinearLayout.LayoutParams(dp(52), dp(52)).withBottom(dp(14)))
                progressText = TextView(context).apply {
                    text = "\u6b63\u5728\u5904\u7406..."
                    textSize = 16f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    typeface = Typeface.DEFAULT_BOLD
                }
                addView(progressText)
            }, FrameLayout.LayoutParams(dp(190), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
    }

    private fun showProgress(message: String) {
        operationInProgress = true
        progressText.text = message
        progressOverlay.visibility = View.VISIBLE
    }

    private fun hideProgress() {
        progressOverlay.visibility = View.GONE
        operationInProgress = false
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
        val importTitle = "\u4ece\u624b\u673a\u901a\u8baf\u5f55\u5bfc\u5165"
        val syncTitle = "\u5199\u5165\u624b\u673a\u901a\u8baf\u5f55"
        val vcfTitle = "\u5bfc\u51fa VCF \u6587\u4ef6"
        val csvTitle = "\u5bfc\u51fa CSV \u6587\u4ef6"
        PopupMenu(this, anchor).apply {
            menu.add(importTitle)
            menu.add(syncTitle)
            menu.add(vcfTitle)
            menu.add(csvTitle)
            setOnMenuItemClickListener {
                when (it.title.toString()) {
                    importTitle -> requestReadContactsThenImport()
                    syncTitle -> requestWriteContactsThenExport()
                    vcfTitle -> exportVcfFile()
                    csvTitle -> exportCsvFile()
                }
                true
            }
            show()
        }
    }
    private fun showContacts() {
        content.removeAllViews()
        if (contacts.isEmpty()) {
            content.addView(emptyView("\u6682\u65e0\u8054\u7cfb\u4eba", "\u70b9\u51fb\u53f3\u4e0a\u89d2 + \u6dfb\u52a0\u8054\u7cfb\u4eba"))
            return
        }

        val items = buildContactItems()
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
            .sortedWith(contactNameComparator())
            .map { ContactListItem(it, true) } +
            contacts
                .filter { it.avatarUri == null }
                .sortedWith(contactNameComparator())
                .map { ContactListItem(it, false) }
    }

    private fun contactNameComparator(): Comparator<PhoneContact> {
        return compareBy<PhoneContact> { nameInitial(it.displayName()) }
            .thenComparator { left, right -> chineseCollator.compare(left.displayName(), right.displayName()) }
            .thenBy { it.phone }
    }

    private fun nameInitial(name: String): String {
        val first = name.trim().firstOrNull() ?: return "#"
        if (first in 'A'..'Z' || first in 'a'..'z') return first.uppercaseChar().toString()
        if (first.isDigit()) return "#$first"
        return chineseInitial(first)?.toString() ?: first.uppercaseChar().toString()
    }

    private fun chineseInitial(ch: Char): Char? {
        val bytes = ch.toString().toByteArray(gbkCharset)
        if (bytes.size < 2) return null
        val code = (bytes[0].toInt() and 0xFF) * 256 + (bytes[1].toInt() and 0xFF)
        if (code < 0xB0A1 || code > 0xF7FE) return null
        val initials = charArrayOf(
            'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K',
            'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W',
            'X', 'Y', 'Z'
        )
        val ranges = intArrayOf(
            0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE,
            0xBBF7, 0xBFA6, 0xC0AC, 0xC2E8, 0xC4C3, 0xC5B6, 0xC5BE, 0xC6DA,
            0xC8BB, 0xC8F6, 0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1, 0xD7FA
        )
        for (i in 0 until ranges.lastIndex) {
            if (code >= ranges[i] && code < ranges[i + 1]) return initials[i]
        }
        return null
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
            name.text = contact.displayName()
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
        private val name: TextView = itemView.findViewWithTag("listName")
        private val phone: TextView = itemView.findViewWithTag("listPhone")

        fun bind(contact: PhoneContact) {
            itemView.setOnClickListener { showContactDialog(contact) }
            name.text = contact.displayName()
            name.background = gradientFor(contact)
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
            setPadding(dp(10), dp(8), dp(12), dp(8))
            background = rounded(Color.WHITE, 18)
            elevation = dp(1).toFloat()
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp(2), 0, dp(5))
            }
            addView(TextView(context).apply {
                tag = "listName"
                gravity = Gravity.CENTER
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setSingleLine(true)
                setPadding(dp(10), 0, dp(10), 0)
            }, LinearLayout.LayoutParams(0, dp(40), 1f).withRight(dp(12)))
            addView(TextView(context).apply {
                tag = "listPhone"
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
                setSingleLine(true)
                setTextColor(MUTED)
            }, LinearLayout.LayoutParams(0, dp(40), 1.15f))
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
                text = "X"
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
            text = contact.displayName()
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
        body.addView(primaryButton("\u62e8\u53f7") {
            dialog.dismiss()
            callContact(contact)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(secondaryButton("\u7f16\u8f91") {
            dialog.dismiss()
            openEditor(contact)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(dangerButton("\u5220\u9664") {
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
            .setTitle("\u5220\u9664\u8054\u7cfb\u4eba")
            .setMessage("\u786e\u5b9a\u5220\u9664 ${contact.name} \u5417\uff1f")
            .setNegativeButton("\u53d6\u6d88", null)
            .setPositiveButton("\u5220\u9664") { _, _ ->
                detailDialog.dismiss()
                contacts.removeAll { it.id == contact.id }
                saveContacts()
                showContacts()
            }
            .show()
    }

    private fun requestReadContactsThenImport() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) runImportFromPhoneContacts()
        else requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), READ_CONTACTS_REQUEST)
    }

    private fun requestWriteContactsThenExport() {
        if (contacts.isEmpty()) {
            toast("\u6682\u65e0\u8054\u7cfb\u4eba\u53ef\u5199\u5165")
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) runExportToPhoneContacts()
        else requestPermissions(arrayOf(Manifest.permission.WRITE_CONTACTS), WRITE_CONTACTS_REQUEST)
    }

    private fun runImportFromPhoneContacts() {
        showProgress("\u6b63\u5728\u5bfc\u5165\u901a\u8baf\u5f55...")
        workExecutor.execute {
            val result = importFromPhoneContacts()
            runOnUiThread {
                saveContacts()
                hideProgress()
                toast("\u5df2\u5bfc\u5165 ${result.first} \u4e2a\u8054\u7cfb\u4eba\uff0c\u8865\u9f50 ${result.second} \u4e2a\u5934\u50cf")
                showContacts()
            }
        }
    }

    private fun runExportToPhoneContacts() {
        showProgress("\u6b63\u5728\u5199\u5165\u624b\u673a\u901a\u8baf\u5f55...")
        workExecutor.execute {
            val exported = exportToPhoneContacts()
            runOnUiThread {
                hideProgress()
                toast("\u5df2\u540c\u6b65 $exported \u4e2a\u8054\u7cfb\u4eba\u5230\u624b\u673a\u901a\u8baf\u5f55")
            }
        }
    }
    private fun importFromPhoneContacts(): Pair<Int, Int> {
        val existingByPhone = contacts.associateBy { normalizePhone(it.phone) }.toMutableMap()
        var imported = 0
        var avatarUpdated = 0
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
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
            val contactIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val photoIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.PHOTO_URI)
            while (cursor.moveToNext()) {
                val systemContactId = cursor.getLong(contactIdIndex)
                val phone = cursor.getString(phoneIndex)?.trim().orEmpty()
                val normalized = normalizePhone(phone)
                if (normalized.isEmpty()) continue
                val phoneAvatar = cursor.getString(photoIndex)
                val existing = existingByPhone[normalized]
                if (existing != null) {
                    if (existing.avatarUri == null && !phoneAvatar.isNullOrBlank()) {
                        val localAvatar = copyAvatarToPrivateFile(phoneAvatar, existing.id, systemContactId) ?: phoneAvatar
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
                    copyAvatarToPrivateFile(phoneAvatar, id, systemContactId) ?: phoneAvatar
                } else {
                    copyAvatarToPrivateFile(null, id, systemContactId)
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
        return imported to avatarUpdated
    }

    private fun exportToPhoneContacts(): Int {
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
        return exported
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
            toast("\u6682\u65e0\u8054\u7cfb\u4eba\u53ef\u5bfc\u51fa")
            return
        }
        showProgress("\u6b63\u5728\u5bfc\u51fa VCF...")
        workExecutor.execute {
            val text = buildString {
                contacts.forEach { c ->
                    appendLine("BEGIN:VCARD")
                    appendLine("VERSION:3.0")
                    appendLine("FN:${escapeVcf(c.name)}")
                    appendLine("TEL;TYPE=CELL:${escapeVcf(c.phone)}")
                    appendLine("END:VCARD")
                }
            }
            val file = writeExportFile("contacts.vcf", text)
            runOnUiThread {
                hideProgress()
                shareExportFile(file, "text/vcard")
            }
        }
    }

    private fun exportCsvFile() {
        if (contacts.isEmpty()) {
            toast("\u6682\u65e0\u8054\u7cfb\u4eba\u53ef\u5bfc\u51fa")
            return
        }
        showProgress("\u6b63\u5728\u5bfc\u51fa CSV...")
        workExecutor.execute {
            val text = buildString {
                appendLine("\u59d3\u540d,\u624b\u673a\u53f7")
                contacts.forEach { appendLine("${csvCell(it.name)},${csvCell(it.phone)}") }
            }
            val file = writeExportFile("contacts.csv", text)
            runOnUiThread {
                hideProgress()
                shareExportFile(file, "text/csv")
            }
        }
    }

    private fun writeExportFile(fileName: String, text: String): File {
        val dir = File(cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(text, Charsets.UTF_8)
        return file
    }

    private fun shareExportFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        toast("\u5df2\u751f\u6210\uff1a${file.absolutePath}")
        startActivity(Intent.createChooser(intent, "\u5bfc\u51fa\u8054\u7cfb\u4eba"))
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
            toast("\u62e8\u53f7\u5931\u8d25\uff0c\u8bf7\u68c0\u67e5\u7535\u8bdd\u6743\u9650")
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
        contacts.sortWith(compareByDescending<PhoneContact> { it.avatarUri != null }.then(contactNameComparator()))
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

    private fun copyAvatarToPrivateFile(sourceUri: String?, contactId: String, systemContactId: Long? = null): String? {
        return try {
            val bitmap = if (!sourceUri.isNullOrBlank()) {
                decodeAvatarThumbnail(sourceUri)
            } else {
                null
            } ?: decodeSystemContactPhoto(systemContactId) ?: return null
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

    private fun decodeSystemContactPhoto(systemContactId: Long?): Bitmap? {
        if (systemContactId == null) return null
        val contactUri = Uri.withAppendedPath(
            ContactsContract.Contacts.CONTENT_URI,
            systemContactId.toString()
        )
        return try {
            openSystemContactPhotoStream(contactUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun openSystemContactPhotoStream(contactUri: Uri): InputStream? {
        return ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, contactUri, true)
            ?: ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, contactUri)
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
