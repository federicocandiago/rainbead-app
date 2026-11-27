package net.candiago.bluegleam

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Notifications without Google's push service: every quarter of an hour (the least Android allows) the phone
 * asks the Bluegleam site what is new (GET {scope}api/push, with this phone's own token) and shows it. While the
 * app is in front the page shows them itself, so the phone only keeps count. A token the site no longer knows
 * (the phone was removed in Settings › Devices) is forgotten: the page asks again next time.
 */
class PushWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("bluegleam", Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_TOKEN, null) ?: return Result.success()
        val scope = prefs.getString("scope_url", null) ?: return Result.success()
        val connection = try {
            (URL(scope.trimEnd('/') + "/api/push").openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15000
                readTimeout = 20000
            }
        } catch (_: Exception) {
            return Result.retry()
        }
        try {
            when (connection.responseCode) {
                200 -> {
                    val items = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optJSONArray("notifications")
                    if (!visible && items != null) {
                        for (i in 0 until items.length()) {
                            val n = items.getJSONObject(i)
                            show(n.optInt("id"), n.optString("title"), n.optString("body"))
                        }
                    }
                    return Result.success()
                }
                401 -> {
                    prefs.edit().remove(KEY_TOKEN).apply()
                    WorkManager.getInstance(applicationContext).cancelUniqueWork(PERIODIC)
                    return Result.success()
                }
                else -> return Result.retry()
            }
        } catch (_: Exception) {
            return Result.retry()
        } finally {
            connection.disconnect()
        }
    }

    private fun show(id: Int, title: String, body: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, applicationContext.getString(R.string.notifications_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // notifications not allowed on this phone: nothing to show
        }
    }

    companion object {
        const val KEY_TOKEN = "push_token"
        private const val CHANNEL = "bluegleam"
        private const val PERIODIC = "bluegleam-push"

        /** True while the app is in front: its page shows the notifications. */
        @Volatile
        var visible = false

        /** Every 15 minutes with a network, plus one check right away. */
        fun schedule(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(
                PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequest.Builder(PushWorker::class.java, 15, TimeUnit.MINUTES).setConstraints(network).build(),
            )
            manager.enqueueUniqueWork("bluegleam-push-now", ExistingWorkPolicy.REPLACE, OneTimeWorkRequest.Builder(PushWorker::class.java).setConstraints(network).build())
        }
    }
}
