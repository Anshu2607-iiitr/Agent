package com.anshu.jarvis.actions

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Torch, brightness, DND, battery, time, alarms, timers, camera, settings pages. */
object DeviceActions {

    fun torch(c: Context, on: Boolean): String = try {
        val cm = c.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.setTorchMode(cm.cameraIdList.first(), on)
        if (on) "Flashlight on" else "Flashlight off"
    } catch (e: Exception) {
        "Flashlight not available"
    }

    fun brightness(c: Context, pct: Int? = null, up: Boolean? = null, auto: Boolean = false): String {
        if (!Settings.System.canWrite(c)) return "Allow Jarvis to modify system settings first"
        val cr = c.contentResolver
        if (auto) {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
            return "Auto brightness on"
        }
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128)
        val target = when {
            pct != null -> pct * 255 / 100
            up == true -> cur + 64
            else -> cur - 64
        }.coerceIn(5, 255)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, target)
        return "Brightness ${target * 100 / 255} percent"
    }

    fun autoRotate(c: Context, on: Boolean): String {
        if (!Settings.System.canWrite(c)) return "Allow Jarvis to modify system settings first"
        Settings.System.putInt(c.contentResolver, Settings.System.ACCELEROMETER_ROTATION, if (on) 1 else 0)
        return if (on) "Auto rotate on" else "Auto rotate off"
    }

    fun dnd(c: Context, on: Boolean): String {
        val nm = c.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return "Give Jarvis Do Not Disturb access first"
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY
            else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return if (on) "Do not disturb on" else "Do not disturb off"
    }

    fun battery(c: Context): String {
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "Battery is at $pct percent" + if (bm.isCharging) " and charging" else ""
    }

    fun time(): String = "It's " + SimpleDateFormat("h:mm a", Locale.ENGLISH).format(Date())
    fun date(): String = "Today is " + SimpleDateFormat("EEEE, d MMMM", Locale.ENGLISH).format(Date())

    fun alarm(c: Context, hour: Int, minute: Int, label: String = "Jarvis alarm"): String {
        c.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, label)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return "Alarm set for $h12:${"%02d".format(minute)} ${if (hour < 12) "AM" else "PM"}"
    }

    fun timer(c: Context, seconds: Int): String {
        c.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, "Jarvis timer")
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return when {
            seconds >= 3600 -> "Timer set for ${seconds / 3600} hour"
            seconds >= 60 -> "Timer set for ${seconds / 60} minutes"
            else -> "Timer set for $seconds seconds"
        }
    }

    fun camera(c: Context, selfie: Boolean): String {
        val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (selfie) {
            i.putExtra("android.intent.extras.CAMERA_FACING", 1)
            i.putExtra("android.intent.extras.LENS_FACING_FRONT", 1)
            i.putExtra("android.intent.extra.USE_FRONT_CAMERA", true)
        }
        c.startActivity(i)
        return if (selfie) "Opening selfie camera" else "Opening camera"
    }

    fun openSettings(c: Context, action: String = Settings.ACTION_SETTINGS, label: String = "settings"): String {
        c.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opening $label"
    }
}
