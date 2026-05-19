package com.palana.phonebook

import android.os.Bundle
import android.content.Intent
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.palana.phonebook.data.AvatarStorage
import com.palana.phonebook.data.ContactRepository
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EditContactActivity : ComponentActivity() {
    private val repository by lazy { ContactRepository.getInstance(this) }
    private val avatarStorage by lazy { AvatarStorage(this) }

    private var contactId: String? = null
    private var createdAt: Long = 0L
    private var recentAt: Long = 0L
    private var originalPhone: String = ""
    private var name by mutableStateOf("")
    private var phone by mutableStateOf("")
    private var avatarUri by mutableStateOf<String?>(null)

    private val pickAvatarLauncher = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        lifecycleScope.launch {
            val localUri = withContext(Dispatchers.IO) { avatarStorage.copyPickedAvatar(uri?.toString()) }
            if (uri != null && localUri == null) {
                toast("头像读取失败")
            } else if (localUri != null) {
                avatarUri = localUri
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        contactId = intent.getStringExtra(EXTRA_ID)
        name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        phone = normalizeMainlandMobileNumber(intent.getStringExtra(EXTRA_PHONE).orEmpty())
        originalPhone = phone
        avatarUri = intent.getStringExtra(EXTRA_AVATAR_URI)
        createdAt = intent.getLongExtra(EXTRA_CREATED_AT, System.currentTimeMillis())
        recentAt = intent.getLongExtra(EXTRA_RECENT_AT, 0L)

        setContent {
            EditContactTheme {
                EditContactScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditContactScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceColor)
        ) {
            TopAppBar(
                title = {
                    Text(
                        if (contactId == null) "新增联系人" else "编辑联系人",
                        fontWeight = FontWeight.ExtraBold,
                        color = TextColor
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Brand)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AvatarPicker()
                Spacer(Modifier.height(22.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("姓名") },
                    singleLine = true,
                    trailingIcon = {
                        if (name.isNotEmpty()) {
                            IconButton(onClick = { name = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除姓名", tint = MutedColor)
                            }
                        }
                    },
                    textStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = normalizeMainlandMobileNumber(it) },
                    label = { Text("电话") },
                    singleLine = true,
                    trailingIcon = {
                        if (phone.isNotEmpty()) {
                            IconButton(onClick = { phone = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "清除电话", tint = MutedColor)
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    visualTransformation = PhoneNumberVisualTransformation(),
                    textStyle = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.Bold),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(24.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { finish() },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0F2F1), contentColor = Brand),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text("取消", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = { saveContact() },
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Brand),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("保存", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }

    @Composable
    private fun AvatarPicker() {
        Box(
            modifier = Modifier
                .size(136.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color.White)
                .clickable {
                    pickAvatarLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val avatar = avatarUri
            if (avatar.isNullOrBlank()) {
                Icon(Icons.Default.Person, contentDescription = "选择头像", tint = MutedColor, modifier = Modifier.size(86.dp))
                Icon(
                    Icons.Default.Photo,
                    contentDescription = null,
                    tint = Brand,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(12.dp)
                )
            } else {
                AsyncImage(
                    model = avatar,
                    contentDescription = "头像",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    private fun EditContactTheme(content: @Composable () -> Unit) {
        val view = LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
        }
        MaterialTheme(
            colorScheme = androidx.compose.material3.lightColorScheme(
                primary = Brand,
                surface = SurfaceColor,
                background = SurfaceColor
            ),
            typography = PhoneBookTypography,
            content = content
        )
    }

    private fun saveContact() {
        val trimmedPhone = normalizeMainlandMobileNumber(phone)
        val trimmedName = name.trim()
        if (trimmedPhone.isEmpty()) {
            toast("请填写电话")
            return
        }
        if (trimmedName.isEmpty() && avatarUri.isNullOrBlank()) {
            toast("请填写姓名或选择头像")
            return
        }

        val id = contactId ?: UUID.randomUUID().toString()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.upsertContact(
                    PhoneContact(
                        id = id,
                        name = trimmedName,
                        phone = trimmedPhone,
                        avatarUri = avatarUri,
                        createdAt = if (contactId == null) System.currentTimeMillis() else createdAt,
                        recentAt = recentAt
                    )
                )
            }
            setResult(
                RESULT_OK,
                Intent()
                    .putExtra(EXTRA_ID, id)
                    .putExtra(EXTRA_ORIGINAL_PHONE, originalPhone)
                    .putExtra(EXTRA_NAME, trimmedName)
                    .putExtra(EXTRA_PHONE, trimmedPhone)
                    .putExtra(EXTRA_AVATAR_URI, avatarUri)
                    .putExtra(EXTRA_CREATED_AT, if (contactId == null) System.currentTimeMillis() else createdAt)
                    .putExtra(EXTRA_RECENT_AT, recentAt)
            )
            finish()
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_ORIGINAL_PHONE = "originalPhone"
        const val EXTRA_NAME = "name"
        const val EXTRA_PHONE = "phone"
        const val EXTRA_AVATAR_URI = "avatarUri"
        const val EXTRA_CREATED_AT = "createdAt"
        const val EXTRA_RECENT_AT = "recentAt"

        private val Brand = Color(0xFF0891B2)
        private val TextColor = Color(0xFF0F172A)
        private val MutedColor = Color(0xFF64748B)
        private val SurfaceColor = Color(0xFFF8FAFC)
    }
}
