package com.palana.phonebook

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

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
    private lateinit var content: FrameLayout
    private lateinit var title: TextView
    private lateinit var addButton: ImageButton

    private var currentTab = Tab.CONTACTS
    private var editingContact: PhoneContact? = null
    private var selectedAvatarUri: String? = null
    private var pendingCallNumber: String? = null

    enum class Tab { CONTACTS, RECENT, MORE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadContacts()
        buildShell()
        showContacts()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
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
        if (requestCode == CALL_PERMISSION_REQUEST &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            pendingCallNumber?.let { makeCallNow(it) }
        } else if (requestCode == CALL_PERMISSION_REQUEST) {
            Toast.makeText(this, "需要电话权限才能直接拨号", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildShell() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
        }

        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        title = TextView(this).apply {
            text = "电话本"
            textSize = 24f
            setTextColor(TEXT)
            typeface = Typeface.DEFAULT_BOLD
        }
        addButton = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_input_add)
            setColorFilter(Color.WHITE)
            setBackgroundColor(BRAND)
            setOnClickListener { showEditPage(null) }
            contentDescription = "新增联系人"
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(addButton, LinearLayout.LayoutParams(dp(48), dp(48)))

        content = FrameLayout(this)
        val bottomNav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
            setBackgroundColor(Color.WHITE)
        }
        bottomNav.addView(navButton("通讯录", Tab.CONTACTS), LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomNav.addView(navButton("最近", Tab.RECENT), LinearLayout.LayoutParams(0, dp(58), 1f))
        bottomNav.addView(navButton("更多", Tab.MORE), LinearLayout.LayoutParams(0, dp(58), 1f))

        root.addView(toolbar)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNav)
        setContentView(root)
    }

    private fun navButton(label: String, tab: Tab): TextView {
        return TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (tab == currentTab) BRAND else MUTED)
            setOnClickListener {
                currentTab = tab
                when (tab) {
                    Tab.CONTACTS -> showContacts()
                    Tab.RECENT -> showRecent()
                    Tab.MORE -> showMore()
                }
                buildShell()
                when (currentTab) {
                    Tab.CONTACTS -> showContacts()
                    Tab.RECENT -> showRecent()
                    Tab.MORE -> showMore()
                }
            }
        }
    }

    private fun showContacts() {
        currentTab = Tab.CONTACTS
        title.text = "电话本"
        addButton.visibility = View.VISIBLE
        content.removeAllViews()

        val withAvatar = contacts.filter { it.avatarUri != null }
        val withoutAvatar = contacts.filter { it.avatarUri == null }

        if (contacts.isEmpty()) {
            content.addView(emptyView("还没有联系人", "点右上角 + 添加第一个联系人"))
            return
        }

        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }

        if (withAvatar.isNotEmpty()) {
            body.addView(sectionTitle("头像联系人"))
            val grid = GridLayout(this).apply {
                columnCount = 2
                useDefaultMargins = false
            }
            withAvatar.forEach { grid.addView(avatarCard(it), gridParams()) }
            body.addView(grid)
        }

        if (withoutAvatar.isNotEmpty()) {
            body.addView(sectionTitle("全部联系人"))
            withoutAvatar.forEach { body.addView(listRow(it)) }
        }

        scroll.addView(body)
        content.addView(scroll)
    }

    private fun showRecent() {
        currentTab = Tab.RECENT
        title.text = "最近"
        addButton.visibility = View.GONE
        content.removeAllViews()

        val recent = contacts.filter { it.recentAt > 0L }.sortedByDescending { it.recentAt }
        if (recent.isEmpty()) {
            content.addView(emptyView("暂无最近通话", "拨号后会自动显示在这里"))
            return
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(24))
        }
        recent.forEach { body.addView(listRow(it)) }
        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun showMore() {
        currentTab = Tab.MORE
        title.text = "更多"
        addButton.visibility = View.GONE
        content.removeAllViews()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        body.addView(infoCard("应用名称", "电话本"))
        body.addView(infoCard("当前版本", "1.0.0"))
        body.addView(infoCard("数据存储", "联系人保存在本机应用数据中"))
        content.addView(body)
    }

    private fun showEditPage(contact: PhoneContact?) {
        editingContact = contact
        if (selectedAvatarUri == null) selectedAvatarUri = contact?.avatarUri
        title.text = if (contact == null) "新增联系人" else "编辑联系人"
        addButton.visibility = View.GONE
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
            setBackgroundColor(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_CROP
            selectedAvatarUri?.let { setImageURI(Uri.parse(it)) }
            if (selectedAvatarUri == null) setImageResource(android.R.drawable.ic_menu_camera)
            setOnClickListener { pickImage() }
            contentDescription = "选择头像"
        }

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        body.addView(avatar, LinearLayout.LayoutParams(dp(128), dp(128)).withBottom(dp(20)))
        body.addView(nameInput, LinearLayout.LayoutParams(-1, dp(60)).withBottom(dp(12)))
        body.addView(phoneInput, LinearLayout.LayoutParams(-1, dp(60)).withBottom(dp(24)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryButton("返回") {
            selectedAvatarUri = null
            editingContact = null
            showContacts()
        }, LinearLayout.LayoutParams(0, dp(54), 1f).withRight(dp(10)))
        actions.addView(primaryButton("保存") {
            val name = nameInput.text.toString().trim()
            val phone = phoneInput.text.toString().trim()
            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "请填写姓名和手机号", Toast.LENGTH_SHORT).show()
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
        }, LinearLayout.LayoutParams(0, dp(54), 1f))
        body.addView(actions)
        content.addView(ScrollView(this).apply { addView(body) })
    }

    private fun avatarCard(contact: PhoneContact): View {
        val frame = FrameLayout(this).apply {
            setBackgroundColor(Color.WHITE)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { showContactDialog(contact) }
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageURI(Uri.parse(contact.avatarUri))
        }
        val label = TextView(this).apply {
            text = contact.name
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setBackgroundColor(0x99000000.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        frame.addView(image, FrameLayout.LayoutParams(-1, -1))
        frame.addView(label, FrameLayout.LayoutParams(-1, dp(42), Gravity.BOTTOM))
        return frame
    }

    private fun listRow(contact: PhoneContact): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.WHITE)
            setOnClickListener { showContactDialog(contact) }
        }
        row.addView(TextView(this).apply {
            text = contact.name
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
        })
        row.addView(TextView(this).apply {
            text = contact.phone
            textSize = 15f
            setTextColor(MUTED)
        })
        return row.withBottomMargin(dp(10))
    }

    private fun showContactDialog(contact: PhoneContact) {
        val dialog = AlertDialog.Builder(this).create()
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(16), dp(22), dp(22))
            setBackgroundColor(Color.WHITE)
        }

        val back = Button(this).apply {
            text = "返回"
            setOnClickListener { dialog.dismiss() }
        }
        body.addView(back, LinearLayout.LayoutParams(-1, dp(46)).withBottom(dp(12)))

        body.addView(ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (contact.avatarUri != null) setImageURI(Uri.parse(contact.avatarUri))
            else setImageResource(android.R.drawable.sym_def_app_icon)
        }, LinearLayout.LayoutParams(dp(132), dp(132)).withBottom(dp(14)))

        body.addView(TextView(this).apply {
            text = contact.name
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER
        })
        body.addView(TextView(this).apply {
            text = contact.phone
            textSize = 18f
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
        }, LinearLayout.LayoutParams(-1, dp(54)))

        dialog.setView(body)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnCancelListener { refreshCurrentTab() }
        dialog.show()
    }

    private fun callContact(contact: PhoneContact) {
        val updated = contact.copy(recentAt = System.currentTimeMillis())
        contacts.removeAll { it.id == contact.id }
        contacts.add(updated)
        saveContacts()

        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            makeCallNow(contact.phone)
        } else {
            pendingCallNumber = contact.phone
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), CALL_PERMISSION_REQUEST)
        }
    }

    private fun makeCallNow(phone: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone"))
        try {
            startActivity(intent)
        } catch (e: SecurityException) {
            Toast.makeText(this, "没有电话权限", Toast.LENGTH_SHORT).show()
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
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            setPadding(dp(4), dp(10), dp(4), dp(8))
        }
    }

    private fun emptyView(heading: String, subheading: String): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            addView(TextView(context).apply {
                text = heading
                textSize = 20f
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
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setBackgroundColor(Color.WHITE)
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

    private fun primaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(BRAND)
            setOnClickListener { onClick() }
        }
    }

    private fun secondaryButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(BRAND)
            setBackgroundColor(0xFFE0F2F1.toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun dangerButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 17f
            setTextColor(Color.WHITE)
            setBackgroundColor(0xFFDC2626.toInt())
            setOnClickListener { onClick() }
        }
    }

    private fun gridParams(): GridLayout.LayoutParams {
        return GridLayout.LayoutParams().apply {
            width = 0
            height = dp(180)
            columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
            setMargins(dp(4), dp(4), dp(4), dp(8))
        }
    }

    private fun View.withBottomMargin(margin: Int): View {
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = margin
        }
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONTACTS_KEY = "contacts"
        private const val PICK_IMAGE_REQUEST = 1001
        private const val CALL_PERMISSION_REQUEST = 1002
        private const val BRAND = 0xFF0F766E.toInt()
        private const val TEXT = 0xFF0F172A.toInt()
        private const val MUTED = 0xFF64748B.toInt()
        private const val SURFACE = 0xFFF8FAFC.toInt()
    }
}
