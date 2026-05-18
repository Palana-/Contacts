package com.palana.phonebook

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.luck.picture.lib.basic.PictureSelector
import com.luck.picture.lib.config.SelectMimeType
import com.luck.picture.lib.config.SelectModeConfig
import com.luck.picture.lib.entity.LocalMedia
import com.luck.picture.lib.interfaces.OnResultCallbackListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.UUID

class EditContactActivity : Activity() {
    private var contactId: String? = null
    private var selectedAvatarUri: String? = null
    private var createdAt: Long = 0L
    private var recentAt: Long = 0L
    private lateinit var avatar: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        contactId = intent.getStringExtra("id")
        selectedAvatarUri = intent.getStringExtra("avatarUri")
        createdAt = intent.getLongExtra("createdAt", System.currentTimeMillis())
        recentAt = intent.getLongExtra("recentAt", 0L)

        buildPage()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }

    private fun buildPage() {
        val nameInput = EditText(this).apply {
            hint = "\u59d3\u540d"
            setText(intent.getStringExtra("name").orEmpty())
            textSize = 18f
            setSingleLine(true)
        }
        val phoneInput = EditText(this).apply {
            hint = "\u624b\u673a\u53f7"
            setText(intent.getStringExtra("phone").orEmpty())
            textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setSingleLine(true)
        }

        avatar = ImageView(this).apply {
            background = rounded(Color.WHITE, 24)
            scaleType = ImageView.ScaleType.CENTER_CROP
            selectedAvatarUri?.let { setImageURI(Uri.parse(it)) }
            if (selectedAvatarUri == null) {
                scaleType = ImageView.ScaleType.CENTER
                setImageResource(R.drawable.ic_default_avatar)
                setColorFilter(MUTED)
            }
            setOnClickListener { pickImage() }
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SURFACE)
            setPadding(0, statusBarHeight(), 0, 0)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(8))
            setBackgroundColor(Color.WHITE)
            elevation = dp(2).toFloat()
        }
        topBar.addView(TextView(this).apply {
            text = "<"
            textSize = 36f
            gravity = Gravity.CENTER
            setTextColor(BRAND)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(52)))
        topBar.addView(TextView(this).apply {
            text = if (contactId == null) "\u65b0\u589e\u8054\u7cfb\u4eba" else "\u7f16\u8f91\u8054\u7cfb\u4eba"
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }
        body.addView(avatar, LinearLayout.LayoutParams(dp(136), dp(136)).withBottom(dp(22)))
        body.addView(nameInput, LinearLayout.LayoutParams(-1, dp(62)).withBottom(dp(12)))
        body.addView(phoneInput, LinearLayout.LayoutParams(-1, dp(62)).withBottom(dp(24)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(secondaryButton("\u53d6\u6d88") { finish() }, LinearLayout.LayoutParams(0, dp(56), 1f).withRight(dp(10)))
        actions.addView(primaryButton("\u4fdd\u5b58") {
            saveContact(nameInput.text.toString().trim(), phoneInput.text.toString().trim())
        }, LinearLayout.LayoutParams(0, dp(56), 1f))
        body.addView(actions)

        root.addView(topBar)
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun saveContact(name: String, phone: String) {
        if (phone.isEmpty()) {
            Toast.makeText(this, "\u8bf7\u586b\u5199\u624b\u673a\u53f7", Toast.LENGTH_SHORT).show()
            return
        }
        if (name.isEmpty() && selectedAvatarUri.isNullOrBlank()) {
            Toast.makeText(this, "\u8bf7\u586b\u5199\u59d3\u540d\u6216\u9009\u62e9\u5934\u50cf", Toast.LENGTH_SHORT).show()
            return
        }
        val contacts = loadContacts().toMutableList()
        val id = contactId ?: UUID.randomUUID().toString()
        contacts.removeAll { it.id == id }
        contacts.add(
            PhoneContact(
                id = id,
                name = name,
                phone = phone,
                avatarUri = selectedAvatarUri,
                createdAt = if (contactId == null) System.currentTimeMillis() else createdAt,
                recentAt = recentAt
            )
        )
        saveContacts(contacts)
        setResult(RESULT_OK)
        finish()
    }

    private fun pickImage() {
        PictureSelector.create(this)
            .openGallery(SelectMimeType.ofImage())
            .setImageEngine(ContactImageEngine)
            .setSelectionMode(SelectModeConfig.SINGLE)
            .setMaxSelectNum(1)
            .isDisplayCamera(false)
            .isDirectReturnSingle(true)
            .forResult(object : OnResultCallbackListener<LocalMedia> {
                override fun onResult(result: ArrayList<LocalMedia>) {
                    val media = result.firstOrNull() ?: return
                    val avatarUri = copyAvatarToPrivateFile(media.availablePath.ifBlank { media.path })
                    if (avatarUri == null) {
                        Toast.makeText(this@EditContactActivity, "\u5934\u50cf\u8bfb\u53d6\u5931\u8d25", Toast.LENGTH_SHORT).show()
                        return
                    }
                    selectedAvatarUri = avatarUri
                    avatar.clearColorFilter()
                    avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                    avatar.setImageURI(Uri.parse(avatarUri))
                }

                override fun onCancel() = Unit
            })
    }

    private fun copyAvatarToPrivateFile(sourcePath: String?): String? {
        if (sourcePath.isNullOrBlank()) return null
        return try {
            val sourceUri = Uri.parse(sourcePath)
            val ext = avatarExtension(sourcePath)
            val dir = File(filesDir, "avatars").apply { mkdirs() }
            val file = File(dir, "avatar_${System.currentTimeMillis()}.$ext")
            val input = when {
                sourceUri.scheme == "file" -> File(sourceUri.path ?: return null).inputStream()
                sourceUri.scheme == null -> File(sourcePath).inputStream()
                else -> contentResolver.openInputStream(sourceUri) ?: return null
            }
            input.use {
                FileOutputStream(file).use { output -> it.copyTo(output) }
            }
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun avatarExtension(path: String): String {
        val ext = path.substringBefore('?')
            .substringAfterLast('.', "")
            .lowercase(Locale.US)
        return when (ext) {
            "png", "webp", "jpg", "jpeg" -> if (ext == "jpeg") "jpg" else ext
            else -> "jpg"
        }
    }

    private fun loadContacts(): List<PhoneContact> {
        val list = mutableListOf<PhoneContact>()
        val raw = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .getString(MainActivity.CONTACTS_KEY, "[]") ?: "[]"
        val array = JSONArray(raw)
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            list.add(
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
        return list
    }

    private fun saveContacts(contacts: List<PhoneContact>) {
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
        getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(MainActivity.CONTACTS_KEY, array.toString())
            .apply()
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

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
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

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else dp(24)
    }

    companion object {
        private const val BRAND = 0xFF0891B2.toInt()
        private const val TEXT = 0xFF0F172A.toInt()
        private const val MUTED = 0xFF64748B.toInt()
        private const val SURFACE = 0xFFF8FAFC.toInt()
    }
}
