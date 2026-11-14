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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

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
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "photon_daily_news", ExistingPeriodicWorkPolicy.KEEP, request
        )
    }

}

private val categories = listOf(
    "ALL" to "Все",
    "PRODUCT" to "Продукты",
    "RESEARCH" to "Исследования",
    "BUSINESS" to "Бизнес",
    "SAFETY" to "Безопасность",
    "POLICY" to "Регулирование",
    "OTHER" to "Другие"
)

@Composable
private fun PhotonApp() {
    val context = LocalContext.current
    val store = remember { NewsStore(context) }
    var news by remember { mutableStateOf(store.all()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Умная лента готова к обновлению") }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var savedOnly by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("photon", 0) }
    var lastUpdate by remember { mutableLongStateOf(prefs.getLong("last_update", 0L)) }

    val visibleNews = remember(news, query, selectedCategory, savedOnly, newestFirst) {
        val filtered = news.filter { item ->
            (selectedCategory == "ALL" || item.category == selectedCategory) &&
                (!savedOnly || item.isSaved) &&
                (query.isBlank() || listOf(item.title, item.summary, item.source)
                    .any { it.contains(query.trim(), ignoreCase = true) })
        }
        if (newestFirst) filtered.sortedByDescending { it.publishedAt }
        else filtered.sortedWith(compareByDescending<NewsItem> { it.score }.thenByDescending { it.publishedAt })
    }

    fun reload() {
        news = store.all()
        lastUpdate = prefs.getLong("last_update", 0L)
    }

    fun refresh() {
        if (loading) return
        loading = true
        status = "Проверяем источники и отбираем важные события…"
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    NewsRepository.fetchImportant().also { store.upsert(it) }
                    store.all()
                }
                news = result
                lastUpdate = System.currentTimeMillis()
                prefs.edit().putLong("last_update", lastUpdate).apply()
                status = "Обновлено · " + result.size + " публикаций в архиве"
            } catch (_: Exception) {
                status = "Не удалось обновить ленту. Проверьте подключение и повторите попытку."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (news.isEmpty()) refresh()
    }


    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        background = Color(0xFF080808), surface = Color(0xFF151515),
        onSurface = Color.White, secondary = Color(0xFFC2C2C2)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF080808)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                Spacer(Modifier.height(24.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        Text("PHOTON", fontSize = 12.sp, letterSpacing = 4.sp, color = Color(0xFFBDBDBD))
                        Text("AI NEWS", fontSize = 30.sp, letterSpacing = 1.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            savedOnly = !savedOnly
                            selectedCategory = "ALL"
                        }) {
                            Icon(if (savedOnly) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null, tint = Color.White)
                            Spacer(Modifier.width(5.dp))
                            Text(if (savedOnly) "Сохранённое" else "Закладки", color = Color.White, fontSize = 12.sp)
                        }
                        FilledTonalIconButton(
                            onClick = { refresh() },
                            enabled = !loading,
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = Color(0xFF252525), contentColor = Color.White
                            )
                        ) {
                            if (loading) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("ТОЛЬКО ВАЖНОЕ", fontSize = 10.sp, letterSpacing = 2.sp, color = Color(0xFFBDBDBD), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "Главные события искусственного интеллекта — без информационного шума.",
                    color = Color(0xFF9D9D9D), fontSize = 13.sp, lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Search, null, tint = Color(0xFF888888), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(9.dp))
                    BasicTextField(
                        value = query, onValueChange = { query = it },
                        modifier = Modifier.weight(1f), singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("Поиск по заголовкам и источникам", color = Color(0xFF777777), fontSize = 13.sp)
                            inner()
                        }
                    )
                    if (query.isNotEmpty()) {
                        TextButton(onClick = { query = "" }) { Text("Очистить", color = Color.LightGray) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(visibleNews.size.toString() + " новостей", color = Color(0xFF888888), fontSize = 11.sp)
                    TextButton(onClick = { newestFirst = !newestFirst }) {
                        Text(if (newestFirst) "Сначала новые" else "Сначала важные", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(categories) { category ->
                        val id = category.first
                        val label = category.second
                        FilterChip(
                            selected = selectedCategory == id,
                            onClick = { selectedCategory = id; savedOnly = false },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF1B1B1B),
                                labelColor = Color(0xFFBDBDBD)
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Color(0xFFBDBDBD), RoundedCornerShape(50)))
                    Spacer(Modifier.width(7.dp))
                    Text(status, color = Color(0xFF888888), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (lastUpdate > 0) {
                    Text(
                        "Последняя проверка: " + SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale("ru")).format(Date(lastUpdate)),
                        color = Color(0xFF666666), fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp)
                    )
                }
                HorizontalDivider(color = Color(0xFF292929), modifier = Modifier.padding(top = 12.dp))
                if (visibleNews.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (savedOnly) "Нет сохранённых новостей" else "Ничего не найдено",
                                color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(7.dp))
                            Text("Измените фильтр или обновите ленту.", color = Color(0xFF888888), fontSize = 12.sp)
                        }
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                        items(visibleNews, key = { it.id }) { item ->
                            NewsCard(item = item, onBookmark = {
                                store.setSaved(item.id, !item.isSaved)
                                reload()
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, onBookmark: () -> Unit) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF151515), RoundedCornerShape(15.dp)).padding(15.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.source.uppercase(Locale.ROOT), color = Color(0xFFBDBDBD), fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(SimpleDateFormat("dd MMM · HH:mm", Locale("ru")).format(Date(item.publishedAt)), color = Color(0xFF777777), fontSize = 9.sp)
            IconButton(onClick = onBookmark, modifier = Modifier.size(30.dp)) {
                Icon(
                    if (item.isSaved) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (item.isSaved) "Убрать из сохранённого" else "Сохранить",
                    tint = if (item.isSaved) Color.White else Color(0xFF888888),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        Spacer(Modifier.height(7.dp))
        Text(item.title, color = Color.White, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        if (item.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(item.summary, color = Color(0xFFB0B0B0), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(9.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.category, color = Color(0xFF888888), fontSize = 9.sp, letterSpacing = 1.sp)
            Spacer(Modifier.weight(1f))
            Text(
                "ЧИТАТЬ ИСТОЧНИК  ↗", color = Color.White, fontSize = 9.sp, letterSpacing = 1.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(item.url))) }
                }.padding(4.dp)
            )
        }
    }
}
