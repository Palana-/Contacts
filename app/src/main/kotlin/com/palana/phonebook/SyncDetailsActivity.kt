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
                                .size(54.dp)
                                .avatarChangeBorder(detail)
                                .padding(if (detail.isAvatarChanged()) 1.dp else 0.dp)
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
                            Text(detail.name, color = detail.fieldColor(PhoneSyncField.NAME), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(groupedPhoneNumber(detail.phone), color = detail.fieldColor(PhoneSyncField.PHONE), maxLines = 1)
                    }
                }
            }
        }
    }

    private fun Modifier.avatarChangeBorder(detail: SyncDetail): Modifier {
        return if (detail.isAvatarChanged()) {
            border(1.dp, detail.toneColor(), RoundedCornerShape(11.dp))
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
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                SyncDetail(
                    type = item.optString("type"),
                    name = item.optString("name"),
                    phone = item.optString("phone"),
                    avatarUri = item.optString("avatarUri").ifBlank { null },
                    tone = item.optString("tone", PhoneSyncTone.ADD),
                    field = item.optString("field", PhoneSyncField.CONTACT)
                )
            }
        }.getOrDefault(emptyList())
    }

    private data class SyncDetail(
        val type: String,
        val name: String,
        val phone: String,
        val avatarUri: String?,
        val tone: String,
        val field: String
    ) {
        fun title(): String = type.replaceFirst("APP", "APP-").replaceFirst("系统", "系统-")
        fun shouldShowName(): Boolean = name.isNotBlank() && name != phone
        fun toneColor(): Color = if (tone == PhoneSyncTone.UPDATE) DangerColor else Brand
        fun isAvatarChanged(): Boolean = field == PhoneSyncField.AVATAR || field == PhoneSyncField.CONTACT
        fun fieldColor(targetField: String): Color {
            return if (field == targetField || field == PhoneSyncField.CONTACT) toneColor() else TextColor
        }
    }

    companion object {
        const val EXTRA_DETAILS = "details"
        private val Brand = Color(0xFF0891B2)
        private val TitleColor = Color(0xFF334155)
        private val DangerColor = Color(0xFFDC2626)
        private val TextColor = Color(0xFF0F172A)
        private val SurfaceColor = Color(0xFFF8FAFC)
    }
}
