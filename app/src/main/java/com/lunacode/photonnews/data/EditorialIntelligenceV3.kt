package com.lunacode.photonnews.data

import java.net.URI
import java.text.Normalizer
import java.util.Locale
import kotlin.math.max

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
    val semanticConfidence: Int,
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

    fun similarity(first: String, second: String): Double {
        val a = tokens(first)
        val b = tokens(second)
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val intersection = a.intersect(b).size.toDouble()
        val union = a.union(b).size.toDouble()
        val containment = intersection / minOf(a.size, b.size).coerceAtLeast(1)
        val jaccard = intersection / union
        return (jaccard * 0.65 + containment * 0.35).coerceIn(0.0, 1.0)
    }

    fun confidence(articles: List<NewsItem>): Int {
        if (articles.size < 2) return 0
        val pairScores = mutableListOf<Double>()
        for (i in articles.indices) {
            for (j in i + 1 until articles.size) {
                pairScores += similarity(
                    "${articles[i].title} ${articles[i].summary}",
                    "${articles[j].title} ${articles[j].summary}"
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
     * Measures provenance transparency, not publisher honesty or factual accuracy.
     * No reputation score is inferred from a publisher's brand name.
     */
    fun assess(articles: List<NewsItem>): SourceTrustAssessment {
        if (articles.isEmpty()) return SourceTrustAssessment(0, "Нет данных", 0.0, 0, "Нет публикаций для анализа")
        val secureCount = articles.count { article ->
            runCatching { URI(article.url).scheme.equals("https", true) && !URI(article.url).host.isNullOrBlank() }
                .getOrDefault(false)
        }
        val hosts = articles.mapNotNull { article ->
            runCatching { URI(article.url).host?.lowercase(Locale.ROOT)?.removePrefix("www.") }.getOrNull()
        }.distinct()
        val datedRatio = articles.count { it.publishedAt > 0L }.toDouble() / articles.size
        val secureRatio = secureCount.toDouble() / articles.size
        val score = (
            30 + secureRatio * 25 +
                (hosts.size.toDouble() / articles.size).coerceAtMost(1.0) * 25 +
                datedRatio * 10 +
                (articles.map { it.source.trim().lowercase(Locale.ROOT) }.filter { it.isNotBlank() }.distinct().size.toDouble() /
                    articles.size).coerceAtMost(1.0) * 10
            ).toInt().coerceIn(0, 100)
        val label = when {
            score >= 85 -> "Хорошая прозрачность данных"
            score >= 65 -> "Средняя прозрачность данных"
            else -> "Недостаточно данных об источниках"
        }
        return SourceTrustAssessment(
            score = score,
            label = label,
            secureLinkRatio = secureRatio,
            distinctHostCount = hosts.size,
            explanation = "Оценены HTTPS-ссылки, домены, даты и заполненность издателей. Это не рейтинг достоверности."
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
    private val negations = setOf("not", "no", "never", "denies", "denied", "не", "нет", "отрицает", "отказался")
    private val stop = setOf("the", "and", "for", "with", "that", "this", "from", "что", "это", "как", "для", "при", "после")

    fun findPotentialConflicts(articles: List<NewsItem>): List<FactConflict> {
        val claims = articles.flatMap(::extractClaims)
        val conflicts = mutableListOf<FactConflict>()
        for (i in claims.indices) {
            for (j in i + 1 until claims.size) {
                val first = claims[i]
                val second = claims[j]
                val sharedAnchors = first.anchors.intersect(second.anchors)
                if (first.source != second.source && first.unit == second.unit &&
                    first.value != second.value && sharedAnchors.size >= 2
                ) {
                    conflicts += FactConflict(
                        description = "В публикациях различаются числовые значения (${first.value} и ${second.value} ${first.unit.ifBlank { "ед." }}) по теме: ${sharedAnchors.take(3).joinToString(" ")}",
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
            val value = match.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return@mapNotNull null
            val unit = match.groupValues.getOrNull(2).orEmpty().lowercase(Locale.ROOT)
            val before = text.substring(0, match.range.first).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in stop }
                .takeLast(5)
            val after = text.substring(match.range.last + 1).split(Regex("[^\\p{L}\\p{N}]+"))
                .filter { it.length > 2 && it !in stop }
                .take(2)
            val anchors = (before + after).toSet()
            if (anchors.size < 2) null else NumericClaim(value, unit, anchors, article.source)
        }.toList()
    }
}

object ImpactRanking {
    fun score(event: NewsEvent, now: Long = System.currentTimeMillis()): Int {
        val strongestArticle = event.articles.maxOfOrNull { it.score } ?: 0
        val significance = (strongestArticle * 4).coerceIn(0, 40)
        val coverage = (event.publisherCount * 7).coerceAtMost(21)
        val ageHours = ((now - event.latestAt).coerceAtLeast(0L) / 3_600_000L).toInt()
        val freshness = when {
            ageHours <= 1 -> 20
            ageHours <= 6 -> 16
            ageHours <= 24 -> 12
            ageHours <= 72 -> 6
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
        val highImpact = impactScore >= 65
        val corroboratedOrExceptional = event.publisherCount >= 2 ||
            (event.articles.maxOfOrNull { it.score } ?: 0) >= 14
        return event.isBreaking && fresh && highImpact && corroboratedOrExceptional
    }
}

object EditorialIntelligenceV3 {
    fun assess(event: NewsEvent, now: Long = System.currentTimeMillis()): EditorialAssessment {
        val impact = ImpactRanking.score(event, now)
        return EditorialAssessment(
            semanticConfidence = SemanticIntelligence.confidence(event.articles),
            sourceTrust = SourceTrustEngine.assess(event.articles),
            factConflicts = FactCrossCheck.findPotentialConflicts(event.articles),
            impactScore = impact,
            isGlobalBreaking = GlobalBreakingMonitor.isGlobalBreaking(event, impact, now)
        )
    }
}
