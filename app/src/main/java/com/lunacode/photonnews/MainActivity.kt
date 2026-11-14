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
import com.lunacode.photonnews.data.NewsEvent
import com.lunacode.photonnews.data.E5ModelAssets
import com.lunacode.photonnews.data.EditorialComposer
import com.lunacode.photonnews.data.EditorialDraft
import com.lunacode.photonnews.data.EditorialAssessment
import com.lunacode.photonnews.data.EditorialIntelligenceV3
import com.lunacode.photonnews.data.NewsIntelligence
import com.lunacode.photonnews.data.NewsItem
import com.lunacode.photonnews.data.NewsRepository
import com.lunacode.photonnews.data.NewsStore
import com.lunacode.photonnews.data.SemanticModelRegistry
import com.lunacode.photonnews.worker.NewsWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Background = Color(0xFF080808)
private val Panel = Color(0xFF151515)
private val Muted = Color(0xFF929292)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        scheduleDailySync()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
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

private val sections = listOf(
    "FEED" to "Лента",
    "EVENTS" to "События",
    "BREAKING" to "Срочно",
    "SOURCES" to "Источники",
    "IMPACT" to "Влияние"
)

private fun categoryLabel(category: String): String = when (category) {
    "PRODUCT" -> "Продукт"
    "RESEARCH" -> "Исследование"
    "BUSINESS" -> "Бизнес"
    "SAFETY" -> "Безопасность"
    "POLICY" -> "Регулирование"
    else -> "Общее"
}

