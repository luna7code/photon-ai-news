package com.lunacode.photonnews.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class NewsStore(context: Context) : SQLiteOpenHelper(context, "photon_news.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE news (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL,
            url TEXT NOT NULL, source TEXT NOT NULL, publishedAt INTEGER NOT NULL,
            score INTEGER NOT NULL, category TEXT NOT NULL DEFAULT 'OTHER',
            isSaved INTEGER NOT NULL DEFAULT 0
        )""")
        db.execSQL("CREATE INDEX idx_news_published ON news(publishedAt DESC)")
        db.execSQL("CREATE INDEX idx_news_category ON news(category)")
        db.execSQL("CREATE INDEX idx_news_saved ON news(isSaved)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE news ADD COLUMN category TEXT NOT NULL DEFAULT 'OTHER'")
            db.execSQL("ALTER TABLE news ADD COLUMN isSaved INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_published ON news(publishedAt DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_category ON news(category)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_news_saved ON news(isSaved)")
        }
    }

    fun upsert(items: List<NewsItem>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("id", item.id); put("title", item.title); put("summary", item.summary)
                    put("url", item.url); put("source", item.source); put("publishedAt", item.publishedAt)
                    put("score", item.score); put("category", item.category)
                }
                val inserted = db.insertWithOnConflict("news", null, values, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) {
                    val updates = ContentValues(values).apply { remove("id") }
                    db.update("news", updates, "id = ?", arrayOf(item.id))
                }
            }
            db.execSQL(
                "DELETE FROM news WHERE publishedAt < ? AND isSaved = 0",
                arrayOf(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setSaved(id: String, saved: Boolean) {
        writableDatabase.update(
            "news", ContentValues().apply { put("isSaved", if (saved) 1 else 0) },
            "id = ?", arrayOf(id)
        )
    }

    fun all(): List<NewsItem> {
        val result = mutableListOf<NewsItem>()
        readableDatabase.rawQuery(
            "SELECT id,title,summary,url,source,publishedAt,score,category,isSaved FROM news ORDER BY score DESC,publishedAt DESC",
            null
        ).use { c ->
            while (c.moveToNext()) result += NewsItem(
                id = c.getString(0), title = c.getString(1), summary = c.getString(2),
                url = c.getString(3), source = c.getString(4), publishedAt = c.getLong(5),
                score = c.getInt(6), category = c.getString(7), isSaved = c.getInt(8) == 1
            )
        }
        return result
    }
}
