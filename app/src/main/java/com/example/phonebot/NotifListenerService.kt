package com.example.phonebot

import android.app.Notification
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.Executors

class NotifListenerService : NotificationListenerService() {

    private val client = OkHttpClient()
    private val executor = Executors.newSingleThreadExecutor()

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val prefs = getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("notif_forward", false)) return

        val pkg = sbn.packageName ?: return
        // নিজের অ্যাপ এবং টেলিগ্রামের নোটিফিকেশন বাদ দেওয়া (লুপ এড়াতে)
        if (pkg == packageName || pkg.contains("telegram")) return

        val notif = sbn.notification ?: return
        if ((notif.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notif.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return

        val token = prefs.getString("bot_token", "") ?: ""
        val userId = prefs.getLong("user_id", 0L)
        if (token.isEmpty() || userId == 0L) return

        val appName = try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(ai).toString()
        } catch (_: Exception) {
            pkg
        }

        val msg = "🔔 নোটিফিকেশন [$appName]:\n$title\n$text"
        executor.execute {
            try {
                val json = JSONObject().apply {
                    put("chat_id", userId)
                    put("text", msg)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$token/sendMessage")
                    .post(body)
                    .build()
                client.newCall(req).execute().close()
            } catch (_: Exception) {
            }
        }
    }
}