@Composable
private fun PhotonApp() {
    val context = LocalContext.current
    val store = remember { NewsStore(context) }
    var news by remember { mutableStateOf(store.all()) }
    var loading by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Умная лента готова к обновлению") }
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("ALL") }
    var selectedSection by remember { mutableStateOf("FEED") }
    var savedOnly by remember { mutableStateOf(false) }
    var newestFirst by remember { mutableStateOf(false) }
    val prefs = remember { context.getSharedPreferences("photon", 0) }
    var lastUpdate by remember { mutableLongStateOf(prefs.getLong("last_update", 0L)) }
    var semanticModelReady by remember { mutableStateOf(SemanticModelRegistry.isReady()) }
    var semanticModelDownloading by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<NewsEvent>>(emptyList()) }
    var assessments by remember { mutableStateOf<Map<String, EditorialAssessment>>(emptyMap()) }

    LaunchedEffect(news, semanticModelReady) {
        events = withContext(Dispatchers.Default) { NewsIntelligence.buildEvents(news) }
    }
    LaunchedEffect(events) {
        assessments = withContext(Dispatchers.Default) {
            events.associate { it.id to EditorialIntelligenceV3.assess(it) }
        }
    }
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
    val visibleEvents = remember(events, assessments, query, selectedSection) {
        val filtered = events.filter { event ->
            query.isBlank() || listOf(event.headline, event.summary, event.publishers.joinToString(" "))
                .any { it.contains(query.trim(), ignoreCase = true) }
        }
        when (selectedSection) {
            "BREAKING" -> filtered.filter { assessments[it.id]?.isGlobalBreaking == true }
            "IMPACT" -> filtered.sortedByDescending { assessments[it.id]?.impactScore ?: 0 }
            "SOURCES" -> filtered
            else -> filtered
        }
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
                status = "Обновлено · ${result.size} публикаций в архиве"
            } catch (_: Exception) {
                status = "Не удалось обновить ленту. Проверьте подключение и повторите попытку."
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!semanticModelReady) {
            val installed = withContext(Dispatchers.IO) { E5ModelAssets.existing(context) }
            if (installed != null) {
                runCatching {
                    withContext(Dispatchers.Default) { SemanticModelRegistry.install(installed) }
                    semanticModelReady = true
                }.onFailure {
                    status = "Локальная AI-модель не загрузилась. Используется резервный анализ."
                }
            }
        }
        if (news.isEmpty()) refresh()
    }

    fun installSemanticModel() {
        if (semanticModelDownloading || semanticModelReady) return
        semanticModelDownloading = true
        status = "Загрузка multilingual E5 (~135 МБ вместе с токенизатором). Не закрывайте приложение…"
        (context as? ComponentActivity)?.lifecycleScope?.launch {
            try {
                val files = E5ModelAssets.install(context)
                withContext(Dispatchers.Default) { SemanticModelRegistry.install(files) }
                semanticModelReady = true
                status = "Мультиязычная AI-модель установлена и работает на устройстве."
            } catch (error: Exception) {
                status = "Не удалось установить AI-модель: ${error.message ?: "ошибка загрузки"}. Резервный анализ доступен."
            } finally {
                semanticModelDownloading = false
            }
        }
    }

    MaterialTheme(colorScheme = darkColorScheme(
        primary = Color.White, onPrimary = Color.Black,
        background = Background, surface = Panel,
        onSurface = Color.White, secondary = Color(0xFFC2C2C2)
    )) {
        Surface(modifier = Modifier.fillMaxSize(), color = Background) {
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
                            selectedSection = "FEED"
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
                    color = Muted, fontSize = 13.sp, lineHeight = 19.sp
                )
                Spacer(Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    items(sections) { section ->
                        FilterChip(
                            selected = selectedSection == section.first,
                            onClick = {
                                selectedSection = section.first
                                savedOnly = false
                            },
                            label = { Text(section.second, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.White,
                                selectedLabelColor = Color.Black,
                                containerColor = Color(0xFF1B1B1B),
                                labelColor = Color(0xFFBDBDBD)
                            )
                        )
                    }
                }
                if (selectedSection != "FEED") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 3.dp, bottom = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            if (semanticModelReady) "MULTILINGUAL E5 · ON-DEVICE"
                            else "Лексический резервный анализ",
                            color = if (semanticModelReady) Color.White else Muted,
                            fontSize = 9.sp, letterSpacing = 1.sp
                        )
                        TextButton(
                            onClick = { installSemanticModel() },
                            enabled = !semanticModelReady && !semanticModelDownloading
                        ) {
                            if (semanticModelDownloading) {
                                CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.5.dp, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Text("Загрузка…", color = Color.White, fontSize = 10.sp)
                            } else {
                                Text(
                                    if (semanticModelReady) "Модель активна" else "Установить AI · 135 МБ",
                                    color = Color.White, fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 11.dp),
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
                if (selectedSection == "FEED") {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${visibleNews.size} новостей", color = Muted, fontSize = 11.sp)
                        TextButton(onClick = { newestFirst = !newestFirst }) {
                            Text(if (newestFirst) "Сначала новые" else "Сначала важные", color = Color(0xFFCCCCCC), fontSize = 11.sp)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(categories) { category ->
                            FilterChip(
                                selected = selectedCategory == category.first,
                                onClick = { selectedCategory = category.first; savedOnly = false },
                                label = { Text(category.second, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.White,
                                    selectedLabelColor = Color.Black,
                                    containerColor = Color(0xFF1B1B1B),
                                    labelColor = Color(0xFFBDBDBD)
                                )
                            )
                        }
                    }
                } else {
                    Spacer(Modifier.height(8.dp))
                    val count = when (selectedSection) {
                        "BREAKING" -> visibleEvents.count { assessments[it.id]?.isGlobalBreaking == true }
                        else -> visibleEvents.size
                    }
                    Text(
                        when (selectedSection) {
                            "EVENTS" -> "$count сгруппированных событий"
                            "BREAKING" -> "$count глобальных срочных событий"
                            "IMPACT" -> "$count событий · рейтинг влияния"
                            else -> "$count событий · семантическая группировка"
                        },
                        color = Muted, fontSize = 11.sp
                    )
                }
                Spacer(Modifier.height(8.dp))
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
                when {
                    selectedSection == "FEED" && visibleNews.isEmpty() ->
                        EmptyState(if (savedOnly) "Нет сохранённых новостей" else "Ничего не найдено")
                    selectedSection != "FEED" && visibleEvents.isEmpty() ->
                        EmptyState(if (selectedSection == "BREAKING") "Глобальных срочных событий пока нет" else "События не найдены")
                    selectedSection == "FEED" -> {
                        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            items(visibleNews, key = { it.id }) { item ->
                                NewsCard(item = item, onBookmark = {
                                    store.setSaved(item.id, !item.isSaved)
                                    reload()
                                })
                            }
                        }
                    }
                    else -> {
                        LazyColumn(contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(11.dp)) {
                            items(visibleEvents, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    assessment = assessments[event.id],
                                    showSourceEvidence = selectedSection == "SOURCES",
                                    showImpact = selectedSection == "IMPACT"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("Измените фильтр или обновите ленту.", color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun NewsCard(item: NewsItem, onBookmark: () -> Unit) {
    val context = LocalContext.current
    var showDraft by remember(event.id) { mutableStateOf(false) }
    var draft by remember(event.id) { mutableStateOf<EditorialDraft?>(null) }
    var draftLoading by remember(event.id) { mutableStateOf(false) }
    LaunchedEffect(showDraft, event) {
        if (showDraft && draft == null) {
            draftLoading = true
            draft = withContext(Dispatchers.Default) { EditorialComposer.compose(event) }
            draftLoading = false
        }
    }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(15.dp)) {
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
            Text(categoryLabel(item.category), color = Muted, fontSize = 9.sp, letterSpacing = 1.sp)
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

@Composable
private fun EventCard(
    event: NewsEvent,
    assessment: EditorialAssessment?,
    showSourceEvidence: Boolean,
    showImpact: Boolean
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(15.dp)).padding(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (event.isBreaking) "СРОЧНО" else "СОБЫТИЕ",
                color = if (event.isBreaking) Color.White else Muted,
                fontSize = 9.sp, letterSpacing = 1.5.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.weight(1f))
            Text(
                SimpleDateFormat("dd MMM · HH:mm", Locale("ru")).format(Date(event.latestAt)),
                color = Color(0xFF777777), fontSize = 9.sp
            )
        }
        Spacer(Modifier.height(9.dp))
        Text(event.headline, color = Color.White, fontSize = 16.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold)
        if (event.summary.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(event.summary, color = Color(0xFFB0B0B0), fontSize = 12.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        TextButton(
            onClick = { showDraft = !showDraft },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 2.dp)
        ) {
            Text(
                if (showDraft) "СКРЫТЬ РЕДАКЦИОННЫЙ ЧЕРНОВИК" else "СОБРАТЬ РЕДАКЦИОННЫЙ ЧЕРНОВИК",
                color = Color.White, fontSize = 9.sp, letterSpacing = 1.sp
            )
        }
        if (showDraft) {
            Column(
                Modifier.fillMaxWidth().background(Color(0xFF202020), RoundedCornerShape(10.dp)).padding(11.dp)
            ) {
                if (draftLoading) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 1.5.dp, color = Color.White)
                        Spacer(Modifier.width(8.dp))
                        Text("Собираем материалы…", color = Muted, fontSize = 11.sp)
                    }
                } else {
                    draft?.let { editorial ->
                        Text("ЧЕРНОВИК · НЕОБХОДИМА РЕДАКТОРСКАЯ ПРОВЕРКА", color = Color.White, fontSize = 9.sp, letterSpacing = 1.sp)
                        Spacer(Modifier.height(7.dp))
                        Text(editorial.headline, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(5.dp))
                        Text(editorial.lead, color = Color(0xFFD0D0D0), fontSize = 12.sp, lineHeight = 17.sp)
                        editorial.paragraphs.drop(1).forEach { paragraph ->
                            Spacer(Modifier.height(5.dp))
                            Text(paragraph, color = Color(0xFFB0B0B0), fontSize = 11.sp, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("ИСТОЧНИКИ", color = Color.White, fontSize = 9.sp, letterSpacing = 1.sp)
                        editorial.sources.forEach { source ->
                            Text(
                                "↗ ${source.publisher} · открыть оригинал",
                                color = Color(0xFFDDDDDD), fontSize = 10.sp,
                                modifier = Modifier.clickable {
                                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url))) }
                                }.padding(vertical = 3.dp)
                            )
                        }
                        editorial.caveats.forEach { caveat ->
                            Text("• $caveat", color = Color(0xFF999999), fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Text(editorial.method, color = Color(0xFF777777), fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(11.dp))
        assessment?.let { result ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("SIMILARITY ${result.semanticSimilarity}/100", color = Color(0xFFBDBDBD), fontSize = 9.sp, letterSpacing = 1.sp)
                Text("SOURCE DATA ${result.sourceTrust.score}/100", color = Color(0xFFBDBDBD), fontSize = 9.sp, letterSpacing = 1.sp)
                if (showImpact) {
                    Text("IMPACT ${result.impactScore}/100", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                "${result.sourceTrust.label} · ${result.sourceTrust.distinctHostCount} доменов",
                color = Color(0xFFBDBDBD), fontSize = 10.sp, lineHeight = 14.sp
            )
            Text(
                result.sourceTrust.explanation,
                color = Color(0xFF777777), fontSize = 9.sp, lineHeight = 13.sp
            )
            if (result.factConflicts.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Text("ВОЗМОЖНЫЕ РАСХОЖДЕНИЯ", color = Color.White, fontSize = 9.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold)
                result.factConflicts.forEach { conflict ->
                    Text(
                        "${conflict.description} · ${conflict.sources.joinToString(", ")}",
                        color = Color(0xFFBDBDBD), fontSize = 10.sp, lineHeight = 14.sp
                    )
                }
            }
        }
        Text(
            if (assessment?.isGlobalBreaking == true) "GLOBAL BREAKING · высокий приоритет"
            else if (event.isBreaking) "Срочное событие · локальная эвристика"
            else "Редакционная оценка · автоматический анализ",
            color = Color(0xFFBDBDBD), fontSize = 9.sp, letterSpacing = 1.sp
        )
        Text(
            "ИЗДАТЕЛЕЙ: ${event.publisherCount}",
            color = Color.White, fontSize = 10.sp, letterSpacing = 1.sp, fontWeight = FontWeight.SemiBold
        )
        Text(event.publishers.joinToString(" · "), color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        Spacer(Modifier.height(6.dp))
        Text(
            event.verificationLabel,
            color = if (event.publisherCount >= 2) Color(0xFFD8D8D8) else Color(0xFFAAAAAA),
            fontSize = 11.sp, lineHeight = 16.sp
        )
        Text(
            if (showSourceEvidence) "Сопоставление издателей — сигнал для проверки, не фактчекинг."
            else "Количество публикаций не гарантирует независимость или истинность сообщений.",
            color = Color(0xFF777777), fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(8.dp))
        event.articles.forEach { article ->
            Text(
                "↗ ${article.source}: открыть публикацию",
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().clickable {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(article.url))) }
                }.padding(vertical = 5.dp)
            )
        }
    }
}
