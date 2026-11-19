# 🤖 PhoneBot Pro — Personal Home Automation & Find-My-Phone Bot

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![MinSDK](https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-blue?style=for-the-badge)
![Build](https://img.shields.io/badge/Build-GitHub%20Actions-2088FF?style=for-the-badge&logo=github-actions&logoColor=white)

**PhoneBot Pro** is a lightweight, native Android background service controlled securely through your own private **Telegram Bot**. Turn your primary phone into an anti-theft Find-My-Phone tracker or repurpose an old Android device into a smart home automation & security hub — **compiled directly in the cloud using GitHub Actions (no PC or Android Studio required!).**

---

## ✨ Key Features

### 🛡️ Find-My-Phone & Security
* **📍 Live GPS Location:** Fetches fresh GPS/Network coordinates and sends a Telegram Map Pin + Google Maps link.
* **🚨 High-Volume Siren:** Maximizes alarm volume and plays the alarm ringtone continuously, even if the phone is on silent.
* **📷 Front & Back Security Photos:** Silently captures photos via **CameraX** in the background and uploads them to Telegram.
* **🎙️ 10-Second Audio Clip:** Records ambient sound from the microphone and sends it as an audio message.
* **📱 Lost Mode Full-Screen Popup:** Displays a bright red warning screen with your custom contact message, waking the screen if locked.
* **🔒 Remote Screen Lock:** Instantly locks the device screen using Android Device Administrator privileges.
* **🗣️ Text-to-Speech (TTS) & 📳 Vibration:** Makes the phone speak any text aloud or vibrate on command.

### 🏠 Home Automation & Monitoring
* **🏃 Camera Motion Detection:** Monitors the back camera feed in real time and automatically snaps & sends a photo when movement is detected.
* **💡 Ambient Light Sensor:** Reads room brightness in `lux` to check whether lights are on or off.
* **🔌 Local IoT Control:** Sends HTTP requests from the phone over your local Wi-Fi to control **ESP8266 / ESP32 / Tasmota / Home Assistant** smart devices.
* **🔔 Notification Forwarder:** Forwards incoming phone notifications (missed calls, alerts, etc.) straight to your Telegram.
* **🔋 Battery Status & Auto Alerts:** Sends automatic Telegram alerts when battery reaches `>= 98%` while charging or drops to `<= 15%`.
* **🌐 Network & 💾 System Info:** Reports Wi-Fi/Mobile status, Local & Public IP, free storage, RAM, and device info.
* **🔄 Auto-Start on Boot:** Automatically resumes background service after a device reboot.

---

## 🔐 Privacy & Security First
* **Zero Hardcoded Secrets:** Your Telegram `Bot Token` and `User ID` are **never stored in source code**. They are entered inside the app UI and saved locally in Android `SharedPreferences`.
* **Owner-Only Access:** Every incoming Telegram message is strictly verified against your numeric `Allowed User ID`. Unauthorized users are automatically blocked.

---

## 💬 Telegram Commands Cheat Sheet

| Command / Button | Description |
| :--- | :--- |
| `/start` or `/help` | Show interactive button menu & command list |
| `/location` | Get live GPS map pin & Google Maps link |
| `/battery` | Check battery percentage, charging state, temp & voltage |
| `/battery_alert_on` / `_off` | Toggle automatic high (98%) / low (15%) battery alerts |
| `/photo` / `/front_photo` | Capture & send Back or Front camera photo |
| `/motion_on` / `/motion_off` | Enable or disable real-time camera motion alerts |
| `/alarm` / `/stop_alarm` | Play or stop max-volume alarm siren |
| `/vibrate` | Vibrate phone for 2 seconds |
| `/say <text>` | Speak `<text>` aloud through the phone speaker |
| `/lost <message>` | Show full-screen Lost Mode alert on phone screen |
| `/lock` | Lock the phone screen immediately |
| `/mic` | Record & send a 10-second audio clip |
| `/light` | Read ambient light level (lux) from sensor |
| `/network` | Check connection type, Local IP & Public IP |
| `/system` | Check available storage, RAM & device model |
| `/vol <0-100>` | Set music & ringtone volume percentage |
| `/normal_mode` / `/vibrate_mode` / `/silent_mode` | Switch phone ringer mode |
| `/notif_on` / `/notif_off` | Toggle forwarding notifications to Telegram |
| `/iot <url>` | Trigger a local Wi-Fi smart home HTTP URL |

---

## 🚀 How to Build APK (Cloud Build via GitHub Actions)

1. Fork or push this repository to your GitHub account.
2. Go to the **Actions** tab in your repository.
3. Click on the latest **Build Android APK** workflow run (or trigger it manually via *Run workflow*).
4. Once completed (`✓`), scroll down to **Artifacts** and download **`PhoneBot-APK`**.
5. Extract the `.zip` file and install `app-debug.apk` on your Android device.

---

## 📱 Initial App Setup

1. Create a Telegram bot via [@BotFather](https://t.me/BotFather) and copy your **Bot Token**.
2. Get your numeric Telegram **User ID** from [@userinfobot](https://t.me/userinfobot).
3. Open **PhoneBot** on your phone and configure the buttons in order:
   * **1. Grant Core Permissions:** Allow Camera, Microphone, Location (`Allow all the time` recommended), and Notifications.
   * **2. Disable Battery Optimization:** Set to *Unrestricted* so Android doesn't kill the service in the background.
   * **3. Enable Screen Lock:** *(Optional)* Activate Device Admin for `/lock`.
   * **4. Allow Overlay:** *(Optional)* Allow *Display over other apps* for `/lost` popups.
   * **5. Enable Notification Access:** *(Optional)* Enable for `/notif_on` forwarding.
4. Paste your **Bot Token** and **User ID**, then tap **▶ Save & Start Bot Service**.
5. Open your bot in Telegram and send `/start`.

---

## 🛠️ Tech Stack

* **Kotlin** & **Android SDK** (Target SDK 34, Min SDK 26)
* **CameraX** (`camera-core`, `camera-camera2`, `camera-lifecycle`) for background capture & motion analysis
* **OkHttp 4** for Telegram Bot API long-polling & local IoT HTTP requests
* **Android LifecycleService** with Foreground Service (`camera|dataSync|mediaPlayback|microphone|location`)
* **GitHub Actions** (`gradle:8.7`, `JDK 17`) for automated APK compilation

---

## ⚠️ Disclaimer
This project is built strictly for **personal device management, Find-My-Phone recovery, and DIY home automation** on devices you own. Always comply with local laws and respect privacy.