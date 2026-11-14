package com.lunacode.photonnews.data

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class SourceTrustAssessment(
    val score: Int,
    val label: String,
    val secureLinkRatio: Double,
    val distinctHostCount: Int,
    val explanation: String
)

data class FactConflict(
    val description: String,
    val sources: List<String>,
    val severity: String = "POTENTIAL"
)

data class EditorialAssessment(
    val semanticSimilarity: Int,
    val sourceTrust: SourceTrustAssessment,
    val factConflicts: List<FactConflict>,
    val impactScore: Int,
    val isGlobalBreaking: Boolean
)

object SemanticIntelligence {
    private val stopWords = setOf(
        "the", "a", "an", "and", "or", "for", "to", "of", "in", "on", "at", "by", "with",
        "from", "as", "is", "are", "be", "was", "were", "has", "have", "had", "its",
        "новый", "новая", "новое", "новые", "для", "как", "что", "это", "при", "после",
        "над", "из", "и", "или", "в", "на", "по", "с", "со", "к", "от", "до", "не"
    )

    /**
     * Uses the installed multilingual neural model when available.
     * The lexical score is a deliberately labelled fallback, not semantic inference.
     */
    fun similarity(first: String, second: String): Double =
        SemanticModelRegistry.similarity(first, second)?.coerceIn(0.0, 1.0)
            ?: lexicalSimilarity(first, second)

    fun clusteringThreshold(): Double = if (SemanticModelRegistry.isReady()) 0.72 else 0.42

