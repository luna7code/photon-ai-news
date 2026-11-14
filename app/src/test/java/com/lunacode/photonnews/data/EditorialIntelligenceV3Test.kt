package com.lunacode.photonnews.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialIntelligenceV3Test {
    @Test
    fun lexicalFallbackHandlesMorphologicalVariationsWithoutClaimingNeuralSemantics() {
        val similarity = SemanticIntelligence.lexicalSimilarity(
            "OpenAI launches a powerful model for developers",
            "OpenAI launched powerful models for developer tools"
        )
        assertTrue("Expected lexical overlap, got $similarity", similarity >= 0.42)
    }

    @Test
    fun neuralEmbeddingCosineIsNormalizedAndBounded() {
        assertEquals(1.0, EmbeddingMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(2f, 0f)), 0.0001)
        assertEquals(0.0, EmbeddingMath.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 0.0001)
        assertEquals(0.0, EmbeddingMath.cosineSimilarity(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0.0001)
    }

    @Test
    fun sourceTransparencyScoreDoesNotRewardSinglePublisherAsVerified() {
        val result = SourceTrustEngine.assess(
            listOf(article("OpenAI announces a new model", "OpenAI", "https://openai.com/news", 10))
        )
        assertTrue(result.score in 0..64)
        assertEquals(1.0, result.secureLinkRatio, 0.0001)
        assertTrue(result.explanation.contains("не рейтинг достоверности"))
    }

    @Test
    fun crossCheckFlagsConflictingComparableNumbers() {
        val conflicts = FactCrossCheck.findPotentialConflicts(
            listOf(
                article("OpenAI revenue reached 10 billion dollars in 2025", "Publisher A", "https://a.example/story", 10),
                article("OpenAI revenue reached 8 billion dollars in 2025", "Publisher B", "https://b.example/story", 10)
            )
        )
        assertTrue(conflicts.isNotEmpty())
        assertEquals(2, conflicts.first().sources.size)
    }

    @Test
    fun crossCheckDoesNotTreatDifferentCalendarYearsAsConflictingClaims() {
        val conflicts = FactCrossCheck.findPotentialConflicts(
            listOf(
                article("OpenAI launched a model in 2024", "Publisher A", "https://a.example/story", 10),
                article("OpenAI launched a model in 2025", "Publisher B", "https://b.example/story", 10)
            )
        )
        assertTrue(conflicts.isEmpty())
    }

    @Test
    fun impactScoreIsBoundedAndRewardsFreshHighImpactStories() {
        val now = System.currentTimeMillis()
        val recent = event(now, score = 15, publishers = 3, breaking = true)
        val old = event(now - 5L * 24 * 60 * 60 * 1000, score = 15, publishers = 3, breaking = false)
        val future = event(now + 60L * 60 * 1000, score = 15, publishers = 3, breaking = true)
        val recentScore = ImpactRanking.score(recent, now)
        val oldScore = ImpactRanking.score(old, now)
        assertTrue(recentScore in 0..100)
        assertTrue(recentScore > oldScore)
        assertTrue(ImpactRanking.score(future, now) < recentScore)
    }

    @Test
    fun globalMonitorRequiresTwoPublishersAndTwoHttpsHosts() {
        val now = System.currentTimeMillis()
        val recent = event(now, score = 18, publishers = 2, breaking = true)
        val sameHost = recent.copy(
            articles = recent.articles.map { it.copy(url = "https://same.example/story") }
        )
        val stale = event(now - 3L * 24 * 60 * 60 * 1000, score = 18, publishers = 2, breaking = true)
        assertTrue(GlobalBreakingMonitor.isGlobalBreaking(recent, 90, now))
        assertFalse(GlobalBreakingMonitor.isGlobalBreaking(sameHost, 90, now))
        assertFalse(GlobalBreakingMonitor.isGlobalBreaking(stale, 90, now))
    }

    @Test
    fun routineLaunchDoesNotBecomeBreakingWithoutCriticalIncidentSignal() {
        val now = System.currentTimeMillis()
        val routine = article(
            "OpenAI launches a new model for developers",
            "Publisher A",
            "https://a.example/story",
            10
        ).copy(publishedAt = now)
        val critical = article(
            "Critical security breach exposes customer data",
            "Publisher A",
            "https://a.example/story",
            10
        ).copy(publishedAt = now)
        assertFalse(NewsIntelligence.buildEvents(listOf(routine)).first().isBreaking)
        assertTrue(NewsIntelligence.buildEvents(listOf(critical)).first().isBreaking)
    }

    private fun article(title: String, source: String, url: String, score: Int) = NewsItem(
        id = "$source-$score",
        title = title,
        summary = "OpenAI revenue reached a significant milestone for artificial intelligence.",
        url = url,
        source = source,
        publishedAt = System.currentTimeMillis(),
        score = score,
        category = "BUSINESS"
    )

    private fun event(now: Long, score: Int, publishers: Int, breaking: Boolean): NewsEvent {
        val articles = (0 until publishers).map { index ->
            article(
                "OpenAI launches major AI model for developers",
                "Publisher $index",
                "https://publisher$index.example/news",
                score
            ).copy(publishedAt = now, id = "article-$index")
        }
        return NewsEvent(
            id = "event-$now",
            headline = articles.first().title,
            summary = articles.first().summary,
            articles = articles,
            publisherCount = publishers,
            latestAt = now,
            isBreaking = breaking,
            verificationLabel = "test"
        )
    }
}
