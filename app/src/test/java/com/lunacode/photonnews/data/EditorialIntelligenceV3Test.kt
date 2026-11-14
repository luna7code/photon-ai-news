package com.lunacode.photonnews.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorialIntelligenceV3Test {
    @Test
    fun semanticMatcherHandlesMorphologicalVariations() {
        val similarity = SemanticIntelligence.similarity(
            "OpenAI launches a powerful model for developers",
            "OpenAI launched powerful models for developer tools"
        )
        assertTrue("Expected high lexical-semantic overlap, got $similarity", similarity >= 0.42)
    }

    @Test
    fun sourceTrustMeasuresProvenanceNotTruth() {
        val result = SourceTrustEngine.assess(
            listOf(article("OpenAI announces a new model", "OpenAI", "https://openai.com/news", 10))
        )
        assertTrue(result.score in 0..100)
        assertTrue(result.secureLinkRatio == 1.0)
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
    fun impactScoreIsBoundedAndRewardsFreshHighImpactStories() {
        val now = System.currentTimeMillis()
        val recent = event(now, score = 15, publishers = 3, breaking = true)
        val old = event(now - 5L * 24 * 60 * 60 * 1000, score = 15, publishers = 3, breaking = false)
        val recentScore = ImpactRanking.score(recent, now)
        val oldScore = ImpactRanking.score(old, now)
        assertTrue(recentScore in 0..100)
        assertTrue(recentScore > oldScore)
    }

    @Test
    fun globalMonitorRequiresFreshHighImpactBreakingEvent() {
        val now = System.currentTimeMillis()
        val recent = event(now, score = 18, publishers = 2, breaking = true)
        val stale = event(now - 3L * 24 * 60 * 60 * 1000, score = 18, publishers = 2, breaking = true)
        assertTrue(GlobalBreakingMonitor.isGlobalBreaking(recent, 90, now))
        assertFalse(GlobalBreakingMonitor.isGlobalBreaking(stale, 90, now))
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
