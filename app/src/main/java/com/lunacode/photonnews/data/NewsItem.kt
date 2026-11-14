package com.lunacode.photonnews.data

data class NewsItem(
    val id: String,
    val title: String,
    val summary: String,
    val url: String,
    val source: String,
    val publishedAt: Long,
    val score: Int,
    val category: String = "OTHER",
    val isSaved: Boolean = false
)
