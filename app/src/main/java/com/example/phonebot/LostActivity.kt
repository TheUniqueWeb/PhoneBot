package com.example.phonebot

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class LostActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val msg = intent.getStringExtra("lost_msg")
            ?: "এই ফোনটি হারিয়ে গেছে! দয়া করে মালিকের সাথে যোগাযোগ করুন।"

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#B71C1C"))
            setPadding(64, 64, 64, 64)
        }

        val iconText = TextView(this).apply {
            text = "⚠️ LOST PHONE ⚠️"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }

        val bodyText = TextView(this).apply {
            text = msg
            textSize = 20f
            setTextColor(Color.YELLOW)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 48)
        }

        val closeBtn = Button(this).apply {
            text = "Dismiss"
            setOnClickListener { finish() }
        }

        layout.addView(iconText)
        layout.addView(bodyText)
        layout.addView(closeBtn)
        setContentView(layout)
    }
}