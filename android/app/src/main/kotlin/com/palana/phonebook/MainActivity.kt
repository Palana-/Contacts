package com.palana.phonebook

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
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
            showContacts()
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

        val adapter = ContactAdapter(items)
        val layoutManager = GridLayoutManager(this, 2).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int): Int {
                    return if (items[position].hasAvatar) 1 else 2
                }
            }
        }

        content.addView(RecyclerView(this).apply {
            setPadding(dp(16), dp(16), dp(16), dp(28))
            clipToPadding = false
            this.layoutManager = layoutManager
            this.adapter = adapter
            setHasFixedSize(false)
            itemAnimator = null
        }, FrameLayout.LayoutParams(-1, -1))
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
        private val items: List<ContactListItem>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemViewType(position: Int): Int {
            return if (items[position].hasAvatar) VIEW_TYPE_AVATAR else VIEW_TYPE_LIST
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val placeholder = FrameLayout(parent.context)
            return object : RecyclerView.ViewHolder(placeholder) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            val view = if (item.hasAvatar) visualCard(item.contact) else listRow(item.contact)
            val height = if (item.hasAvatar) dp(190) else ViewGroup.LayoutParams.WRAP_CONTENT
            val params = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height).apply {
                val horizontal = if (item.hasAvatar) dp(5) else 0
                setMargins(horizontal, dp(5), horizontal, dp(10))
            }
            holder.itemView.layoutParams = params
            (holder.itemView as FrameLayout).apply {
                removeAllViews()
                addView(
                    view,
                    FrameLayout.LayoutParams(
                        -1,
                        if (item.hasAvatar) -1 else ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        override fun getItemCount(): Int = items.size
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
        val existingPhones = contacts.map { normalizePhone(it.phone) }.toMutableSet()
        var imported = 0
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
                if (normalized.isEmpty() || existingPhones.contains(normalized)) continue
                contacts.add(
                    PhoneContact(
                        name = cursor.getString(nameIndex)?.trim().orEmpty().ifBlank { phone },
                        phone = phone,
                        avatarUri = cursor.getString(photoIndex)
                    )
                )
                existingPhones.add(normalized)
                imported++
            }
        }
        saveContacts()
        toast("已导入 $imported 个联系人")
        showContacts()
    }

    private fun exportToPhoneContacts() {
        var exported = 0
        for (contact in contacts) {
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
            try {
                contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                exported++
            } catch (_: Exception) {
            }
        }
        toast("已写入 $exported 个联系人到手机通讯录")
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
