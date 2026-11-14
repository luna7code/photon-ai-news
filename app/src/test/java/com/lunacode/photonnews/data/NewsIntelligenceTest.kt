package com.lunacode.photonnews.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsIntelligenceTest {
    @Test
    fun groupsSimilarHeadlinesAcrossDifferentPublishers() {
        val now = System.currentTimeMillis()
        val events = NewsIntelligence.buildEvents(
            listOf(
                article("OpenAI launches GPT-5 model for developers", "OpenAI", now),
                article("OpenAI launched GPT-5 model for developers", "TechCrunch", now - 60_000L)
            )
        )

        assertEquals(1, events.size)
        assertEquals(2, events.single().publisherCount)
        assertTrue(events.single().verificationLabel.contains("двух издателей"))
    }

    @Test
    fun unrelatedHeadlinesRemainSeparateEvents() {
        val now = System.currentTimeMillis()
        val events = NewsIntelligence.buildEvents(
            listOf(
                article("OpenAI launches GPT-5 model for developers", "OpenAI", now),
                article("European regulators publish new AI safety framework", "MIT Technology Review", now)
            )
        )

        assertEquals(2, events.size)
    }

    @Test
    fun breakingEventRequiresRecentHighImpactSignal() {
        val now = System.currentTimeMillis()
        val events = NewsIntelligence.buildEvents(
            listOf(article("Critical security breach exposes customer data", "OpenAI", now, score = 12))
        )

        assertTrue(events.single().isBreaking)
    }

    @Test
    fun staleHighImpactStoryIsNotBreaking() {
        val old = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1000
        val events = NewsIntelligence.buildEvents(
            listOf(article("Critical security breach exposes customer data", "OpenAI", old, score = 12))
        )

        assertFalse(events.single().isBreaking)
    }

    private fun article(title: String, source: String, publishedAt: Long, score: Int = 10) =
        NewsItem(
            id = "$source-$publishedAt",
            title = title,
            summary = "A significant development in artificial intelligence.",
            url = "https://example.com/${source.lowercase().replace(" ", "-")}",
            source = source,
            publishedAt = publishedAt,
            score = score,
            category = "PRODUCT"
        )
}
