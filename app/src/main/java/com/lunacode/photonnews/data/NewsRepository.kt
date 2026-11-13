package com.lunacode.photonnews.data

import android.text.Html
import android.util.Xml
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URI
import java.security.MessageDigest
import java.text.Normalizer
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Offline editorial pipeline:
 * - RSS/Atom feeds are the only news inputs; no paid AI/API service is called.
 * - ML Kit performs on-device English -> Russian translation after its model is downloaded.
 * - If translation is unavailable, the original English text is retained.
 */
object NewsRepository {
    private data class Feed(val name: String, val url: String, val authority: Int)

    private val feeds = listOf(
        Feed("OpenAI", "https://openai.com/news/rss.xml", 3),
        Feed("Google AI", "https://blog.google/technology/ai/rss/", 3),
        Feed("TechCrunch AI", "https://techcrunch.com/category/artificial-intelligence/feed/", 2),
        Feed("Hugging Face", "https://huggingface.co/blog/feed.xml", 2),
        Feed("MIT Technology Review", "https://www.technologyreview.com/topic/artificial-intelligence/feed/", 3)
    )

    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val MAX_AGE_DAYS = 7L
    private const val MAX_SUMMARY_CHARS = 900

    suspend fun fetchImportant(): List<NewsItem> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val candidates = feeds.flatMap { feed ->
            runCatching { parseFeed(feed) }.getOrDefault(emptyList())
        }
            .filter { it.publishedAt > 0L && it.publishedAt <= now + DAY_MS && it.publishedAt >= now - MAX_AGE_DAYS * DAY_MS }
            .filter { it.title.length >= 18 && it.url.startsWith("https://") }
            .distinctBy { canonicalUrl(it.url) }
            .sortedWith(compareByDescending<NewsItem> { it.score }.thenByDescending { it.publishedAt })

        // Remove near-identical headlines even when publishers use different URLs.
        val unique = mutableListOf<NewsItem>()
        for (candidate in candidates) {
            if (unique.none { headlineSimilarity(it.title, candidate.title) >= 0.82 }) {
                unique += candidate
            }
        }

