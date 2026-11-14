package com.lunacode.photonnews.data

data class EditorialSourceReference(
    val publisher: String,
    val url: String
)

data class EditorialDraft(
    val headline: String,
    val lead: String,
    val paragraphs: List<String>,
    val sources: List<EditorialSourceReference>,
    val caveats: List<String>,
    val method: String
)

/**
 * Evidence-grounded editorial draft assembly.
 *
 * This is extractive synthesis, not free-form LLM generation: every sentence is
 * copied from a source title or summary, ranked and deduplicated with semantic
 * embeddings when the optional on-device model is installed.
 */
object EditorialComposer {
    fun compose(event: NewsEvent): EditorialDraft {
        if (event.articles.isEmpty()) {
            return EditorialDraft(
                headline = event.headline,
                lead = "",
                paragraphs = emptyList(),
                sources = emptyList(),
                caveats = listOf("Нет исходных публикаций для редакционного материала."),
                method = "Extractive evidence synthesis"
            )
        }

        val ordered = event.articles.sortedWith(
            compareByDescending<NewsItem> { it.score }
                .thenByDescending { it.publishedAt }
        )
        val representative = ordered.maxByOrNull { candidate ->
            val centrality = if (event.articles.size < 2) 0.0 else
                event.articles.asSequence()
                    .filter { it.id != candidate.id }
                    .take(8)
                    .map {
                        SemanticIntelligence.similarity(
                            "${candidate.title} ${candidate.summary}",
                            "${it.title} ${it.summary}"
                        )
                    }
                    .average()
            candidate.score * 0.7 + centrality * 30.0 +
                ((candidate.publishedAt / 60_000L) % 1000) * 0.0001
        } ?: ordered.first()

        val lead = representative.summary.ifBlank { representative.title }
        val paragraphs = mutableListOf<String>()
        ordered.forEach { article ->
            val text = article.summary.trim().ifBlank { article.title.trim() }
            if (text.isNotBlank() && paragraphs.size < 4) {
                val duplicate = paragraphs.any { existing ->
                    SemanticIntelligence.similarity(existing, text) >= 0.88
                }
                if (!duplicate) paragraphs += text
            }
        }

        val sources = event.articles
            .distinctBy { it.url }
            .map { EditorialSourceReference(it.source, it.url) }

        val caveats = buildList {
            if (sources.size < 2) add("Материал основан на одном издателе; независимое подтверждение отсутствует.")
            if (FactCrossCheck.findPotentialConflicts(event.articles).isNotEmpty()) {
                add("Обнаружены потенциальные расхождения числовых утверждений; проверьте оригинальные публикации.")
            }
            if (event.articles.any { it.publishedAt <= 0L }) {
                add("У части публикаций отсутствует корректная дата.")
            }
            add("Черновик составлен только из заголовков и кратких описаний; проверьте полные тексты перед публикацией.")
        }

        return EditorialDraft(
            headline = representative.title,
            lead = lead,
            paragraphs = paragraphs,
            sources = sources,
            caveats = caveats,
            method = if (SemanticModelRegistry.isReady()) {
                "Multilingual E5 · extractive evidence synthesis"
            } else {
                "Lexical fallback · extractive evidence synthesis"
            }
        )
    }
}
