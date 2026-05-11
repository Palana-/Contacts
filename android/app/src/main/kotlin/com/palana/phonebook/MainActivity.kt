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
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
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

class MainActivity : Activity() {
    private val contacts = mutableListOf<PhoneContact>()
    private lateinit var shell: FrameLayout
    private lateinit var page: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var fab: TextView

    private var currentTab = Tab.CONTACTS
    private var editingContact: PhoneContact? = null
    private var selectedAvatarUri: String? = null
    private var pendingCallNumber: String? = null

    enum class Tab { CONTACTS, RECENT, MORE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        loadContacts()
        buildShell()
        showContacts()
    }

    override fun onBackPressed() {
        if (editingContact != null) {
            selectedAvatarUri = null
            editingContact = null
            showContacts()
            return
        }
        super.onBackPressed()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            selectedAvatarUri = uri.toString()
            showEditPage(editingContact)
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

    private fun buildShell() {
        shell = FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
            setPadding(0, statusBarHeight(), 0, 0)
            clipToPadding = false
        }
        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }
        content = FrameLayout(this)
        page.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(bottomNav())
        shell.addView(page, FrameLayout.LayoutParams(-1, -1))

        fab = TextView(this).apply {
            text = "+"
            textSize = 42f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = rounded(BRAND, 22)
            elevation = dp(10).toFloat()
            setOnClickListener { showEditPage(null) }
        }
        val fabParams = FrameLayout.LayoutParams(dp(74), dp(74), Gravity.BOTTOM or Gravity.RIGHT).apply {
            rightMargin = dp(24)
            bottomMargin = dp(76)
        }
        shell.addView(fab, fabParams)
        setContentView(shell)
    }

    private fun bottomNav(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(8).toFloat()
            addView(navItem("▣", "通讯录", Tab.CONTACTS), LinearLayout.LayoutParams(0, dp(70), 1f))
            addView(navItem("◷", "最近", Tab.RECENT), LinearLayout.LayoutParams(0, dp(70), 1f))
            addView(navItem("⋯", "更多", Tab.MORE), LinearLayout.LayoutParams(0, dp(70), 1f))
        }
    }

    private fun navItem(icon: String, label: String, tab: Tab): LinearLayout {
        val selected = currentTab == tab
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener {
                currentTab = tab
                buildShell()
                refreshCurrentTab()
            }
            addView(TextView(context).apply {
                text = icon
                textSize = 30f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (selected) BRAND else MUTED)
            })
            addView(TextView(context).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (selected) BRAND else MUTED)
            })
        }
    }

    private fun showContacts() {
        currentTab = Tab.CONTACTS
        editingContact = null
        fab.visibility = View.VISIBLE
        content.removeAllViews()

        if (contacts.isEmpty()) {
            content.addView(emptyView("还没有联系人", "点右下角 + 添加第一个联系人"))
            return
        }

        val withAvatar = contacts.filter { it.avatarUri != null }
        val withoutAvatar = contacts.filter { it.avatarUri == null }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(96))
        }

        if (withAvatar.isNotEmpty()) {
            val grid = GridLayout(this).apply { columnCount = 2 }
            withAvatar.forEach { grid.addView(visualCard(it), gridParams()) }
            body.addView(grid)
        }

        if (withoutAvatar.isNotEmpty()) {
            body.addView(sectionTitle(if (withAvatar.isEmpty()) "通讯录" else "无头像联系人"))
            withoutAvatar.forEach { body.addView(listRow(it)) }
        }

        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun showRecent() {
        currentTab = Tab.RECENT
        fab.visibility = View.GONE
        content.removeAllViews()
        val recent = contacts.filter { it.recentAt > 0L }.sortedByDescending { it.recentAt }
        if (recent.isEmpty()) {
            content.addView(emptyView("暂无最近通话", "拨号后会自动显示在这里"))
            return
        }
        val body = pageBody("最近")
        recent.forEach { body.addView(listRow(it)) }
        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun showMore() {
        currentTab = Tab.MORE
        fab.visibility = View.GONE
        content.removeAllViews()
        val body = pageBody("更多")
        body.addView(actionCard("从手机通讯录导入", "读取手机联系人并加入电话本") { requestReadContactsThenImport() })
        body.addView(actionCard("写入手机通讯录", "把电话本联系人保存到系统通讯录") { requestWriteContactsThenExport() })
        body.addView(actionCard("导出 VCF 文件", "生成 contacts.vcf 并调用系统分享") { exportVcfFile() })
        body.addView(actionCard("导出 CSV 文件", "生成 contacts.csv 并调用系统分享") { exportCsvFile() })
        body.addView(infoCard("当前版本", "1.0.0"))
        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun pageBody(heading: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            addView(TextView(context).apply {
                text = heading
                textSize = 28f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
                setPadding(0, 0, 0, dp(18))
            })
        }
    }

    private fun showEditPage(contact: PhoneContact?) {
        editingContact = contact ?: PhoneContact(name = "", phone = "")
        if (contact != null && selectedAvatarUri == null) selectedAvatarUri = contact.avatarUri
        fab.visibility = View.GONE
        content.removeAllViews()

        val nameInput = EditText(this).apply {
            hint = "姓名"
            setText(contact?.name.orEmpty())
            textSize = 18f
            setSingleLine(true)
        }
        val phoneInput = EditText(this).apply {
            hint = "手机号"
            setText(contact?.phone.orEmpty())
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }
        val avatar = ImageView(this).apply {
            background = rounded(Color.WHITE, 24)
            scaleType = ImageView.ScaleType.CENTER_CROP
            selectedAvatarUri?.let { setImageURI(Uri.parse(it)) }
            if (selectedAvatarUri == null) setImageResource(android.R.drawable.ic_menu_camera)
            setOnClickListener { pickImage() }
        }

        val body = pageBody(if (contact == null) "新增联系人" else "编辑联系人")
        body.gravity = Gravity.CENTER_HORIZONTAL
        body.addView(avatar, LinearLayout.LayoutParams(dp(132), dp(132)).withBottom(dp(20)))
        body.addView(nameInput, LinearLayout.LayoutParams(-1, dp(60)).withBottom(dp(12)))
        body.addView(phoneInput, LinearLayout.LayoutParams(-1, dp(60)).withBottom(dp(24)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryButton("返回") {
            selectedAvatarUri = null
            editingContact = null
            showContacts()
        }, LinearLayout.LayoutParams(0, dp(56), 1f).withRight(dp(10)))
        actions.addView(primaryButton("保存") {
            val name = nameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            if (name.isEmpty() || phone.isEmpty()) {
                toast("请填写姓名和手机号")
                return@primaryButton
            }
            val saved = PhoneContact(
                id = contact?.id ?: UUID.randomUUID().toString(),
                name = name,
                phone = phone,
                avatarUri = selectedAvatarUri,
                createdAt = contact?.createdAt ?: System.currentTimeMillis(),
                recentAt = contact?.recentAt ?: 0L
            )
            contacts.removeAll { it.id == saved.id }
            contacts.add(saved)
            saveContacts()
            selectedAvatarUri = null
            editingContact = null
            showContacts()
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        body.addView(actions)
        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun visualCard(contact: PhoneContact): View {
        val frame = FrameLayout(this).apply {
            background = gradientFor(contact)
            clipToOutline = true
            setOnClickListener { showContactDialog(contact) }
            elevation = dp(2).toFloat()
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(Uri.parse(contact.avatarUri))
            alpha = 0.96f
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1).apply {
            bottomMargin = dp(48)
        })
        frame.addView(TextView(this).apply {
            text = contact.name
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(0x88000000.toInt())
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            }
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

    private fun showContactDialog(contact: PhoneContact) {
        val dialog = AlertDialog.Builder(this).create()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(20), dp(22), dp(22))
            background = rounded(Color.WHITE, 26)
        }
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
            selectedAvatarUri = null
            showEditPage(contact)
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(dangerButton("删除") {
            dialog.dismiss()
            contacts.removeAll { it.id == contact.id }
            saveContacts()
            refreshCurrentTab()
        }, LinearLayout.LayoutParams(-1, dp(54)).withBottom(dp(10)))
        body.addView(secondaryButton("返回") { dialog.dismiss() }, LinearLayout.LayoutParams(-1, dp(46)))
        dialog.setView(body)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
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

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    private fun refreshCurrentTab() {
        when (currentTab) {
            Tab.CONTACTS -> showContacts()
            Tab.RECENT -> showRecent()
            Tab.MORE -> showMore()
        }
    }

    private fun loadContacts() {
        contacts.clear()
        val raw = getPreferences(MODE_PRIVATE).getString(CONTACTS_KEY, "[]") ?: "[]"
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
        getPreferences(MODE_PRIVATE).edit().putString(CONTACTS_KEY, array.toString()).apply()
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

    private fun infoCard(label: String, value: String): View {
        return baseCard().apply {
            addView(TextView(context).apply {
                text = label
                textSize = 14f
                setTextColor(MUTED)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 18f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            })
        }.withBottomMargin(dp(10))
    }

    private fun actionCard(label: String, value: String, onTap: () -> Unit): View {
        return baseCard().apply {
            setOnClickListener { onTap() }
            addView(TextView(context).apply {
                text = label
                textSize = 17f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(TEXT)
            })
            addView(TextView(context).apply {
                text = value
                textSize = 14f
                setTextColor(MUTED)
            })
        }.withBottomMargin(dp(10))
    }

    private fun baseCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(Color.WHITE, 18)
            elevation = dp(1).toFloat()
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

    private fun gridParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            height = dp(190)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(5), dp(5), dp(5), dp(10))
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
        private const val CONTACTS_KEY = "contacts"
        private const val PICK_IMAGE_REQUEST = 1001
        private const val CALL_PERMISSION_REQUEST = 1002
        private const val READ_CONTACTS_REQUEST = 1003
        private const val WRITE_CONTACTS_REQUEST = 1004
        private const val BRAND = 0xFF0891B2.toInt()
        private const val TEXT = 0xFF0F172A.toInt()
        private const val MUTED = 0xFF64748B.toInt()
        private const val SURFACE = 0xFFF8FAFC.toInt()
    }
}