        // A stricter threshold favors fewer substantial stories over a noisy full feed.
        val selected = unique.filter { it.score >= 6 }.take(30)
        translateLocally(selected)
    }

    private suspend fun translateLocally(items: List<NewsItem>): List<NewsItem> {
        if (items.isEmpty()) return items
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.RUSSIAN)
                .build()
        )
        return try {
            // Model is downloaded once and then inference runs on-device.
            translator.downloadModelIfNeeded().await()
            items.map { item ->
                val title = runCatching { translateText(translator, item.title) }.getOrDefault(item.title)
                val summary = runCatching { translateText(translator, item.summary) }.getOrDefault(item.summary)
                item.copy(title = title, summary = summary)
            }
        } catch (_: Exception) {
            // Never replace good source text with a broken/partial translation.
            items
        } finally {
            translator.close()
        }
    }

    private suspend fun translateText(
        translator: com.google.mlkit.nl.translate.Translator,
        source: String
    ): String {
        val clean = source.trim()
        if (clean.isBlank()) return clean
        val translated = translator.translate(clean).await().trim()
        if (translated.isBlank()) return clean
        return applyRussianTerminology(translated)
    }

    private fun parseFeed(feed: Feed): List<NewsItem> {
        val connection = (URL(feed.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12000
            readTimeout = 15000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PHOTON-AI-NEWS/2.0 (Android RSS reader)")
            setRequestProperty("Accept", "application/rss+xml, application/atom+xml, application/xml, text/xml")
        }
        try {
            connection.inputStream.use { input ->
                val parser = Xml.newPullParser().apply {
                    setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
                    setInput(input, null)
                }
                val items = mutableListOf<NewsItem>()
                var event = parser.eventType
                var insideItem = false
                var title = ""
                var link = ""
                var description = ""
                var date = ""
                while (event != XmlPullParser.END_DOCUMENT) {
                    when (event) {
                        XmlPullParser.START_TAG -> when (parser.name.substringAfter(':').lowercase(Locale.ROOT)) {
                            "item", "entry" -> {
                                insideItem = true
                                title = ""; link = ""; description = ""; date = ""
                            }
                            "title" -> if (insideItem) title = parser.nextText().trim()
                            "link" -> if (insideItem) {
                                val href = parser.getAttributeValue(null, "href")
                                val rel = parser.getAttributeValue(null, "rel")
                                if (!href.isNullOrBlank() && (rel.isNullOrBlank() || rel == "alternate")) {
                                    link = href.trim()
                                } else if (href.isNullOrBlank()) {
                                    link = parser.nextText().trim()
                                }
                            }
                            "description", "summary", "content", "encoded" -> if (insideItem && description.isBlank()) {
                                description = parser.nextText().trim()
                            }
                            "pubdate", "published", "updated", "date" -> if (insideItem && date.isBlank()) {
                                date = parser.nextText().trim()
                            }
                        }
                        XmlPullParser.END_TAG -> if (
                            parser.name.substringAfter(':').equals("item", true) ||
                            parser.name.substringAfter(':').equals("entry", true)
                        ) {
                            val cleanTitle = cleanText(title)
                            val cleanSummary = cleanText(description).take(MAX_SUMMARY_CHARS)
                            val published = parseDate(date)
                            if (cleanTitle.isNotBlank() && link.startsWith("http") && published > 0L) {
                                val score = significanceScore(cleanTitle, cleanSummary, feed.authority)
                                val id = sha256(canonicalUrl(link))
                                items += NewsItem(
                                    id = id,
                                    title = cleanTitle,
                                    summary = cleanSummary,
                                    url = link,
                                    source = feed.name,
                                    publishedAt = published,
                                    score = score
                                )
                            }
                            insideItem = false
                        }
                    }
                    event = parser.next()
                }
                return items
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun cleanText(raw: String): String {
        if (raw.isBlank()) return ""
        val decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_COMPACT).toString()
        return decoded
            .replace(Regex("\\u00a0"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Conservative rules, not an AI judgment. High-impact event language carries
     * more weight than generic mentions of popular model names.
     */
    private fun significanceScore(title: String, summary: String, authority: Int): Int {
        val headline = normalize(title)
        val body = normalize(summary)
        val combined = "$headline $body"
        val majorEvents = listOf(
            "launches", "launched", "introduces", "introduced", "announces", "announced",
            "releases", "released", "open sources", "open-sources", "acquires", "acquired",
            "acquisition", "raises", "raised", "funding", "investment", "partnership",
            "regulation", "policy", "safety", "security", "benchmark", "breakthrough",
            "new flagship model", "frontier model", "research breakthrough",
            "запустил", "представил", "выпустил", "объявил", "открыл исходный код",
            "приобрел", "поглощение", "финансирование", "инвестиции", "партнерство",
            "регулирование", "закон", "безопасность", "прорыв", "новая флагманская модель"
        )
        val impactTopics = listOf(
            "gpt-5", "gpt-4", "gemini", "claude", "llama", "deepseek", "openai",
            "google", "anthropic", "nvidia", "meta", "microsoft", " робот", "robot",
            "chip", "gpu", "compute", "reasoning", "multimodal", "agent", "model",
            "модель", "ии", "искусственный интеллект", "вычислитель"
        )
        val lowValue = listOf(
            "opinion", "podcast", "sponsored", "weekly roundup", "how to", "tutorial",
            "guide", "newsletter", "job opening", "event recap", "мнение", "подкаст",
            "реклама", "инструкция", "руководство", "дайджест", "вакансия"
        )
        val majorCount = majorEvents.count { combined.contains(it) }
        val topicCount = impactTopics.count { combined.contains(it) }
        var score = authority + majorCount * 3 + topicCount
        if (majorEvents.any { headline.contains(it) }) score += 2
        if (lowValue.any { headline.contains(it) || body.contains(it) }) score -= 6
        if (summary.length < 80) score -= 1
        if (headline.length < 35) score -= 1
        return score
    }

    private fun parseDate(raw: String): Long {
        val value = raw.trim()
        if (value.isBlank()) return 0L // Unknown dates must not be presented as fresh.
        return runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }
            .recoverCatching { Instant.parse(value).toEpochMilli() }
            .recoverCatching { value.toLong().let { if (it < 10_000_000_000L) it * 1000 else it } }
            .getOrDefault(0L)
    }

    private fun canonicalUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw.trim())
            val host = (uri.host ?: "").lowercase(Locale.ROOT).removePrefix("www.")
            val path = (uri.path ?: "").trimEnd('/')
            "https://$host$path"
        }.getOrDefault(raw.substringBefore('?').substringBefore('#').trimEnd('/').lowercase(Locale.ROOT))
    }

    private fun headlineSimilarity(first: String, second: String): Double {
        val a = normalize(first).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        val b = normalize(second).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()

    /**
     * Small deterministic glossary for frequent ML translation inconsistencies.
     * It is intentionally conservative and never rewrites company/model names.
     */
    private fun applyRussianTerminology(value: String): String {
        val replacements = listOf(
            Regex("машинный интеллект", RegexOption.IGNORE_CASE) to "искусственный интеллект",
            Regex("генеративный искусственный интеллект", RegexOption.IGNORE_CASE) to "генеративный ИИ",
            Regex("языковые модели большого размера", RegexOption.IGNORE_CASE) to "большие языковые модели",
            Regex("открытый код", RegexOption.IGNORE_CASE) to "открытый исходный код"
        )
        return replacements.fold(value) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
