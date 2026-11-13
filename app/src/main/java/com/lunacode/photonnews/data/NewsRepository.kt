package com.lunacode.photonnews.data

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

object NewsRepository {
    private data class Feed(val name: String, val url: String)
    private val feeds = listOf(
        Feed("OpenAI", "https://openai.com/news/rss.xml"),
        Feed("Google AI", "https://blog.google/technology/ai/rss/"),
        Feed("TechCrunch AI", "https://techcrunch.com/category/artificial-intelligence/feed/"),
        Feed("Hugging Face", "https://huggingface.co/blog/feed.xml"),
        Feed("MIT Technology Review", "https://www.technologyreview.com/topic/artificial-intelligence/feed/")
    )

    suspend fun fetchImportant(): List<NewsItem> = withContext(Dispatchers.IO) {
        val selected = feeds.flatMap { feed -> runCatching { parseFeed(feed) }.getOrDefault(emptyList()) }
            .distinctBy { it.id }
            .filter { it.score >= 3 && it.publishedAt >= System.currentTimeMillis() - 10L * 24 * 60 * 60 * 1000 }
            .sortedWith(compareByDescending<NewsItem> { it.score }.thenByDescending { it.publishedAt })

        // On-device English-to-Russian translation; no API key or external LLM service is required.
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
        try {
            translator.downloadModelIfNeeded().await()
            selected.map { item ->
                item.copy(
                    title = runCatching { translator.translate(item.title).await() }.getOrDefault(item.title),
                    summary = runCatching { translator.translate(item.summary).await() }.getOrDefault(item.summary)
                )
            }
        } catch (_: Exception) {
            selected
        } finally {
            translator.close()
        }
    }

    private fun parseFeed(feed: Feed): List<NewsItem> {
        val connection = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000; readTimeout = 15000
            setRequestProperty("User-Agent", "PHOTON-AI-NEWS/1.0 (Android RSS reader)")
        }
        try {
            connection.inputStream.use { input ->
                val parser = Xml.newPullParser().apply { setInput(input, null) }
                val items = mutableListOf<NewsItem>()
                var event = parser.eventType
                var insideItem = false
                var title = ""; var link = ""; var description = ""; var date = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> {
                            when (parser.name.lowercase()) {
                                "item", "entry" -> { insideItem = true; title = ""; link = ""; description = ""; date = "" }
                                "title" -> if (insideItem) title = parser.nextText().trim()
                                "link" -> if (insideItem) {
                                    val href = parser.getAttributeValue(null, "href")
                                    link = (href ?: parser.nextText()).trim()
                                }
                                "description", "summary", "content" -> if (insideItem) description = parser.nextText().trim()
                                "pubdate", "published", "updated", "date" -> if (insideItem) date = parser.nextText().trim()
                            }
                        }
                        XmlPullParser.END_TAG -> if (parser.name.equals("item", true) || parser.name.equals("entry", true)) {
                            if (title.isNotBlank() && link.startsWith("http")) {
                                val clean = description.replace(Regex("<[^>]*>"), " ").replace(Regex("\\s+"), " ").take(500)
                                val score = significanceScore(title + " " + clean)
                                val published = parseDate(date)
                                val id = sha256(link)
                                items += NewsItem(id, title, clean.ifBlank { "Откройте источник, чтобы прочитать подробности." }, link, feed.name, published, score)
                            }
                            insideItem = false
                        }
                    }
                    event = parser.next()
                }
                return items
            }
        } finally { connection.disconnect() }
    }

    // Transparent editorial heuristic: this is a first-release relevance filter, not an LLM judgment.
    private fun significanceScore(text: String): Int {
        val s = text.lowercase()
        val high = listOf("launches", "released", "release", "announces", "announced", "breakthrough", "open source", "funding", "acquisition", "partnership", "regulation", "safety", "benchmark", "frontier model", "new model", "research paper", "researchers", "state-of-the-art", "объявил", "выпустил", "запустил", "представил", "открытая модель", "открытый исходный код", "прорыв", "инвестиции", "финансирование", "поглощение", "партнёрство", "регулирование", "безопасность", "исследование", "новая модель", "бенчмарк")
        val strong = listOf("gpt-", "claude", "gemini", "llama", "deepseek", "reasoning", "multimodal", "agent", "robotics", "chips", "gpu", "чип", "робот", "мультимодаль")
        val weak = listOf("opinion", "podcast", "sponsored", "weekly roundup", "how to", "guide", "мнение", "подкаст", "реклама", "инструкция")
        var score = high.count { s.contains(it) } * 3 + strong.count { s.contains(it) }
        if (weak.any { s.contains(it) }) score -= 4
        if (text.length < 45) score -= 2
        return score
    }

    private fun parseDate(raw: String): Long {
        if (raw.isBlank()) return System.currentTimeMillis()
        return runCatching { java.time.ZonedDateTime.parse(raw, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { java.time.Instant.parse(raw).toEpochMilli() }
            .getOrElse { System.currentTimeMillis() }
    }
    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
