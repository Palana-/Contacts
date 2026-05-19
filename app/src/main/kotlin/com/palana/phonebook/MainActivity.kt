package com.palana.phonebook

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.Window
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.palana.phonebook.data.AvatarStorage
import com.palana.phonebook.data.ContactRepository
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.text.Collator
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    private val repository by lazy { ContactRepository.getInstance(this) }
    private val avatarStorage by lazy { AvatarStorage(this) }
    private val gbkCharset = Charset.forName("GBK")
    private val chineseCollator = Collator.getInstance(Locale.CHINA)

    private var contacts by mutableStateOf<List<PhoneContact>>(emptyList())
    private var selectedContact by mutableStateOf<PhoneContact?>(null)
    private var contactPendingDelete by mutableStateOf<PhoneContact?>(null)
    private var progressMessage by mutableStateOf<String?>(null)
    private var pendingCallNumber: String? = null

    private val editContactLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) loadContacts()
    }

    private val pickMigrationLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        runImportMigrationPackage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        cleanMigrationExportCache()
        setContent {
            PhoneBookTheme {
                PhoneBookApp()
            }
        }
        loadContacts()
    }

    private val callPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCallNumber?.let { makeCallNow(it) } else toast("需要电话权限才能拨号")
    }

    private val readContactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runImportFromPhoneContacts() else toast("需要通讯录读取权限")
    }

    private val writeContactsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runExportToPhoneContacts() else toast("需要通讯录写入权限")
    }

    @Composable
    private fun PhoneBookApp() {
        ContactHomeScreen()

        selectedContact?.let { contact ->
            ContactDetailDialog(
                contact = contact,
                onDismiss = { selectedContact = null },
                onCall = {
                    selectedContact = null
                    callContact(contact)
                },
                onEdit = {
                    selectedContact = null
                    openEditor(it)
                },
                onDelete = {
                    selectedContact = null
                    contactPendingDelete = contact
                }
            )
        }

        contactPendingDelete?.let { contact ->
            DeleteConfirmDialog(
                contact = contact,
                onDismiss = { contactPendingDelete = null },
                onConfirm = {
                    contactPendingDelete = null
                    deleteContact(contact)
                }
            )
        }

        progressMessage?.let { message ->
            AlertDialog(
                onDismissRequest = {},
                confirmButton = {},
                text = {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text(message, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ContactHomeScreen() {
        var menuOpen by remember { mutableStateOf(false) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(SurfaceColor)
        ) {
            TopAppBar(
                title = {
                    Text("电话本", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = TextColor)
                },
                actions = {
                    IconButton(onClick = { openEditor(null) }) {
                        Icon(Icons.Default.Add, contentDescription = "新增联系人", tint = Brand)
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreHoriz, contentDescription = "更多", tint = Brand)
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(text = { Text("从手机通讯录导入") }, onClick = {
                                menuOpen = false
                                requestReadContactsThenImport()
                            })
                            DropdownMenuItem(text = { Text("写入手机通讯录") }, onClick = {
                                menuOpen = false
                                requestWriteContactsThenExport()
                            })
                            DropdownMenuItem(text = { Text("导出迁移包") }, onClick = {
                                menuOpen = false
                                exportMigrationPackage()
                            })
                            DropdownMenuItem(text = { Text("导入迁移包") }, onClick = {
                                menuOpen = false
                                pickMigrationLauncher.launch(arrayOf("*/*"))
                            })
                            DropdownMenuItem(text = { Text("同步占位接口") }, onClick = {
                                menuOpen = false
                                syncNow()
                            }, leadingIcon = { Icon(Icons.Default.Sync, null) })
                            DropdownMenuItem(text = { Text("版本 ${BuildConfig.VERSION_NAME}") }, onClick = {})
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )

            if (contacts.isEmpty()) {
                EmptyContacts(
                    modifier = Modifier.fillMaxSize(),
                    onAdd = { openEditor(null) }
                )
            } else {
                ContactGrid(
                    contacts = buildContactItems(),
                    onContactClick = { selectedContact = it },
                    modifier = Modifier.fillMaxSize().navigationBarsPadding()
                )
            }
        }
    }

    @Composable
    private fun EmptyContacts(modifier: Modifier, onAdd: () -> Unit) {
        Column(
            modifier = modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MutedColor, modifier = Modifier.size(72.dp))
            Spacer(Modifier.height(16.dp))
            Text("暂无联系人", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = TextColor)
            Spacer(Modifier.height(14.dp))
            Button(onClick = onAdd, colors = ButtonDefaults.buttonColors(containerColor = Brand)) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("添加联系人", fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun ContactGrid(contacts: List<PhoneContact>, onContactClick: (PhoneContact) -> Unit, modifier: Modifier = Modifier) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier,
            contentPadding = PaddingValues(2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(
                contacts,
                key = { it.id },
                span = { GridItemSpan(if (it.avatarUri.isNullOrBlank()) 2 else 1) }
            ) { contact ->
                if (contact.avatarUri.isNullOrBlank()) {
                    CompactContactRow(contact = contact, onClick = { onContactClick(contact) })
                } else {
                    AvatarContactTile(contact = contact, onClick = { onContactClick(contact) })
                }
            }
        }
    }

    @Composable
    private fun AvatarContactTile(contact: PhoneContact, onClick: () -> Unit) {
        Card(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
            shape = RoundedCornerShape(18.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Box(modifier = Modifier.background(gradientFor(contact))) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(contact.avatarUri).crossfade(true).build(),
                    contentDescription = contact.displayName(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                )
                Text(
                    text = contact.displayName(),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(34.dp)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 2.dp, vertical = 4.dp)
                )
            }
        }
    }

    @Composable
    private fun CompactContactRow(contact: PhoneContact, onClick: () -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color.White)
                .clickable(onClick = onClick)
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .height(44.dp)
                    .width(136.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(gradientFor(contact))
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    contact.displayName(),
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(Modifier.width(12.dp))
            Spacer(Modifier.weight(1f))
            PhoneNumberText(contact.phone)
        }
    }

    @Composable
    private fun PhoneNumberText(phone: String) {
        val groups = remember(phone) { phoneNumberGroups(phone) }
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            groups.forEachIndexed { groupIndex, group ->
                if (groupIndex > 0) Spacer(Modifier.width(3.dp))
                group.forEach { digit ->
                    Box(
                        modifier = Modifier.width(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = digit.toString(),
                            color = TextColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            maxLines = 1,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun DeleteConfirmDialog(contact: PhoneContact, onDismiss: () -> Unit, onConfirm: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text("删除联系人", fontWeight = FontWeight.ExtraBold, color = TextColor)
            },
            text = {
                Column {
                    Text("确定删除？", color = TextColor, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(contact.displayName(), color = TextColor, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                }
            },
            confirmButton = {
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerColor, contentColor = Color.White)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("删除", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消", fontWeight = FontWeight.Bold, color = Brand)
                }
            }
        )
    }

    @Composable
    private fun ContactDetailDialog(
        contact: PhoneContact,
        onDismiss: () -> Unit,
        onCall: () -> Unit,
        onEdit: (PhoneContact) -> Unit,
        onDelete: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            confirmButton = {},
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "关闭", tint = MutedColor)
                        }
                    }
                    ContactAvatar(contact = contact, size = 136)
                    Spacer(Modifier.height(14.dp))
                    Text(contact.displayName(), fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = TextColor, textAlign = TextAlign.Center)
                    Text(groupedPhoneNumber(contact.phone), fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, color = Color.Black, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(18.dp))
                    DialogAction("拨号", Icons.Default.Call, CallGreen, Color.White, onCall)
                    DialogAction("编辑", Icons.Default.Edit, Color(0xFFE0F2F1), Brand) { onEdit(contact) }
                    DialogAction("删除", Icons.Default.Delete, DangerColor, Color.White, onDelete)
                }
            }
        )
    }

    @Composable
    private fun ContactAvatar(contact: PhoneContact, size: Int) {
        Box(
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(26.dp)).background(gradientFor(contact)),
            contentAlignment = Alignment.Center
        ) {
            if (contact.avatarUri.isNullOrBlank()) {
                Text(contact.displayName().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = (size / 2.5).sp)
            } else {
                AsyncImage(
                    model = contact.avatarUri,
                    contentDescription = contact.displayName(),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    @Composable
    private fun DialogAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, background: Color, foreground: Color, onClick: () -> Unit) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(54.dp).padding(bottom = 10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = background, contentColor = foreground),
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        }
    }

    @Composable
    private fun PhoneBookTheme(content: @Composable () -> Unit) {
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
                secondary = CallGreen,
                surface = SurfaceColor,
                background = SurfaceColor,
                error = DangerColor
            ),
            content = content
        )
    }

    private fun loadContacts() {
        lifecycleScope.launch {
            contacts = withContext(Dispatchers.IO) { repository.getContacts() }.sortedWith(contactComparator())
        }
    }

    private fun deleteContact(contact: PhoneContact) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.deleteContact(contact.id) }
            loadContacts()
        }
    }

    private fun openEditor(contact: PhoneContact?) {
        val intent = Intent(this, EditContactActivity::class.java)
        contact?.let {
            intent.putExtra(EditContactActivity.EXTRA_ID, it.id)
            intent.putExtra(EditContactActivity.EXTRA_NAME, it.name)
            intent.putExtra(EditContactActivity.EXTRA_PHONE, it.phone)
            intent.putExtra(EditContactActivity.EXTRA_AVATAR_URI, it.avatarUri)
            intent.putExtra(EditContactActivity.EXTRA_CREATED_AT, it.createdAt)
            intent.putExtra(EditContactActivity.EXTRA_RECENT_AT, it.recentAt)
        }
        editContactLauncher.launch(intent)
    }

    private fun saveContacts(snapshot: List<PhoneContact> = contacts) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { repository.replaceContacts(snapshot) }
            loadContacts()
        }
    }

    private fun syncNow() {
        progressMessage = "正在同步..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { repository.syncNow() }
            progressMessage = null
            toast(if (result.success) "已同步 ${result.pushedCount} 个变更" else result.message ?: "同步失败")
            loadContacts()
        }
    }

    private fun buildContactItems(): List<PhoneContact> {
        return contacts.filter { !it.avatarUri.isNullOrBlank() }.sortedWith(contactComparator()) +
            contacts.filter { it.avatarUri.isNullOrBlank() }.sortedWith(contactComparator())
    }

    private fun contactComparator(): Comparator<PhoneContact> {
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
        val initials = charArrayOf('A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'W', 'X', 'Y', 'Z')
        val ranges = intArrayOf(0xB0A1, 0xB0C5, 0xB2C1, 0xB4EE, 0xB6EA, 0xB7A2, 0xB8C1, 0xB9FE, 0xBBF7, 0xBFA6, 0xC0AC, 0xC2E8, 0xC4C3, 0xC5B6, 0xC5BE, 0xC6DA, 0xC8BB, 0xC8F6, 0xCBFA, 0xCDDA, 0xCEF4, 0xD1B9, 0xD4D1, 0xD7FA)
        for (i in 0 until ranges.lastIndex) if (code >= ranges[i] && code < ranges[i + 1]) return initials[i]
        return null
    }

    private fun requestReadContactsThenImport() {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) runImportFromPhoneContacts()
        else readContactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun requestWriteContactsThenExport() {
        if (contacts.isEmpty()) {
            toast("暂无联系人可写入")
            return
        }
        if (checkSelfPermission(Manifest.permission.WRITE_CONTACTS) == PackageManager.PERMISSION_GRANTED) runExportToPhoneContacts()
        else writeContactsPermissionLauncher.launch(Manifest.permission.WRITE_CONTACTS)
    }

    private fun runImportFromPhoneContacts() {
        progressMessage = "正在导入通讯录..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { importFromPhoneContacts() }
            progressMessage = null
            saveContacts(contacts)
            toast("已导入 ${result.first} 个联系人，补齐 ${result.second} 个头像")
        }
    }

    private fun runExportToPhoneContacts() {
        progressMessage = "正在写入手机通讯录..."
        lifecycleScope.launch {
            val exported = withContext(Dispatchers.IO) { exportToPhoneContacts() }
            progressMessage = null
            toast("已同步 $exported 个联系人到手机通讯录")
        }
    }

    private fun importFromPhoneContacts(): Pair<Int, Int> {
        val mutable = contacts.toMutableList()
        val existingByPhone = mutable.associateBy { normalizePhone(it.phone) }.toMutableMap()
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
                        mutable.removeAll { it.id == existing.id }
                        mutable.add(updated)
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
                val contact = PhoneContact(
                    id = id,
                    name = cursor.getString(nameIndex)?.trim().orEmpty().ifBlank { phone },
                    phone = phone,
                    avatarUri = localAvatar
                )
                mutable.add(contact)
                existingByPhone[normalized] = contact
                imported++
            }
        }
        contacts = mutable.sortedWith(contactComparator())
        return imported to avatarUpdated
    }

    private fun exportToPhoneContacts(): Int {
        var exported = 0
        for (contact in contacts) {
            val rawContactId = findRawContactIdByPhone(contact.phone)
            if (rawContactId != null) {
                if (contact.avatarUri != null && upsertSystemContactPhoto(rawContactId, contact.avatarUri)) exported++
                continue
            }
            val operations = arrayListOf<ContentProviderOperation>()
            operations.add(ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI).withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null).withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null).build())
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, contact.name).build())
            operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, contact.phone).withValue(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE).build())
            avatarBytes(contact.avatarUri)?.let { bytes ->
                operations.add(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes).build())
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
        val projection = arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
        contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, projection, null, null, null)?.use { cursor ->
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            val rawIdIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID)
            while (cursor.moveToNext()) {
                if (normalizePhone(cursor.getString(phoneIndex).orEmpty()) == target) return cursor.getLong(rawIdIndex)
            }
        }
        return null
    }

    private fun upsertSystemContactPhoto(rawContactId: Long, avatarUri: String): Boolean {
        val bytes = avatarBytes(avatarUri) ?: return false
        val dataId = findPhotoDataId(rawContactId)
        return try {
            val operations = if (dataId != null) {
                arrayListOf(ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI).withSelection("${ContactsContract.Data._ID}=?", arrayOf(dataId.toString())).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes).build())
            } else {
                arrayListOf(ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValue(ContactsContract.Data.RAW_CONTACT_ID, rawContactId).withValue(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE).withValue(ContactsContract.CommonDataKinds.Photo.PHOTO, bytes).build())
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
            if (cursor.moveToFirst()) return cursor.getLong(cursor.getColumnIndexOrThrow(ContactsContract.Data._ID))
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

    private fun exportMigrationPackage() {
        if (contacts.isEmpty()) {
            toast("暂无联系人可导出")
            return
        }
        progressMessage = "正在导出迁移包..."
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { writeMigrationPackage() }
            progressMessage = null
            if (file == null) toast("导出迁移包失败") else shareExportFile(file, "application/zip", "选择迁移包的发送或保存方式")
        }
    }

    private fun runImportMigrationPackage(uri: Uri) {
        progressMessage = "正在导入迁移包..."
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { importMigrationPackage(uri) }
            progressMessage = null
            if (result == null) {
                toast("导入迁移包失败")
            } else {
                saveContacts(contacts)
                toast("已导入 ${result.first} 个联系人，更新 ${result.second} 个联系人")
            }
        }
    }

    private fun writeMigrationPackage(): File? {
        return try {
            val dir = File(cacheDir, "exports").apply { mkdirs() }
            cleanMigrationExportCache()
            val file = File(dir, "phonebook_backup_${System.currentTimeMillis()}.pbk")
            val manifest = JSONObject().put("version", 1).put("createdAt", System.currentTimeMillis()).put("app", "phonebook")
            val contactArray = JSONArray()
            val avatarEntries = mutableListOf<Pair<String, String>>()
            contacts.forEach { contact ->
                val item = JSONObject().put("id", contact.id).put("name", contact.name).put("phone", contact.phone).put("createdAt", contact.createdAt).put("recentAt", contact.recentAt)
                val avatarName = migrationAvatarName(contact)
                if (avatarName != null) {
                    item.put("avatar", avatarName)
                    avatarEntries.add(contact.avatarUri!! to avatarName)
                } else {
                    item.put("avatar", "")
                }
                contactArray.put(item)
            }
            ZipOutputStream(FileOutputStream(file)).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("contacts.json"))
                zip.write(JSONObject().put("version", 1).put("contacts", contactArray).toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                avatarEntries.forEach { (uriText, entryName) ->
                    openAvatarInputStream(uriText)?.use { input ->
                        zip.putNextEntry(ZipEntry(entryName))
                        input.copyTo(zip)
                        zip.closeEntry()
                    }
                }
            }
            file
        } catch (_: Exception) {
            null
        }
    }

    private fun cleanMigrationExportCache() {
        File(cacheDir, "exports").listFiles { file -> file.name.startsWith("phonebook_backup_") && file.extension == "pbk" }?.forEach { it.delete() }
    }

    private fun importMigrationPackage(uri: Uri): Pair<Int, Int>? {
        val temp = File(cacheDir, "imports/migration_${System.currentTimeMillis()}.pbk").apply { parentFile?.mkdirs() }
        return try {
            contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { output -> input.copyTo(output) } } ?: return null
            val mutable = contacts.toMutableList()
            var imported = 0
            var updated = 0
            val existingByPhone = mutable.associateBy { normalizePhone(it.phone) }.toMutableMap()
            ZipFile(temp).use { zip ->
                val contactsEntry = zip.getEntry("contacts.json") ?: return null
                val root = JSONObject(zip.getInputStream(contactsEntry).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val array = root.getJSONArray("contacts")
                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val phone = item.optString("phone").trim()
                    val normalized = normalizePhone(phone)
                    if (normalized.isEmpty()) continue
                    val existing = existingByPhone[normalized]
                    val avatarEntry = item.optString("avatar").takeIf { it.startsWith("avatars/") }
                    val avatarUri = if (existing?.avatarUri.isNullOrBlank()) avatarEntry?.let { extractMigrationAvatar(zip, it, existing?.id ?: item.optString("id").ifBlank { UUID.randomUUID().toString() }) } else existing?.avatarUri
                    if (existing != null) {
                        val merged = existing.copy(name = existing.name.ifBlank { item.optString("name") }, avatarUri = avatarUri ?: existing.avatarUri)
                        mutable.removeAll { it.id == existing.id }
                        mutable.add(merged)
                        existingByPhone[normalized] = merged
                        updated++
                    } else {
                        val id = item.optString("id").ifBlank { UUID.randomUUID().toString() }
                        val safeId = if (mutable.any { it.id == id }) UUID.randomUUID().toString() else id
                        val contact = PhoneContact(
                            id = safeId,
                            name = item.optString("name"),
                            phone = phone,
                            avatarUri = avatarEntry?.let { extractMigrationAvatar(zip, it, safeId) },
                            createdAt = item.optLong("createdAt", System.currentTimeMillis()),
                            recentAt = item.optLong("recentAt", 0L)
                        )
                        mutable.add(contact)
                        existingByPhone[normalized] = contact
                        imported++
                    }
                }
            }
            contacts = mutable.sortedWith(contactComparator())
            imported to updated
        } catch (_: Exception) {
            null
        } finally {
            temp.delete()
        }
    }

    private fun shareExportFile(file: File, mimeType: String, chooserTitle: String) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, chooserTitle))
    }

    private fun migrationAvatarName(contact: PhoneContact): String? {
        val avatar = contact.avatarUri ?: return null
        val ext = avatarStorage.avatarExtension(avatar)
        return "avatars/${contact.id}.$ext"
    }

    private fun openAvatarInputStream(uriText: String): InputStream? = avatarStorage.openAvatarInputStream(uriText)

    private fun extractMigrationAvatar(zip: ZipFile, entryName: String, contactId: String): String? {
        return try {
            val entry = zip.getEntry(entryName) ?: return null
            val ext = entryName.substringAfterLast('.', "jpg").lowercase(Locale.US).let { if (it in setOf("png", "webp", "jpg", "jpeg")) it else "jpg" }
            val file = File(avatarStorage.avatarDir(), "${contactId}_${System.currentTimeMillis()}.$ext")
            zip.getInputStream(entry).use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
            Uri.fromFile(file).toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun callContact(contact: PhoneContact) {
        val updated = contact.copy(recentAt = System.currentTimeMillis())
        contacts = contacts.filterNot { it.id == contact.id } + updated
        saveContacts(contacts)
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) makeCallNow(contact.phone)
        else {
            pendingCallNumber = contact.phone
            callPermissionLauncher.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun makeCallNow(phone: String) {
        try {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$phone")))
        } catch (_: SecurityException) {
            toast("拨号失败，请检查电话权限")
        }
    }

    private fun decodeAvatarThumbnail(uriText: String): Bitmap? {
        val uri = Uri.parse(uriText)
        return try {
            if (uri.scheme == "file") return decodeSampledFile(uri.path ?: return null, 220, 220)
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
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
            val rawFile = File(avatarStorage.avatarDir(), "$contactId.${sourceUri?.let { avatarStorage.avatarExtension(it) } ?: "jpg"}")
            val copiedRaw = if (!sourceUri.isNullOrBlank()) {
                openAvatarInputStream(sourceUri)?.use { input -> FileOutputStream(rawFile).use { output -> input.copyTo(output) }; true } == true
            } else {
                false
            }
            if (copiedRaw) return Uri.fromFile(rawFile).toString()
            val photoFile = File(avatarStorage.avatarDir(), "$contactId.jpg")
            openSystemContactPhotoStream(systemContactId)?.use { input ->
                FileOutputStream(photoFile).use { output -> input.copyTo(output) }
                return Uri.fromFile(photoFile).toString()
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun openSystemContactPhotoStream(systemContactId: Long?): InputStream? {
        if (systemContactId == null) return null
        val contactUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, systemContactId.toString())
        return ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, contactUri, true)
            ?: ContactsContract.Contacts.openContactPhotoInputStream(contentResolver, contactUri)
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) inSampleSize *= 2
        }
        return inSampleSize
    }

    private fun gradientFor(contact: PhoneContact): Brush {
        val palettes = arrayOf(
            listOf(Color(0xFFEC4899), Color(0xFFF472B6)),
            listOf(Color(0xFF06B6D4), Color(0xFF67E8F9)),
            listOf(Color(0xFFF59E0B), Color(0xFFFDE047)),
            listOf(Color(0xFF10B981), Color(0xFF34D399)),
            listOf(Color(0xFF0891B2), Color(0xFF22D3EE)),
            listOf(Color(0xFF7C3AED), Color(0xFFA78BFA))
        )
        return Brush.horizontalGradient(palettes[abs(contact.displayName().hashCode()) % palettes.size])
    }

    private fun normalizePhone(phone: String): String = phone.filter { it.isDigit() || it == '+' }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    companion object {
        private val Brand = Color(0xFF0891B2)
        private val CallGreen = Color(0xFF07C160)
        private val DangerColor = Color(0xFFDC2626)
        private val TextColor = Color(0xFF0F172A)
        private val MutedColor = Color(0xFF64748B)
        private val SurfaceColor = Color(0xFFF8FAFC)
    }
}
