package com.example.phonebot

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import kotlin.math.abs

class BotService : LifecycleService() {

    @Volatile
    private var isRunning = false
    private var botToken = ""
    private var allowedUserId = 0L
    private var lastUpdateId = 0L

    private var mediaPlayer: MediaPlayer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var motionEnabled = false
    private var prevFrameSamples: ByteArray? = null
    private var lastMotionSentTime = 0L
    private var motionStartTime = 0L

    private var batteryAlertEnabled = false
    private var alertedHigh = false
    private var alertedLow = false
    private var batteryReceiver: BroadcastReceiver? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBot::WakeLock").apply {
            acquire()
        }

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ttsReady = true
            }
        }

        registerBatteryMonitor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        val prefs = getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)
        botToken = prefs.getString("bot_token", "") ?: ""
        allowedUserId = prefs.getLong("user_id", 0L)
        batteryAlertEnabled = prefs.getBoolean("battery_alert", false)

        val fromBoot = intent?.getBooleanExtra("from_boot", false) ?: false

        val openAppIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "phonebot_channel")
            .setContentTitle("PhoneBot Pro Active")
            .setContentText("Listening for Telegram commands...")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && fromBoot) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }

        if (!isRunning && botToken.isNotEmpty() && allowedUserId != 0L) {
            isRunning = true
            startPolling()
            if (fromBoot) {
                Thread { sendMessage(allowedUserId, "🔄 ফোন রিস্টার্ট হয়ে আবার অনলাইন হয়েছে!") }.start()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "phonebot_channel",
                "PhoneBot Service",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun registerBatteryMonitor() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (!batteryAlertEnabled || allowedUserId == 0L || botToken.isEmpty()) return
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                if (level < 0 || scale <= 0) return
                val pct = (level * 100) / scale
                val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
                val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL

                if (pct >= 98 && isCharging && !alertedHigh) {
                    alertedHigh = true
                    Thread { sendMessage(allowedUserId, "🔋 ব্যাটারি প্রায় ফুল ($pct%)! চার্জার খুলে ফেলুন।") }.start()
                } else if (pct < 95) {
                    alertedHigh = false
                }

                if (pct <= 15 && !isCharging && !alertedLow) {
                    alertedLow = true
                    Thread { sendMessage(allowedUserId, "🪫 সতর্কতা: ব্যাটারি লো ($pct%)! চার্জ দিন।") }.start()
                } else if (pct > 20) {
                    alertedLow = false
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun startPolling() {
        Thread {
            while (isRunning) {
                try {
                    val url = "https://api.telegram.org/bot$botToken/getUpdates?offset=$lastUpdateId&timeout=25"
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        if (response.isSuccessful && body != null) {
                            val json = JSONObject(body)
                            if (json.optBoolean("ok")) {
                                val results = json.getJSONArray("result")
                                for (i in 0 until results.length()) {
                                    val update = results.getJSONObject(i)
                                    lastUpdateId = update.getLong("update_id") + 1
                                    handleUpdate(update)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    try {
                        Thread.sleep(3000)
                    } catch (_: InterruptedException) {
                    }
                }
            }
        }.start()
    }

    private fun handleUpdate(update: JSONObject) {
        val message = update.optJSONObject("message") ?: return
        val from = message.optJSONObject("from") ?: return
        val userId = from.optLong("id")
        val chatId = message.getJSONObject("chat").getLong("id")
        val text = message.optString("text", "").trim()

        if (userId != allowedUserId) {
            sendMessage(chatId, "⛔ দুঃখিত! আপনার এই বট ব্যবহারের অনুমতি নেই।")
            return
        }

        val prefs = getSharedPreferences("PhoneBotPrefs", Context.MODE_PRIVATE)

        when {
            text == "/start" || text == "/help" -> sendWelcomeMenu(chatId)
            text == "/battery" || text == "🔋 Battery" -> sendBatteryStatus(chatId)
            text == "/location" || text == "📍 Location" -> sendLocation(chatId)
            text == "/torch_on" || text == "🔦 Torch ON" -> toggleTorch(chatId, true)
            text == "/torch_off" || text == "🌑 Torch OFF" -> toggleTorch(chatId, false)
            text == "/alarm" || text == "🚨 Alarm" -> playAlarm(chatId)
            text == "/stop_alarm" || text == "🔕 Stop Alarm" -> stopAlarm(chatId)
            text == "/vibrate" || text == "📳 Vibrate" -> vibratePhone(chatId)
            text == "/photo" || text == "📷 Back Photo" -> takePhoto(chatId, false)
            text == "/front_photo" || text == "🤳 Front Photo" -> takePhoto(chatId, true)
            text == "/mic" || text == "🎙️ Mic (10s)" -> recordAudio(chatId)
            text == "/light" || text == "💡 Light Sensor" -> readLightSensor(chatId)
            text == "/network" || text == "🌐 Network" -> sendNetworkInfo(chatId)
            text == "/system" || text == "💾 System" -> sendSystemInfo(chatId)
            text == "/lock" || text == "🔒 Lock Screen" -> lockScreen(chatId)
            text == "/motion_on" || text == "🏃 Motion ON" -> startMotionDetection(chatId)
            text == "/motion_off" || text == "🛑 Motion OFF" -> stopMotionDetection(chatId)
            text == "/vibrate_mode" -> setRingerMode(chatId, AudioManager.RINGER_MODE_VIBRATE, "📳 ফোন Vibrate মোডে নেওয়া হয়েছে।")
            text == "/normal_mode" -> setRingerMode(chatId, AudioManager.RINGER_MODE_NORMAL, "🔊 ফোন Normal মোডে নেওয়া হয়েছে।")
            text == "/silent_mode" -> setRingerMode(chatId, AudioManager.RINGER_MODE_SILENT, "🔇 ফোন Silent মোডে নেওয়া হয়েছে।")
            text == "/battery_alert_on" -> {
                batteryAlertEnabled = true
                prefs.edit().putBoolean("battery_alert", true).apply()
                sendMessage(chatId, "🔔 ব্যাটারি ফুল/লো অটো-অ্যালার্ট চালু হয়েছে।")
            }
            text == "/battery_alert_off" -> {
                batteryAlertEnabled = false
                prefs.edit().putBoolean("battery_alert", false).apply()
                sendMessage(chatId, "🔕 ব্যাটারি অটো-অ্যালার্ট বন্ধ হয়েছে।")
            }
            text == "/notif_on" -> {
                prefs.edit().putBoolean("notif_forward", true).apply()
                sendMessage(chatId, "🔔 নোটিফিকেশন ফরওয়ার্ড চালু হয়েছে! (অ্যাপ থেকে ৫ নম্বর পারমিশন অন রাখতে হবে)")
            }
            text == "/notif_off" -> {
                prefs.edit().putBoolean("notif_forward", false).apply()
                sendMessage(chatId, "🔕 নোটিফিকেশন ফরওয়ার্ড বন্ধ হয়েছে।")
            }
            text.startsWith("/say ") -> speakText(chatId, text.removePrefix("/say ").trim())
            text.startsWith("/lost") -> triggerLostMode(chatId, text.removePrefix("/lost").trim())
            text.startsWith("/vol ") -> setVolumePercent(chatId, text.removePrefix("/vol ").trim())
            text.startsWith("/iot ") -> callIoTUrl(chatId, text.removePrefix("/iot ").trim())
        }
    }

    private fun sendWelcomeMenu(chatId: Long) {
        val keyboard = JSONObject().apply {
            val rows = JSONArray().apply {
                put(JSONArray().put("📍 Location").put("🔋 Battery"))
                put(JSONArray().put("🔦 Torch ON").put("🌑 Torch OFF"))
                put(JSONArray().put("🚨 Alarm").put("🔕 Stop Alarm"))
                put(JSONArray().put("📷 Back Photo").put("🤳 Front Photo"))
                put(JSONArray().put("🎙️ Mic (10s)").put("📳 Vibrate"))
                put(JSONArray().put("🏃 Motion ON").put("🛑 Motion OFF"))
                put(JSONArray().put("💡 Light Sensor").put("🌐 Network"))
                put(JSONArray().put("💾 System").put("🔒 Lock Screen"))
            }
            put("keyboard", rows)
            put("resize_keyboard", true)
        }
        val msg = "🤖 PhoneBot Pro চালু আছে!\n\n" +
                "বাটন ছাড়াও নিচের কমান্ডগুলো ব্যবহার করতে পারেন:\n" +
                "• `/say হ্যালো` - ফোন কথা বলবে\n" +
                "• `/lost মেসেজ` - লস্ট মোড স্ক্রিন দেখাবে\n" +
                "• `/vol 80` - ভলিউম (0-100%) সেট করবে\n" +
                "• `/iot http://192.168.1.x/on` - লোকাল IoT কন্ট্রোল\n" +
                "• `/battery_alert_on` বা `_off`\n" +
                "• `/notif_on` বা `_off` (নোটিফিকেশন ফরওয়ার্ড)\n" +
                "• `/normal_mode` | `/vibrate_mode` | `/silent_mode`"
        sendMessage(chatId, msg, keyboard)
    }

    private fun sendBatteryStatus(chatId: Long) {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val pct = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f

        sendMessage(
            chatId,
            "🔋 ব্যাটারি স্ট্যাটাস:\n• চার্জ: $pct%\n• চার্জিং: ${if (isCharging) "হ্যাঁ" else "না"}\n• তাপমাত্রা: $temp°C\n• ভোল্টেজ: ${voltage}V"
        )
    }

    private fun sendLocation(chatId: Long) {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            sendMessage(chatId, "❌ অ্যাপে Location পারমিশন দেওয়া নেই!")
            return
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val provider = when {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> {
                sendMessage(chatId, "❌ ফোনের GPS/Location বন্ধ আছে!")
                return
            }
        }

        sendMessage(chatId, "🛰️ লোকেশন খোঁজা হচ্ছে, অপেক্ষা করুন...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(this), Consumer { loc ->
                if (loc != null) {
                    sendLocationToTelegram(chatId, loc)
                } else {
                    @Suppress("DEPRECATION")
                    val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                        ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                    if (last != null) sendLocationToTelegram(chatId, last)
                    else sendMessage(chatId, "❌ লোকেশন পাওয়া যায়নি (ঘরের বাইরে বা GPS সিগন্যাল চেক করুন)।")
                }
            })
        } else {
            @Suppress("DEPRECATION")
            val last = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (last != null) sendLocationToTelegram(chatId, last)
            else sendMessage(chatId, "❌ লোকেশন পাওয়া যায়নি।")
        }
    }

    private fun sendLocationToTelegram(chatId: Long, loc: Location) {
        Thread {
            try {
                val json = JSONObject().apply {
                    put("chat_id", chatId)
                    put("latitude", loc.latitude)
                    put("longitude", loc.longitude)
                }
                val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val req = Request.Builder()
                    .url("https://api.telegram.org/bot$botToken/sendLocation")
                    .post(body)
                    .build()
                client.newCall(req).execute().close()

                val mapsUrl = "https://maps.google.com/?q=${loc.latitude},${loc.longitude}"
                sendMessage(chatId, "📍 Accuracy: ${loc.accuracy}m\n🗺️ Maps: $mapsUrl")
            } catch (e: Exception) {
                sendMessage(chatId, "❌ লোকেশন পাঠাতে সমস্যা: ${e.message}")
            }
        }.start()
    }

    private fun toggleTorch(chatId: Long, turnOn: Boolean) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            var targetId: String? = null
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                if (chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                ) {
                    targetId = id
                    break
                }
            }
            if (targetId != null) {
                cm.setTorchMode(targetId, turnOn)
                sendMessage(chatId, if (turnOn) "🔦 ফ্ল্যাশলাইট চালু হয়েছে।" else "🌑 ফ্ল্যাশলাইট বন্ধ হয়েছে।")
            } else {
                sendMessage(chatId, "❌ ফ্ল্যাশলাইট পাওয়া যায়নি।")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "❌ টর্চ এরর: ${e.message}")
        }
    }

    private fun playAlarm(chatId: Long) {
        try {
            stopAlarmInternal()
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(this@BotService, uri)
                isLooping = true
                prepare()
                start()
            }
            sendMessage(chatId, "🚨 ফুল ভলিউমে অ্যালার্ম বাজানো হচ্ছে!")
        } catch (e: Exception) {
            sendMessage(chatId, "❌ অ্যালার্ম এরর: ${e.message}")
        }
    }

    private fun stopAlarm(chatId: Long) {
        stopAlarmInternal()
        sendMessage(chatId, "🔕 অ্যালার্ম বন্ধ করা হয়েছে।")
    }

    private fun stopAlarmInternal() {
        try {
            mediaPlayer?.stop()
            mediaPlayer?.release()
        } catch (_: Exception) {
        }
        mediaPlayer = null
    }

    private fun vibratePhone(chatId: Long) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                vm.defaultVibrator.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                v.vibrate(VibrationEffect.createOneShot(2000, VibrationEffect.DEFAULT_AMPLITUDE))
            }
            sendMessage(chatId, "📳 ফোন ২ সেকেন্ডের জন্য ভাইব্রেট করা হয়েছে।")
        } catch (e: Exception) {
            sendMessage(chatId, "❌ ভাইব্রেশন এরর: ${e.message}")
        }
    }

    private fun speakText(chatId: Long, text: String) {
        if (!ttsReady || text.isEmpty()) {
            sendMessage(chatId, "❌ TTS প্রস্তুত নয় বা টেক্সট খালি।")
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
        sendMessage(chatId, "🗣️ স্পিকারে বলা হচ্ছে: \"$text\"")
    }

    private fun triggerLostMode(chatId: Long, customMsg: String) {
        val msg = if (customMsg.isNotEmpty()) customMsg else "এই ফোনটি হারিয়ে গেছে! দয়া করে মালিকের সাথে যোগাযোগ করুন।"
        val intent = Intent(this, LostActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("lost_msg", msg)
        }
        if (Settings.canDrawOverlays(this)) {
            startActivity(intent)
            sendMessage(chatId, "📱 স্ক্রিনে Lost Mode মেসেজ দেখানো হয়েছে।")
        } else {
            val pi = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, "phonebot_channel")
                .setContentTitle("⚠️ LOST PHONE")
                .setContentText(msg)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setFullScreenIntent(pi, true)
                .build()
            getSystemService(NotificationManager::class.java).notify(2, notif)
            sendMessage(chatId, "📱 Full-screen নোটিফিকেশন পাঠানো হয়েছে। (অ্যাপ থেকে Overlay পারমিশন দিলে সরাসরি পপআপ হবে)")
        }
    }

    private fun lockScreen(chatId: Long) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val compName = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(compName)) {
            dpm.lockNow()
            sendMessage(chatId, "🔒 ফোনের স্ক্রিন লক করা হয়েছে।")
        } else {
            sendMessage(chatId, "⚠️ স্ক্রিন লক করতে অ্যাপের ৩ নম্বর বাটন (Device Admin) অন করুন।")
        }
    }

    private fun recordAudio(chatId: Long) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            sendMessage(chatId, "❌ Microphone পারমিশন দেওয়া নেই!")
            return
        }
        sendMessage(chatId, "🎙️ ১০ সেকেন্ডের অডিও রেকর্ড করা হচ্ছে...")

        Thread {
            val audioFile = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")
            var recorder: MediaRecorder? = null
            try {
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(this)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }
                recorder.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(96000)
                    setAudioSamplingRate(44100)
                    setOutputFile(audioFile.absolutePath)
                    prepare()
                    start()
                }
                Thread.sleep(10_000)
                recorder.stop()
                recorder.release()
                recorder = null

                sendFile(chatId, audioFile, "sendAudio", "audio", "🎙️ ১০ সেকেন্ডের অডিও ক্লিপ")
            } catch (e: Exception) {
                sendMessage(chatId, "❌ অডিও রেকর্ড এরর: ${e.message}")
            } finally {
                try {
                    recorder?.release()
                } catch (_: Exception) {
                }
                audioFile.delete()
            }
        }.start()
    }

    private fun takePhoto(chatId: Long, isFront: Boolean) {
        val camName = if (isFront) "ফ্রন্ট ক্যামেরা" else "ব্যাক ক্যামেরা"
        sendMessage(chatId, "📸 $camName দিয়ে ছবি তোলা হচ্ছে...")

        val wasMotion = motionEnabled
        motionEnabled = false

        val mainExecutor = ContextCompat.getMainExecutor(this)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val selector = if (isFront) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this@BotService, selector, imageCapture)

                val photoFile = File(cacheDir, "sec_${System.currentTimeMillis()}.jpg")
                val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

                mainHandler.postDelayed({
                    imageCapture.takePicture(
                        outputOptions,
                        cameraExecutor,
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                mainExecutor.execute {
                                    cameraProvider.unbindAll()
                                    if (wasMotion) startMotionDetection(chatId, silent = true)
                                }
                                sendFile(chatId, photoFile, "sendPhoto", "photo", "📷 $camName-র ছবি")
                                photoFile.delete()
                            }

                            override fun onError(exc: ImageCaptureException) {
                                mainExecutor.execute {
                                    cameraProvider.unbindAll()
                                    if (wasMotion) startMotionDetection(chatId, silent = true)
                                }
                                sendMessage(chatId, "❌ ছবি তুলতে সমস্যা: ${exc.message}")
                            }
                        }
                    )
                }, 600)
            } catch (e: Exception) {
                sendMessage(chatId, "❌ ক্যামেরা এরর: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun startMotionDetection(chatId: Long, silent: Boolean = false) {
        val mainExecutor = ContextCompat.getMainExecutor(this)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                prevFrameSamples = null
                motionStartTime = System.currentTimeMillis()
                motionEnabled = true

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    try {
                        if (!motionEnabled) return@setAnalyzer
                        val buffer = imageProxy.planes[0].buffer
                        val data = ByteArray(buffer.remaining())
                        buffer.get(data)

                        val step = 32
                        val sampleSize = data.size / step
                        val currentSamples = ByteArray(sampleSize)
                        for (i in 0 until sampleSize) {
                            currentSamples[i] = data[i * step]
                        }

                        val prev = prevFrameSamples
                        prevFrameSamples = currentSamples

                        val now = System.currentTimeMillis()
                        if (prev != null && prev.size == currentSamples.size && now - motionStartTime > 3000L) {
                            var changed = 0
                            for (i in currentSamples.indices) {
                                val diff = abs((currentSamples[i].toInt() and 0xFF) - (prev[i].toInt() and 0xFF))
                                if (diff > 35) changed++
                            }
                            val changePct = (changed * 100f) / currentSamples.size
                            if (changePct > 12f && now - lastMotionSentTime > 15_000L) {
                                lastMotionSentTime = now
                                val bmp = imageProxy.toBitmap()
                                val file = File(cacheDir, "motion_${now}.jpg")
                                FileOutputStream(file).use { fos ->
                                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, fos)
                                }
                                sendFile(chatId, file, "sendPhoto", "photo", "🏃 মোশন ডিটেক্ট হয়েছে! (${changePct.toInt()}% change)")
                                file.delete()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        imageProxy.close()
                    }
                }

                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this@BotService, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                if (!silent) sendMessage(chatId, "🏃 মোশন ডিটেকশন চালু হয়েছে! ক্যামেরার সামনে নড়াচড়া হলেই ছবি পাঠাবে।")
            } catch (e: Exception) {
                motionEnabled = false
                sendMessage(chatId, "❌ মোশন ডিটেকশন এরর: ${e.message}")
            }
        }, mainExecutor)
    }

    private fun stopMotionDetection(chatId: Long) {
        motionEnabled = false
        ContextCompat.getMainExecutor(this).execute {
            try {
                ProcessCameraProvider.getInstance(this).get().unbindAll()
            } catch (_: Exception) {
            }
        }
        sendMessage(chatId, "🛑 মোশন ডিটেকশন বন্ধ করা হয়েছে।")
    }

    private fun readLightSensor(chatId: Long) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_LIGHT)
        if (sensor == null) {
            sendMessage(chatId, "❌ এই ফোনে Light Sensor নেই।")
            return
        }
        var replied = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (replied || event == null) return
                replied = true
                sm.unregisterListener(this)
                val lux = event.values[0]
                val desc = when {
                    lux < 10f -> "একদম অন্ধকার"
                    lux < 100f -> "হালকা আলো / ডিম লাইট"
                    lux < 500f -> "ঘরের স্বাভাবিক আলো"
                    else -> "খুব উজ্জ্বল আলো / রোদ"
                }
                Thread { sendMessage(chatId, "💡 ঘরের আলো: $lux lux ($desc)") }.start()
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        mainHandler.postDelayed({
            if (!replied) {
                replied = true
                sm.unregisterListener(listener)
                Thread { sendMessage(chatId, "⚠️ Light Sensor থেকে রিডিং পাওয়া যায়নি (স্ক্রিন অন থাকলে ভালো কাজ করে)।") }.start()
            }
        }, 2500)
    }

    private fun sendNetworkInfo(chatId: Long) {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val connType = when {
            caps == null -> "Offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
            else -> "Other"
        }

        var localIp = "N/A"
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        localIp = addr.hostAddress ?: "N/A"
                        break
                    }
                }
            }
        } catch (_: Exception) {
        }

        var publicIp = "N/A"
        try {
            val req = Request.Builder().url("https://api.ipify.org").build()
            client.newCall(req).execute().use { res ->
                if (res.isSuccessful) publicIp = res.body?.string() ?: "N/A"
            }
        } catch (_: Exception) {
        }

        sendMessage(chatId, "🌐 নেটওয়ার্ক তথ্য:\n• মাধ্যম: $connType\n• Local IP: $localIp\n• Public IP: $publicIp")
    }

    private fun sendSystemInfo(chatId: Long) {
        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorage = stat.totalBytes / (1024f * 1024f * 1024f)
        val freeStorage = stat.availableBytes / (1024f * 1024f * 1024f)

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalRam = memInfo.totalMem / (1024f * 1024f * 1024f)
        val freeRam = memInfo.availMem / (1024f * 1024f * 1024f)

        val msg = "💾 সিস্টেম স্ট্যাটাস:\n" +
                "• ফ্রি স্টোরেজ: %.2f GB / %.2f GB\n".format(freeStorage, totalStorage) +
                "• ফ্রি RAM: %.2f GB / %.2f GB\n".format(freeRam, totalRam) +
                "• মডেল: ${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
        sendMessage(chatId, msg)
    }

    private fun setVolumePercent(chatId: Long, arg: String) {
        val pct = arg.toIntOrNull()?.coerceIn(0, 100)
        if (pct == null) {
            sendMessage(chatId, "❌ সঠিক সংখ্যা দিন, যেমন: `/vol 80`")
            return
        }
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val maxRing = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusic * pct) / 100, 0)
        am.setStreamVolume(AudioManager.STREAM_RING, (maxRing * pct) / 100, 0)
        sendMessage(chatId, "🔊 ভলিউম $pct% সেট করা হয়েছে।")
    }

    private fun setRingerMode(chatId: Long, mode: Int, successMsg: String) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.ringerMode = mode
            sendMessage(chatId, successMsg)
        } catch (_: SecurityException) {
            sendMessage(chatId, "⚠️ Silent মোডের জন্য ফোনের Settings থেকে Do Not Disturb Access দিতে হবে।")
        }
    }

    private fun callIoTUrl(chatId: Long, url: String) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            sendMessage(chatId, "❌ সঠিক URL দিন, যেমন: `/iot http://192.168.1.50/relay/on`")
            return
        }
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string()?.take(250) ?: ""
                sendMessage(chatId, "🔌 IoT Response (${res.code}):\n$body")
            }
        } catch (e: Exception) {
            sendMessage(chatId, "❌ IoT ডিভাইসে কানেক্ট করা যায়নি: ${e.message}")
        }
    }

    private fun sendMessage(chatId: Long, text: String, replyMarkup: JSONObject? = null) {
        try {
            val json = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                if (replyMarkup != null) put("reply_markup", replyMarkup)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/sendMessage")
                .post(body)
                .build()
            client.newCall(req).execute().close()
        } catch (_: Exception) {
        }
    }

    private fun sendFile(chatId: Long, file: File, endpoint: String, fieldName: String, caption: String) {
        try {
            val mime = if (fieldName == "photo") "image/jpeg" else "audio/mp4"
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId.toString())
                .addFormDataPart("caption", caption)
                .addFormDataPart(fieldName, file.name, file.asRequestBody(mime.toMediaType()))
                .build()

            val req = Request.Builder()
                .url("https://api.telegram.org/bot$botToken/$endpoint")
                .post(body)
                .build()
            client.newCall(req).execute().close()
        } catch (e: Exception) {
            sendMessage(chatId, "❌ ফাইল পাঠাতে সমস্যা: ${e.message}")
        }
    }

    override fun onDestroy() {
        isRunning = false
        stopAlarmInternal()
        try {
            if (batteryReceiver != null) unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
        }
        tts?.stop()
        tts?.shutdown()
        cameraExecutor.shutdown()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? = super.onBind(intent)
}