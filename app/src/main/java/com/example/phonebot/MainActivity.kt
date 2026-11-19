package com.example.phonebot

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var tokenInput: EditText
    private lateinit var userIdInput: EditText
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }

        val title = TextView(this).apply {
            text = "PhoneBot Pro Control Panel"
            textSize = 22f
            setPadding(0, 0, 0, 28)
        }

        val tokenLabel = TextView(this).apply { text = "Telegram Bot Token:" }
        tokenInput = EditText(this).apply {
            hint = "123456:ABC-DEF..."
            setText(prefs.getString("bot_token", ""))
        }

        val userLabel = TextView(this).apply {
            text = "Allowed Telegram User ID:"
            setPadding(0, 20, 0, 0)
        }
        userIdInput = EditText(this).apply {
            hint = "e.g. 123456789"
            inputType = InputType.TYPE_CLASS_NUMBER
            val savedId = prefs.getLong("user_id", 0L)
            if (savedId != 0L) setText(savedId.toString())
        }

        val permBtn = Button(this).apply {
            text = "1. Grant Core Permissions (Camera, Mic, GPS, Notif)"
            setOnClickListener { requestNeededPermissions() }
        }

        val batteryBtn = Button(this).apply {
            text = "2. Disable Battery Optimization"
            setOnClickListener {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "ইতোমধ্যে চালু আছে!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val adminBtn = Button(this).apply {
            text = "3. Enable Screen Lock (Device Admin)"
            setOnClickListener {
                val compName = ComponentName(this@MainActivity, AdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, compName)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "টেলিগ্রাম থেকে রিমোটলি স্ক্রিন লক করার জন্য এটি প্রয়োজন।")
                }
                startActivity(intent)
            }
        }

        val overlayBtn = Button(this).apply {
            text = "4. Allow Overlay (For Lost Mode Popup)"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                } else {
                    Toast.makeText(this@MainActivity, "ইতোমধ্যে অনুমতি দেওয়া আছে!", Toast.LENGTH_SHORT).show()
                }
            }
        }

        val notifListenBtn = Button(this).apply {
            text = "5. Enable Notification Forwarder Access"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
        }

        val startBtn = Button(this).apply {
            text = "▶ Save & Start Bot Service"
            setOnClickListener { saveAndStartBot() }
        }

        val stopBtn = Button(this).apply {
            text = "⏹ Stop Bot Service"
            setOnClickListener {
                stopService(Intent(this@MainActivity, BotService::class.java))
                statusText.text = "Status: Service Stopped"
            }
        }

        statusText = TextView(this).apply {
            text = "Status: Ready (1 থেকে 5 পর্যন্ত পারমিশন দিয়ে Start চাপুন)"
            setPadding(0, 28, 0, 0)
        }

        layout.addView(title)
        layout.addView(tokenLabel)
        layout.addView(tokenInput)
        layout.addView(userLabel)
        layout.addView(userIdInput)
        layout.addView(permBtn)
        layout.addView(batteryBtn)
        layout.addView(adminBtn)
        layout.addView(overlayBtn)
        layout.addView(notifListenBtn)
        layout.addView(startBtn)
        layout.addView(stopBtn)
        layout.addView(statusText)

        setContentView(ScrollView(this).apply { addView(layout) })
    }

    private fun requestNeededPermissions() {
        val perms = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(this, perms.toTypedArray(), 101)
    }

    private fun hasCorePermissions(): Boolean {
        val required = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return required.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun saveAndStartBot() {
        val token = tokenInput.text.toString().trim()
        val userId = userIdInput.text.toString().trim().toLongOrNull()

        if (token.isEmpty() || userId == null) {
            Toast.makeText(this, "সঠিক Bot Token এবং User ID দিন!", Toast.LENGTH_SHORT).show()
            return
        }

        if (!hasCorePermissions()) {
            Toast.makeText(this, "প্রথমে 1 নম্বর বাটন থেকে Camera, Mic ও GPS পারমিশন দিন!", Toast.LENGTH_LONG).show()
            requestNeededPermissions()
            return
        }

        val prefs = getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("bot_token", token)
            .putLong("user_id", userId)
            .apply()

        val serviceIntent = Intent(this, BotService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        statusText.text = "Status: Bot Running in Background!"
        Toast.makeText(this, "PhoneBot Pro চালু হয়েছে!", Toast.LENGTH_SHORT).show()
    }
}