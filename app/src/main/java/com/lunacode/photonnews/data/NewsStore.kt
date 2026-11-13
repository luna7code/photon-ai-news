package com.lunacode.photonnews.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues

class NewsStore(context: Context) : SQLiteOpenHelper(context, "photon_news.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE news (
            id TEXT PRIMARY KEY, title TEXT NOT NULL, summary TEXT NOT NULL,
            url TEXT NOT NULL, source TEXT NOT NULL, publishedAt INTEGER NOT NULL, score INTEGER NOT NULL
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun upsert(items: List<NewsItem>) {
        writableDatabase.beginTransaction()
        try {
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("id", item.id); put("title", item.title); put("summary", item.summary)
                    put("url", item.url); put("source", item.source); put("publishedAt", item.publishedAt); put("score", item.score)
                }
                writableDatabase.insertWithOnConflict("news", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            writableDatabase.execSQL("DELETE FROM news WHERE publishedAt < ?", arrayOf(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000))
            writableDatabase.setTransactionSuccessful()
        } finally { writableDatabase.endTransaction() }
    }

    fun all(): List<NewsItem> {
        val result = mutableListOf<NewsItem>()
        readableDatabase.rawQuery("SELECT id,title,summary,url,source,publishedAt,score FROM news ORDER BY score DESC,publishedAt DESC", null).use { c ->
            while (c.moveToNext()) result += NewsItem(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5), c.getInt(6))
        }
        return result
    }
}
