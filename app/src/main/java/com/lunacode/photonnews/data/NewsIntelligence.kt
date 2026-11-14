package com.lunacode.photonnews.data

import java.text.Normalizer
import java.util.Locale

/**
 * Deterministic editorial event clustering.
 *
 * This is not an LLM, a fact-checker, or proof that publishers are independent.
 * It groups similar headlines and reports how many distinct publisher names
 * cover the same event so readers can inspect the evidence themselves.
 */
data class NewsEvent(
    val id: String,
    val headline: String,
    val summary: String,
    val articles: List<NewsItem>,
    val publisherCount: Int,
    val latestAt: Long,
    val isBreaking: Boolean,
    val verificationLabel: String
) {
    val publishers: List<String>
        get() = articles.map { it.source }.distinct().sorted()
}

object NewsIntelligence {
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "for", "to", "of", "in", "on", "at", "by",
        "with", "from", "as", "is", "are", "be", "its", "it", "new", "новый", "новая",
        "новое", "новые", "для", "как", "что", "это", "при", "после", "над", "из"
    )

    private val breakingSignals = listOf(
        "security breach", "data breach", "critical vulnerability", "zero-day",
        "service outage", "emergency shutdown", "mass recall", "regulatory ban",
        "major data leak", "компрометация данных", "утечка данных",
        "критическая уязвимость", "уязвимость нулевого дня", "масштабный сбой",
        "аварийное отключение", "экстренный запрет", "массовый отзыв"
    )

    fun buildEvents(items: List<NewsItem>): List<NewsEvent> {
        if (items.isEmpty()) return emptyList()
        val clusters = mutableListOf<MutableList<NewsItem>>()
        items.sortedByDescending { it.publishedAt }.forEach { item ->
            val matching = clusters.firstOrNull { cluster ->
                cluster.any { SemanticIntelligence.similarity(it.title, item.title) >= SemanticIntelligence.clusteringThreshold() }
            }
            if (matching == null) clusters += mutableListOf(item) else matching += item
        }

        return clusters.map { cluster ->
            val ordered = cluster.sortedWith(
                compareByDescending<NewsItem> { it.score }.thenByDescending { it.publishedAt }
            )
            val sources = cluster.map { it.source }.distinct()
            val latest = cluster.maxOf { it.publishedAt }
            val headline = ordered.first()
            val strongSignal = cluster.any { article ->
                val normalized = normalize(article.title)
                breakingSignals.any { normalized.contains(it) }
            }
            val now = System.currentTimeMillis()
            val breaking = strongSignal && cluster.any { it.score >= 9 } &&
                latest in (now - 36L * 60 * 60 * 1000)..(now + 5L * 60 * 1000)
            val verification = when {
                sources.size >= 3 -> "3+ издателя сообщают об этом событии"
                sources.size == 2 -> "Есть публикации двух издателей"
                else -> "Только один издатель — подтверждение отсутствует"
            }
            NewsEvent(
                id = cluster.minOf { it.id },
                headline = headline.title,
                summary = ordered.firstOrNull { it.summary.isNotBlank() }?.summary.orEmpty(),
                articles = cluster.sortedByDescending { it.publishedAt },
                publisherCount = sources.size,
                latestAt = latest,
                isBreaking = breaking,
                verificationLabel = verification
            )
        }.sortedWith(
            compareByDescending<NewsEvent> { it.isBreaking }
                .thenByDescending { it.publisherCount }
                .thenByDescending { it.latestAt }
        )
    }

    private fun headlineSimilarity(first: String, second: String): Double {
        val a = tokens(first)
        val b = tokens(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        return a.intersect(b).size.toDouble() / a.union(b).size.toDouble()
    }

    private fun tokens(value: String): Set<String> = normalize(value)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .filter { it.length > 2 && it !in stopWords }
        .toSet()

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
