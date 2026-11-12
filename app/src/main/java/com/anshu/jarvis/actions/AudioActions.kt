package com.anshu.jarvis.actions

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/** Volume, media playback and ringer mode. */
object AudioActions {

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private const val MUSIC = AudioManager.STREAM_MUSIC

    fun step(c: Context, up: Boolean): String {
        val a = am(c)
        repeat(2) {
            a.adjustStreamVolume(
                MUSIC,
                if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI
            )
        }
        return if (up) "Volume increased" else "Volume decreased"
    }

    fun setPercent(c: Context, pct: Int): String {
        val a = am(c)
        val max = a.getStreamMaxVolume(MUSIC)
        a.setStreamVolume(MUSIC, (max * pct.coerceIn(0, 100) / 100), AudioManager.FLAG_SHOW_UI)
        return when (pct) {
            0 -> "Muted"
            100 -> "Volume at maximum"
            else -> "Volume set to $pct percent"
        }
    }

    fun mediaKey(c: Context, keyCode: Int, reply: String): String {
        val a = am(c)
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        a.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        return reply
    }

    fun ringer(c: Context, mode: Int): String = try {
        am(c).ringerMode = mode
        when (mode) {
            AudioManager.RINGER_MODE_SILENT -> "Phone is on silent"
            AudioManager.RINGER_MODE_VIBRATE -> "Phone is on vibrate"
            else -> "Ringer is on"
        }
    } catch (e: SecurityException) {
        "Give Jarvis Do Not Disturb access first"
    }
}
