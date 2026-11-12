package com.anshu.jarvis.actions

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri

/** Phase 1: the "hands" of Jarvis. Each function does one real thing on the phone. */
object Actions {

    fun changeVolume(context: Context, up: Boolean): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        repeat(2) { // two steps feels more noticeable
            audio.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        }
        return if (up) "Volume increased" else "Volume decreased"
    }

    fun setVolumeMax(context: Context, max: Boolean): String {
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val target = if (max) audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) else 0
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, target, AudioManager.FLAG_SHOW_UI)
        return if (max) "Volume at maximum" else "Muted"
    }

    fun playOnYouTube(context: Context, query: String): String {
        // Opens the YouTube app if installed, otherwise the browser
        val url = "https://www.youtube.com/results?search_query=" + Uri.encode(query)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Playing $query on YouTube"
    }

    fun openApp(context: Context, name: String): String {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val match = pm.queryIntentActivities(launcher, 0).firstOrNull {
            it.loadLabel(pm).toString().contains(name, ignoreCase = true)
        } ?: return "I couldn't find an app called $name"

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I couldn't open $name"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Opening ${match.loadLabel(pm)}"
    }

    fun torch(context: Context, on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cm.setTorchMode(cm.cameraIdList.first(), on)
            if (on) "Flashlight on" else "Flashlight off"
        } catch (e: Exception) {
            "Flashlight not available"
        }
    }

    fun lockScreen(): String =
        if (JarvisAccessibilityService.lock()) "Locking the phone"
        else "Turn on Jarvis Controls in Accessibility settings first"

    fun powerMenu(): String =
        if (JarvisAccessibilityService.powerMenu()) "Opening power menu"
        else "Turn on Jarvis Controls in Accessibility settings first"
}
