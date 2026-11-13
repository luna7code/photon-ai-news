package com.lunacode.photonnews.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.lunacode.photonnews.MainActivity
import com.lunacode.photonnews.R
import com.lunacode.photonnews.data.NewsRepository
import com.lunacode.photonnews.data.NewsStore

class NewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        val store = NewsStore(applicationContext)
        val known = store.all().map { it.id }.toSet()
        val fresh = NewsRepository.fetchImportant()
        store.upsert(fresh)
        val newImportant = fresh.filter { it.id !in known }
        if (newImportant.isNotEmpty()) notifyNewItems(newImportant.size, newImportant.first().title)
        applicationContext.getSharedPreferences("photon", Context.MODE_PRIVATE).edit()
            .putLong("last_update", System.currentTimeMillis()).apply()
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }

    private fun notifyNewItems(count: Int, headline: String) {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "photon_daily_news"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "PHOTON AI NEWS", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Новые значимые события в сфере искусственного интеллекта"
            })
        }
        val intent = Intent(applicationContext, MainActivity::class.java)
        val pending = PendingIntent.getActivity(applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("PHOTON AI NEWS · новый выпуск")
            .setContentText(if (count == 1) headline else "$count новых значимых событий. $headline")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Найдено $count новых значимых событий. Первое: $headline"))
            .setContentIntent(pending).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(applicationContext).notify(2401, notification) }
    }
}
