package com.example.phonebot

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat

class AdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "PhoneBot Screen Lock সক্রিয় হয়েছে!", Toast.LENGTH_SHORT).show()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)
            val token = prefs.getString("bot_token", "") ?: ""
            val userId = prefs.getLong("user_id", 0L)

            if (token.isNotEmpty() && userId != 0L) {
                try {
                    val serviceIntent = Intent(context, BotService::class.java).apply {
                        putExtra("from_boot", true)
                    }
                    ContextCompat.startForegroundService(context, serviceIntent)
                } catch (_: Exception) {
                }
            }
        }
    }
}