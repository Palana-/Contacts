package com.palana.phonebook

import android.os.Bundle
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import org.json.JSONArray

class SyncDetailsActivity : ComponentActivity() {
    private val details by lazy { parseDetails(intent.getStringExtra(EXTRA_DETAILS).orEmpty()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            SyncDetailsTheme {
                SyncDetailsScreen()
            }
        }
    }

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Composable
    private fun SyncDetailsScreen() {
        Column(modifier = Modifier.fillMaxSize().background(SurfaceColor)) {
            TopAppBar(
                title = { Text("同步详情", fontWeight = FontWeight.ExtraBold, color = TextColor) },
                navigationIcon = {
                    IconButton(onClick = { finish() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Brand)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White)
            )
            LazyColumn(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                items(details) { detail ->
                    SyncDetailRow(detail)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }

    @Composable
    private fun SyncDetailRow(detail: SyncDetail) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                Text(detail.title(), color = TitleColor, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (!detail.avatarUri.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .size(if (detail.isAvatarChanged()) 58.dp else 54.dp)
                                .avatarChangeBorder(detail)
                                .padding(if (detail.isAvatarChanged()) 2.dp else 0.dp)
                                .clip(RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = detail.avatarUri,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    } else {
                        Spacer(Modifier.size(54.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        if (detail.shouldShowName()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(detail.name, color = detail.fieldColor(PhoneSyncField.NAME), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (detail.shouldShowOldName()) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        detail.oldName.orEmpty(),
                                        color = MutedColor,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textDecoration = TextDecoration.LineThrough
                                    )
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(groupedPhoneNumber(detail.phone), color = detail.fieldColor(PhoneSyncField.PHONE), maxLines = 1)
                            if (detail.shouldShowOldPhone()) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    groupedPhoneNumber(detail.oldPhone.orEmpty()),
                                    color = MutedColor,
                                    maxLines = 1,
                                    textDecoration = TextDecoration.LineThrough
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Modifier.avatarChangeBorder(detail: SyncDetail): Modifier {
        return if (detail.isAvatarChanged()) {
            border(1.dp, detail.toneColor(), RoundedCornerShape(12.dp))
        } else {
            this
        }
    }

    @Composable
    private fun SyncDetailsTheme(content: @Composable () -> Unit) {
        val view = LocalView.current
        androidx.compose.runtime.SideEffect {
            val window = (view.context as? ComponentActivity)?.window ?: return@SideEffect
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.WHITE
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = true
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = true
        }
        MaterialTheme(
            colorScheme = androidx.compose.material3.lightColorScheme(primary = Brand, surface = SurfaceColor, background = SurfaceColor),
            typography = PhoneBookTypography,
            content = content
        )
    }

    private fun parseDetails(raw: String): List<SyncDetail> {
        return runCatching {
            val array = JSONArray(raw)
            val rawDetails = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                RawSyncDetail(
                    type = item.optString("type"),
                    name = item.optString("name"),
                    phone = item.optString("phone"),
                    avatarUri = item.optString("avatarUri").ifBlank { null },
                    tone = item.optString("tone", PhoneSyncTone.ADD),
                    field = item.optString("field", PhoneSyncField.CONTACT),
                    target = item.optString("target").ifBlank {
                        if (item.optString("type").startsWith("APP")) PhoneSyncTarget.APP else PhoneSyncTarget.SYSTEM
                    },
                    oldName = item.optString("oldName").ifBlank { null },
                    oldPhone = item.optString("oldPhone").ifBlank { null }
                )
            }
            rawDetails
                .groupByStable { it.target to it.phone }
                .map { (_, changes) -> SyncDetail.from(changes) }
                .sortedWith(compareBy<SyncDetail> { if (it.target == PhoneSyncTarget.SYSTEM) 0 else 1 })
        }.getOrDefault(emptyList())
    }

    private fun <T, K> List<T>.groupByStable(keySelector: (T) -> K): List<Pair<K, List<T>>> {
        val groups = linkedMapOf<K, MutableList<T>>()
        forEach { item -> groups.getOrPut(keySelector(item)) { mutableListOf() }.add(item) }
        return groups.map { it.key to it.value }
    }

    private data class RawSyncDetail(
        val type: String,
        val name: String,
        val phone: String,
        val avatarUri: String?,
        val tone: String,
        val field: String,
        val target: String,
        val oldName: String?,
        val oldPhone: String?
    )

    private data class SyncDetail(
        val types: List<String>,
        val name: String,
        val phone: String,
        val avatarUri: String?,
        val fields: Set<String>,
        val toneByField: Map<String, String>,
        val target: String,
        val oldName: String?,
        val oldPhone: String?
    ) {
        fun title(): String {
            val prefix = if (target == PhoneSyncTarget.APP) "APP" else "系统"
            val actions = types.map { it.removePrefix("APP").removePrefix("系统").removePrefix("-") }.distinct()
            return "$prefix-${actions.joinToString(" / ")}"
        }
        fun shouldShowName(): Boolean = name.isNotBlank() && (name != phone || fields.contains(PhoneSyncField.NAME))
        fun shouldShowOldName(): Boolean = fields.contains(PhoneSyncField.NAME) && toneByField[PhoneSyncField.NAME] == PhoneSyncTone.UPDATE && !oldName.isNullOrBlank() && oldName != name
        fun shouldShowOldPhone(): Boolean = fields.contains(PhoneSyncField.PHONE) && toneByField[PhoneSyncField.PHONE] == PhoneSyncTone.UPDATE && !oldPhone.isNullOrBlank() && phoneDigits(oldPhone) != phoneDigits(phone)
        fun toneColor(): Color = if ((toneByField[PhoneSyncField.AVATAR] ?: toneByField[PhoneSyncField.CONTACT]) == PhoneSyncTone.UPDATE) DangerColor else Brand
        fun isAvatarChanged(): Boolean = fields.contains(PhoneSyncField.AVATAR) || fields.contains(PhoneSyncField.CONTACT)
        fun fieldColor(targetField: String): Color {
            val tone = toneByField[targetField] ?: toneByField[PhoneSyncField.CONTACT] ?: return TextColor
            return if (tone == PhoneSyncTone.UPDATE) DangerColor else Brand
        }

        companion object {
            private fun phoneDigits(value: String?): String = value.orEmpty().filter(Char::isDigit)

            fun from(changes: List<RawSyncDetail>): SyncDetail {
                val nameChange = changes.lastOrNull { it.field == PhoneSyncField.NAME }
                val phoneChange = changes.lastOrNull { it.field == PhoneSyncField.PHONE }
                return SyncDetail(
                    types = changes.map { it.type },
                    name = nameChange?.name ?: changes.firstNotNullOfOrNull { it.name.takeIf(String::isNotBlank) }.orEmpty(),
                    phone = phoneChange?.phone ?: changes.lastOrNull()?.phone.orEmpty(),
                    avatarUri = changes.firstNotNullOfOrNull { it.avatarUri },
                    fields = changes.map { it.field }.toSet(),
                    toneByField = changes.associate { it.field to it.tone },
                    target = changes.firstOrNull()?.target.orEmpty(),
                    oldName = nameChange?.oldName,
                    oldPhone = phoneChange?.oldPhone
                )
            }
        }
    }

    companion object {
        const val EXTRA_DETAILS = "details"
        private val Brand = Color(0xFF0891B2)
        private val TitleColor = Color(0xFF334155)
        private val DangerColor = Color(0xFFDC2626)
        private val TextColor = Color(0xFF0F172A)
        private val MutedColor = Color(0xFF94A3B8)
        private val SurfaceColor = Color(0xFFF8FAFC)
    }
}
