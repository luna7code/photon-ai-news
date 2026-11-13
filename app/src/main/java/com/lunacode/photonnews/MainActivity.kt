package com.lunacode.photonnews

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.lunacode.photonnews.data.NewsItem
import com.lunacode.photonnews.data.NewsRepository
import com.lunacode.photonnews.data.NewsStore
import com.lunacode.photonnews.worker.NewsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailySync()
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { PhotonApp() }
    }
    private fun scheduleDailySync() {
        val request = PeriodicWorkRequestBuilder<NewsWorker>(24, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("photon_daily_news", ExistingPeriodicWorkPolicy.KEEP, request)
    }
}

@Composable
private fun PhotonApp() {
    val context = LocalContext.current
    val store = remember { NewsStore(context) }
    var news by remember { mutableStateOf(store.all()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Ежедневное обновление включено") }
    val prefs = remember { context.getSharedPreferences("photon", 0) }
    var lastUpdate by remember { mutableLongStateOf(prefs.getLong("last_update", 0L)) }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color.White, onPrimary = Color.Black, background = Color(0xFF080808),
        surface = Color(0xFF151515), onSurface = Color.White, secondary = Color(0xFFC2C2C2)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF080808)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column {
                        Text("PHOTON", fontSize = 13.sp, letterSpacing = 4.sp, color = Color(0xFFBDBDBD), fontWeight = FontWeight.Medium)
                        Text("AI NEWS", fontSize = 30.sp, letterSpacing = 1.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    FilledTonalIconButton(onClick = {
                        if (!loading) {
                            loading = true; status = "Проверяем источники…"
                            (context as? ComponentActivity)?.lifecycleScope?.launch {
                                val result = withContext(Dispatchers.IO) { NewsRepository.fetchImportant().also { store.upsert(it) }; store.all() }
                                news = result; lastUpdate = System.currentTimeMillis()
                                prefs.edit().putLong("last_update", lastUpdate).apply()
                                status = "Обновлено · найдено ${result.size} значимых публикаций"
                                loading = false
                            }
                        }
                    }, enabled = !loading, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = Color(0xFF252525), contentColor = Color.White)) {
                        if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = Color.White)
                        else Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text("ТОЛЬКО ВАЖНОЕ", fontSize = 11.sp, letterSpacing = 2.sp, color = Color(0xFFBDBDBD), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("Главные события искусственного интеллекта — без информационного шума.", color = Color(0xFF9D9D9D), fontSize = 14.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).background(Color(0xFFBDBDBD), RoundedCornerShape(50)))
                    Spacer(Modifier.width(8.dp))
                    Text(status, color = Color(0xFF888888), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (lastUpdate > 0) Text("Последняя проверка: ${SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale("ru")).format(Date(lastUpdate))}", color = Color(0xFF666666), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                Spacer(Modifier.height(18.dp))
                HorizontalDivider(color = Color(0xFF292929))
                if (news.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Пока нет выпусков", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(8.dp))
                            Text("Нажмите обновить, чтобы собрать первые новости.", color = Color(0xFF888888), fontSize = 13.sp)
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(news, key = { it.id }) { item -> NewsCard(item) }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(16.dp)).clickable {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
    }.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.source.uppercase(Locale.ROOT), color = Color(0xFFBDBDBD), fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(SimpleDateFormat("dd MMM · HH:mm", Locale("ru")).format(Date(item.publishedAt)), color = Color(0xFF777777), fontSize = 10.sp)
        }
        Spacer(Modifier.height(9.dp))
        Text(item.title, color = Color.White, fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold)
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(7.dp))
            Text(item.summary, color = Color(0xFFB0B0B0), fontSize = 13.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(10.dp))
        Text("ЧИТАТЬ ИСТОЧНИК  ↗", color = Color.White, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Medium)
    }
}