    fun lexicalSimilarity(first: String, second: String): Double {
        val a = tokens(first)
        val b = tokens(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        val containment = intersection / minOf(a.size, b.size).coerceAtLeast(1)
        val jaccard = intersection / union
        return (jaccard * 0.65 + containment * 0.35).coerceIn(0.0, 1.0)
    }

    fun similarityScore(articles: List<NewsItem>): Int {
        if (articles.size < 2) return 0
        val pairScores = mutableListOf<Double>()
        val sample = articles.take(12)
        for (i in sample.indices) {
            for (j in i + 1 until sample.size) {
                pairScores += similarity(
                    "${sample[i].title} ${sample[i].summary}",
                    "${sample[j].title} ${sample[j].summary}"
                )
            }
        }
        return (pairScores.average() * 100).toInt().coerceIn(0, 100)
    }

    private fun tokens(value: String): Set<String> = normalize(value)
        .split(Regex("[^\\p{L}\\p{N}]+"))
        .map { stem(it) }
        .filter { it.length > 2 && it !in stopWords }
        .toSet()

    private fun stem(token: String): String {
        val suffixes = listOf(
            "иями", "ями", "ами", "ого", "ему", "ыми", "ими", "ать", "ять", "ить",
            "ов", "ев", "ей", "ам", "ям", "ах", "ях", "ы", "и", "а", "я", "ом", "ем",
            "ing", "edly", "ed", "es", "s"
        )
        return suffixes.firstOrNull { token.length > it.length + 3 && token.endsWith(it) }
            ?.let { token.removeSuffix(it) } ?: token
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

object SourceTrustEngine {
    /**
     * Scores provenance completeness only. It is not a publisher reputation,
     * independence, reliability, or factual-accuracy score.
     */
    fun assess(articles: List<NewsItem>): SourceTrustAssessment {
        if (articles.isEmpty()) {
            return SourceTrustAssessment(0, "Нет данных", 0.0, 0, "Нет публикаций для анализа")
        }
        val secureCount = articles.count { article ->
            runCatching {
                val uri = URI(article.url)
                uri.scheme.equals("https", true) && !uri.host.isNullOrBlank()
            }.getOrDefault(false)
        }
        val secureRatio = secureCount.toDouble() / articles.size
        val hosts = articles.mapNotNull { article ->
            runCatching {
                val uri = URI(article.url)
                if (uri.scheme.equals("https", true)) uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
                else null
            }.getOrNull()
        }.distinct()
        val datedRatio = articles.count { it.publishedAt > 0L }.toDouble() / articles.size
        val publisherRatio = articles.count { it.source.isNotBlank() }.toDouble() / articles.size
        val hostDiversity = (hosts.size / 3.0).coerceAtMost(1.0)
        val rawScore = secureRatio * 35 + hostDiversity * 25 + datedRatio * 20 + publisherRatio * 20
        // A single-publisher cluster cannot receive a high provenance-coverage score.
        val score = rawScore.toInt().coerceIn(0, if (hosts.size < 2) 64 else 100)
        val label = when {
            score >= 80 -> "Высокая полнота происхождения"
            score >= 55 -> "Средняя полнота происхождения"
            else -> "Ограниченные данные о происхождении"
        }
        return SourceTrustAssessment(
            score = score,
            label = label,
            secureLinkRatio = secureRatio,
            distinctHostCount = hosts.size,
            explanation = "Оценены HTTPS, разнообразие доменов, даты и метаданные издателей. Это не рейтинг достоверности."
        )
    }
}

object FactCrossCheck {
    private data class NumericClaim(
        val value: Double,
        val unit: String,
        val anchors: Set<String>,
        val source: String
    )

    private val numberPattern = Regex(
        "(?i)(\\d+(?:[.,]\\d+)?)\\s*(%|percent(?:age)?|процент(?:а|ов)?|million|млн|billion|млрд|thousand|тыс|users|пользовател(?:ь|ей)|hours|час(?:а|ов)?)?"
    )
    private val stop = setOf(
        "the", "and", "for", "with", "that", "this", "from", "что", "это", "как", "для",
        "при", "после", "reached", "reported", "reports", "says", "said", "about", "year"
    )

    fun findPotentialConflicts(articles: List<NewsItem>): List<FactConflict> {
        val claims = articles.flatMap(::extractClaims)
        val conflicts = mutableListOf<FactConflict>()
        for (i in claims.indices) {
            for (j in i + 1 until claims.size) {
                val first = claims[i]
                val second = claims[j]
                val sharedAnchors = first.anchors.intersect(second.anchors)
                val relativeDifference = abs(first.value - second.value) /
                    maxOf(abs(first.value), abs(second.value), 1e-9)
                if (first.source != second.source && first.unit == second.unit &&
                    relativeDifference >= 0.02 && sharedAnchors.size >= 2
                ) {
                    conflicts += FactConflict(
                        description = "В публикациях найдены разные числовые значения (${first.value} и ${second.value} ${first.unit.ifBlank { "ед." }}) по теме: ${sharedAnchors.take(3).joinToString(" ")}",
                        sources = listOf(first.source, second.source).distinct()
                    )
                }
            }
        }
        return conflicts.distinctBy { it.description }.take(5)
    }

    private fun extractClaims(article: NewsItem): List<NumericClaim> {
        val text = "${article.title} ${article.summary}".lowercase(Locale.ROOT)
        return numberPattern.findAll(text).mapNotNull { match ->
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                ?: return@mapNotNull null
            // Calendar years are not useful comparison claims for this heuristic.
            if (value in 1900.0..2100.0) return@mapNotNull null
            val rawUnit = match.groupValues.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
            val unit = normalizeUnit(rawUnit)
            val before = text.substring(0, match.range.first)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in stop }
                .takeLast(6)
            val after = text.substring(match.range.last + 1)
                .split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in stop }
                .take(3)
            val anchors = (before + after).toSet()
            if (anchors.size < 2 || article.source.isBlank()) null
            else NumericClaim(value, unit, anchors, article.source)
        }.toList()
    }

    private fun normalizeUnit(unit: String): String = when (unit) {
        "%", "percent", "percentage", "процент", "процента", "процентов" -> "percent"
        "million", "млн" -> "million"
        "billion", "млрд" -> "billion"
        "thousand", "тыс" -> "thousand"
        "users", "пользователь", "пользователей" -> "users"
        "hours", "час", "часа", "часов" -> "hours"
        else -> unit
    }
}

object ImpactRanking {
    fun score(event: NewsEvent, now: Long = System.currentTimeMillis()): Int {
        if (event.articles.isEmpty()) return 0
        val strongestArticle = event.articles.maxOf { it.score }.coerceIn(0, 10_000)
        val significance = (strongestArticle * 4).coerceIn(0, 40)
        val distinctPublishers = event.articles.map { it.source.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }.distinct().size
        val coverage = (distinctPublishers * 7).coerceAtMost(21)
        val ageMs = now - event.latestAt
        val freshness = when {
            ageMs < -5L * 60 * 1000 -> 0
            ageMs <= 60L * 60 * 1000 -> 20
            ageMs <= 6L * 60 * 60 * 1000 -> 16
            ageMs <= 24L * 60 * 60 * 1000 -> 12
            ageMs <= 72L * 60 * 60 * 1000 -> 6
            else -> 0
        }
        val categoryWeight = when (event.articles.maxByOrNull { it.score }?.category) {
            "SAFETY" -> 12
            "POLICY" -> 10
            "BUSINESS" -> 8
            "RESEARCH" -> 7
            "PRODUCT" -> 6
            else -> 3
        }
        return (significance + coverage + freshness + categoryWeight + if (event.isBreaking) 7 else 0)
            .coerceIn(0, 100)
    }
}

object GlobalBreakingMonitor {
    fun isGlobalBreaking(event: NewsEvent, impactScore: Int, now: Long = System.currentTimeMillis()): Boolean {
        val fresh = event.latestAt in (now - 24L * 60 * 60 * 1000)..(now + 5L * 60 * 1000)
        val distinctPublishers = event.articles.map { it.source.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotBlank() }.distinct().size
        val distinctHttpsHosts = event.articles.mapNotNull { article ->
            runCatching {
                val uri = URI(article.url)
                if (uri.scheme.equals("https", true)) uri.host?.lowercase(Locale.ROOT)?.removePrefix("www.")
                else null
            }.getOrNull()
        }.distinct().size
        val corroborated = distinctPublishers >= 2 && distinctHttpsHosts >= 2
        return event.isBreaking && fresh && impactScore >= 65 && corroborated
    }
}

object EditorialIntelligenceV3 {
    fun assess(event: NewsEvent, now: Long = System.currentTimeMillis()): EditorialAssessment {
        val impact = ImpactRanking.score(event, now)
        return EditorialAssessment(
            semanticSimilarity = SemanticIntelligence.similarityScore(event.articles),
            sourceTrust = SourceTrustEngine.assess(event.articles),
            factConflicts = FactCrossCheck.findPotentialConflicts(event.articles),
            impactScore = impact,
            isGlobalBreaking = GlobalBreakingMonitor.isGlobalBreaking(event, impact, now)
        )
    }
}
