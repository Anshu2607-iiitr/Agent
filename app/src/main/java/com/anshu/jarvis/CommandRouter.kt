package com.anshu.jarvis

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import com.anshu.jarvis.actions.*

/**
 * Jarvis's brain (rule-based). Each Rule = a regex pattern + what to do.
 * Rules are checked top to bottom; the first match wins, so specific rules go first.
 * Understands English + Hinglish.
 */
object CommandRouter {

    private class Rule(val regex: Regex, val run: (Context, MatchResult, String) -> String)

    private fun rule(pattern: String, run: (Context, MatchResult, String) -> String) =
        Rule(Regex(pattern), run)

    // ---------- word helpers ----------
    private val OFF = Regex("""\b(off|band|bandh|disable|bujha|hatao|stop|deactivate)\b""")
    private val ON = Regex("""\b(on|chalu|chala|enable|start|activate|laga|jala)\b""")
    private val UP = Regex("""\b(up|increase|badha\w*|jyada|zyada|louder|high|tez|raise)\b""")
    private val DOWN = Regex("""\b(down|decrease|kam|lower|ghata\w*|low|dheere|reduce)\b""")

    private fun wantOn(t: String): Boolean? = when {
        OFF.containsMatchIn(t) -> false
        ON.containsMatchIn(t) -> true
        else -> null
    }

    private fun g(m: MatchResult, i: Int) = m.groupValues.getOrNull(i).orEmpty().trim()

    private fun tile(names: List<String>, spoken: String) =
        { _: Context, _: MatchResult, t: String -> JarvisAccessibilityService.toggleTile(names, spoken, wantOn(t)) }

    const val HELP = "I can control volume, music, brightness, wifi, bluetooth, hotspot, " +
        "flashlight, do not disturb, make calls, send SMS and WhatsApp, set alarms and timers, " +
        "open apps, play YouTube or Spotify, navigate, search Google, read notifications, " +
        "take screenshots, lock the phone, go back or home, scroll, click and type on screen. Just call my name and ask."

    private val rules = listOf(
        // ---- meta ----
        rule("""\b(help|what can you do|kya kya kar sakte|commands)\b""") { _, _, _ -> HELP },
        rule("""\b(stop listening|go to sleep|so jao|sleep mode)\b""") { c, _, _ ->
            c.stopService(Intent(c, JarvisService::class.java)); "Going to sleep. Open the app to wake me."
        },
        rule("""^(hi|hello|hey)$|\bhow are you\b|\bkaise ho\b""") { _, _, _ -> "Hello Anshu, I'm ready. What should I do?" },

        // ---- laptop: "laptop pe vs code kholo", "on my computer open chrome" ----
        rule("""\b(laptop|computer|pc)\b""") { _, _, t -> LaptopBridge.MARKER + t },

        // ---- calls (put first: "end call" must not hit other rules) ----
        rule("""\b(answer|pick up|pick|uthao|utha lo|receive|accept)\b.*\bcall\b|^answer( it)?$""") { c, _, _ -> CommActions.answer(c) },
        rule("""\b(end|cut|reject|decline|kaat|kaato|hang up|disconnect)\b.*\bcall\b|^hang up$|^cut it$""") { c, _, _ -> CommActions.endCall(c) },
        rule("""^(?:call|phone|dial|ring)\s+(.+)$""") { c, m, _ -> CommActions.call(c, g(m, 1)) },
        rule("""^(.+?)\s+ko\s+(?:call|phone)\s*(?:karo|lagao|kar do)?$""") { c, m, _ -> CommActions.call(c, g(m, 1)) },

        // ---- messages: "whatsapp rahul saying i'm coming" / "rahul ko whatsapp karo ki main aa raha hoon" ----
        rule("""^(?:send )?(?:a )?whats ?app (?:message )?(?:to )?(.+?)\s+(?:saying|that|ki|bolo)\s+(.+)$""") { c, m, _ ->
            CommActions.whatsapp(c, g(m, 1), g(m, 2))
        },
        rule("""^(.+?)\s+ko\s+whats ?app\s+(?:karo|bhejo)?\s*(?:ki|that)?\s+(.+)$""") { c, m, _ ->
            CommActions.whatsapp(c, g(m, 1), g(m, 2))
        },
        rule("""^(?:send )?(?:a )?(?:message|sms|text|msg)\s+(?:to\s+)?(.+?)\s+(?:saying|that|ki)\s+(.+)$""") { c, m, _ ->
            CommActions.sms(c, g(m, 1), g(m, 2))
        },
        rule("""^(.+?)\s+ko\s+(?:message|sms|msg)\s+(?:karo|bhejo)?\s*(?:ki|that)?\s+(.+)$""") { c, m, _ ->
            CommActions.sms(c, g(m, 1), g(m, 2))
        },

        // ---- notifications ----
        rule("""\b(read|padho|sunao|suna do|any|check)\b.*\bnotifications?\b|\bwho (messaged|texted)\b|\bkisne message\b|\bnew messages\b""") { _, _, _ ->
            NotificationReader.readOut()
        },
        rule("""\b(clear|hatao|delete|remove)\b.*\bnotifications?\b""") { _, _, _ -> NotificationReader.clearAll() },
        rule("""\b(open|show|dikhao|kholo)\b.*\bnotifications?\b|\bnotification (panel|bar)\b""") { _, _, _ ->
            JarvisAccessibilityService.notifications()
        },
        rule("""\bquick settings\b""") { _, _, _ -> JarvisAccessibilityService.quickSettings() },

        // ---- media playback ----
        rule("""^(pause|ruko|ruk jao|stop)$|\b(pause|stop)\b.*\b(music|song|video|gaana|gana)\b|\b(music|song|gaana|gana) (band|ruko|roko)\b""") { c, _, _ ->
            AudioActions.mediaKey(c, KeyEvent.KEYCODE_MEDIA_PAUSE, "Paused")
        },
        rule("""^(resume|continue|play)$|\b(resume|continue)\b.*\b(music|song|video|playing)\b|\bplay (it|again)\b""") { c, _, _ ->
            AudioActions.mediaKey(c, KeyEvent.KEYCODE_MEDIA_PLAY, "Playing")
        },
        rule("""^(next|skip)$|\b(next|skip|agla|agle)\b.*\b(song|track|video|gaana|gana)\b""") { c, _, _ ->
            AudioActions.mediaKey(c, KeyEvent.KEYCODE_MEDIA_NEXT, "Next track")
        },
        rule("""^previous$|\b(previous|pichla|pichhla|last)\b.*\b(song|track|video|gaana|gana)\b""") { c, _, _ ->
            AudioActions.mediaKey(c, KeyEvent.KEYCODE_MEDIA_PREVIOUS, "Previous track")
        },

        // ---- volume ----
        rule("""\b(volume|awaaz|awaz|sound)\b.*?(\d{1,3})\s*(%|percent)?|(\d{1,3})\s*(%|percent)\s*(volume|awaaz|awaz)""") { c, m, _ ->
            val n = g(m, 2).ifEmpty { g(m, 4) }.toIntOrNull() ?: 50
            AudioActions.setPercent(c, n)
        },
        rule("""\b(full|max|maximum|poora|pura)\b.*\b(volume|awaaz|awaz)\b|\b(volume|awaaz|awaz)\b.*\b(full|max|maximum)\b""") { c, _, _ ->
            AudioActions.setPercent(c, 100)
        },
        rule("""^mute$|\bmute\b|\b(awaaz|awaz|volume) band\b|\bchup\b""") { c, _, t ->
            if (Regex("""\bun ?mute\b""").containsMatchIn(t)) AudioActions.setPercent(c, 50) else AudioActions.setPercent(c, 0)
        },
        rule("""\b(volume|awaaz|awaz|sound)\b""") { c, _, t ->
            when {
                UP.containsMatchIn(t) -> AudioActions.step(c, true)
                DOWN.containsMatchIn(t) -> AudioActions.step(c, false)
                else -> "Should I turn the volume up or down?"
            }
        },
        rule("""\b(louder)\b""") { c, _, _ -> AudioActions.step(c, true) },
        rule("""\b(quieter|softer)\b""") { c, _, _ -> AudioActions.step(c, false) },

        // ---- ringer mode ----
        rule("""\b(vibrate|vibration)\b(?: mode)?""") { c, _, t ->
            if (OFF.containsMatchIn(t)) AudioActions.ringer(c, AudioManager.RINGER_MODE_NORMAL)
            else AudioActions.ringer(c, AudioManager.RINGER_MODE_VIBRATE)
        },
        rule("""\bsilent\b""") { c, _, t ->
            if (OFF.containsMatchIn(t)) AudioActions.ringer(c, AudioManager.RINGER_MODE_NORMAL)
            else AudioActions.ringer(c, AudioManager.RINGER_MODE_SILENT)
        },
        rule("""\b(ring|ringer|normal|general) mode\b|\bringer on\b""") { c, _, _ ->
            AudioActions.ringer(c, AudioManager.RINGER_MODE_NORMAL)
        },

        // ---- do not disturb ----
        rule("""\b(dnd|do not disturb|don't disturb|disturb mat)\b""") { c, _, t ->
            DeviceActions.dnd(c, wantOn(t) != false)
        },

        // ---- brightness ----
        rule("""\bauto(matic)? brightness\b""") { c, _, _ -> DeviceActions.brightness(c, auto = true) },
        rule("""\b(brightness|roshni|light)\b.*?(\d{1,3})\s*(%|percent)?""") { c, m, _ ->
            DeviceActions.brightness(c, pct = g(m, 2).toIntOrNull())
        },
        rule("""\bbrightness\b""") { c, _, t ->
            when {
                Regex("""\b(full|max|maximum)\b""").containsMatchIn(t) -> DeviceActions.brightness(c, pct = 100)
                Regex("""\b(min|minimum|lowest)\b""").containsMatchIn(t) -> DeviceActions.brightness(c, pct = 2)
                UP.containsMatchIn(t) -> DeviceActions.brightness(c, up = true)
                DOWN.containsMatchIn(t) -> DeviceActions.brightness(c, up = false)
                else -> "Should I increase or decrease brightness?"
            }
        },
        rule("""\b(auto ?rotate|rotation|screen rotate)\b""") { c, _, t -> DeviceActions.autoRotate(c, wantOn(t) != false) },

        // ---- flashlight (direct API, faster than the tile) ----
        rule("""\b(torch|flashlight|flash light|flash)\b""") { c, _, t -> DeviceActions.torch(c, wantOn(t) != false) },

        // ---- quick settings tiles (robot taps the tile) ----
        rule("""\b(wi-?fi|wifi|wi fi)\b""", tile(listOf("wifi", "wi-fi", "internet"), "Wi-Fi")),
        rule("""\bbluetooth\b""", tile(listOf("bluetooth"), "Bluetooth")),
        rule("""\b(airplane|aeroplane|flight|aircraft)\b""", tile(listOf("airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane"), "airplane mode")),
        rule("""\bhotspot\b""", tile(listOf("hotspot", "mobile hotspot"), "hotspot")),
        rule("""\b(mobile data|data)\b""", tile(listOf("mobile data", "data"), "mobile data")),
        rule("""\b(location|gps)\b""", tile(listOf("location", "gps"), "location")),
        rule("""\b(battery saver|power saving|power saver)\b""", tile(listOf("battery saver", "power saving", "power mode"), "battery saver")),
        rule("""\bnfc\b""", tile(listOf("nfc"), "NFC")),

        // ---- phone / screen control ----
        rule("""\bscreenshot\b|\bscreen ?shot\b""") { _, _, _ -> JarvisAccessibilityService.screenshot() },
        rule("""\block\b""") { _, _, _ -> JarvisAccessibilityService.lock() },
        rule("""\b(switch off|power off|shut ?down|restart|reboot|power menu|phone (band|off|bandh))\b""") { _, _, _ ->
            JarvisAccessibilityService.powerMenu()
        },
        rule("""^(go )?back$|\b(go back|back jao|peeche jao|wapas)\b""") { _, _, _ -> JarvisAccessibilityService.back() },
        rule("""^(go )?home$|\b(home screen|home jao|go home)\b""") { _, _, _ -> JarvisAccessibilityService.home() },
        rule("""\b(recent apps|recents|recent|multitask)\b""") { _, _, _ -> JarvisAccessibilityService.recents() },
        rule("""\bscroll (down|neeche)\b|\bneeche (scroll|karo)\b|^(down|neeche)$""") { _, _, _ -> JarvisAccessibilityService.scroll(true) },
        rule("""\bscroll (up|upar|oopar)\b|\b(upar|oopar) (scroll|karo)\b|^(up|upar)$""") { _, _, _ -> JarvisAccessibilityService.scroll(false) },
        rule("""^(?:click|tap|press|select)\s+(?:on\s+)?(.+)$""") { _, m, _ -> JarvisAccessibilityService.click(g(m, 1)) },
        rule("""^(.+?)\s+(?:pe|par)\s+(?:click|tap)\s*(?:karo|kar do)?$|^(.+?)\s+dabao$""") { _, m, _ ->
            JarvisAccessibilityService.click(g(m, 1).ifEmpty { g(m, 2) })
        },
        rule("""^(?:type|likho|write)\s+(.+)$""") { _, m, _ -> JarvisAccessibilityService.type(g(m, 1)) },

        // ---- alarms & timers ----
        rule("""\btimer\b.*?(\d+)\s*(second|sec|minute|min|minat|hour|ghanta|ghante)|(\d+)\s*(second|sec|minute|min|minat|hour|ghanta|ghante)\b.*\btimer\b""") { c, m, _ ->
            val n = g(m, 1).ifEmpty { g(m, 3) }.toIntOrNull() ?: 1
            val unit = g(m, 2).ifEmpty { g(m, 4) }
            val secs = when {
                unit.startsWith("h") || unit.startsWith("ghant") -> n * 3600
                unit.startsWith("s") -> n
                else -> n * 60
            }
            DeviceActions.timer(c, secs)
        },
        rule("""\b(alarm|wake me|utha dena|uthana)\b.*?(\d{1,2})(?:[:. ](\d{2}))?\s*(am|pm|a m|p m|baje|o'clock)?""") { c, m, t ->
            var h = g(m, 2).toIntOrNull() ?: 7
            val min = g(m, 3).toIntOrNull() ?: 0
            val ap = g(m, 4)
            val evening = ap.startsWith("p") || Regex("""\b(shaam|raat|evening|night)\b""").containsMatchIn(t)
            if (evening && h < 12) h += 12
            if (ap.startsWith("a") && h == 12) h = 0
            DeviceActions.alarm(c, h, min)
        },

        // ---- info ----
        rule("""\bbattery\b""") { c, _, _ -> DeviceActions.battery(c) },
        rule("""\b(what time|time kya|kitne baje|kitna baja|samay|current time|time batao)\b|^time$""") { _, _, _ -> DeviceActions.time() },
        rule("""\b(what day|what date|today's date|aaj (ki )?(date|tarikh)|kya din|date batao)\b|^date$""") { _, _, _ -> DeviceActions.date() },

        // ---- camera ----
        rule("""\bselfie\b""") { c, _, _ -> DeviceActions.camera(c, selfie = true) },
        rule("""\b(camera|take (a )?photo|take (a )?picture|photo (lo|kheencho|khicho))\b""") { c, _, _ -> DeviceActions.camera(c, selfie = false) },

        // ---- settings pages ----
        rule("""\b(open )?settings\b(?! app)""") { c, _, _ -> DeviceActions.openSettings(c) },
        rule("""\bdisplay settings\b""") { c, _, _ -> DeviceActions.openSettings(c, Settings.ACTION_DISPLAY_SETTINGS, "display settings") },

        // ---- maps ----
        rule("""^(?:navigate to|directions to|take me to|go to|route to)\s+(.+)$""") { c, m, _ -> AppActions.navigate(c, g(m, 1)) },
        rule("""^(.+?)\s+(?:ka rasta|ka raasta|jaana hai|le chalo|chalna hai)(?: batao| dikhao)?$""") { c, m, _ -> AppActions.navigate(c, g(m, 1)) },

        // ---- music / video ----
        rule("""^play\s+(.+?)\s+on spotify$|^spotify (?:pe|par)\s+(.+?)\s+(?:chalao|bajao|lagao)$""") { c, m, _ ->
            AppActions.spotify(c, g(m, 1).ifEmpty { g(m, 2) })
        },
        rule("""^play\s+(.+?)(?:\s+on youtube)?$""") { c, m, _ -> AppActions.youtube(c, g(m, 1)) },
        rule("""^(?:youtube (?:pe|par)\s+)?(.+?)\s+(?:chalao|chala do|laga do|lagao|bajao|baja do|play karo|play kar do)$""") { c, m, _ ->
            AppActions.youtube(c, g(m, 1))
        },

        // ---- search ----
        rule("""^(?:search|google|search for|look up|find)\s+(.+)$""") { c, m, _ -> AppActions.search(c, g(m, 1)) },
        rule("""^(.+?)\s+(?:search karo|google karo|dhundho|search kar do)$""") { c, m, _ -> AppActions.search(c, g(m, 1)) },

        // ---- apps ----
        rule("""^(?:open|launch|start|kholo)\s+(.+)$""") { c, m, _ -> AppActions.openApp(c, g(m, 1).removeSuffix(" app")) },
        rule("""^(.+?)\s+(?:kholo|khol do|open karo|open kar do|chalu karo)$""") { c, m, _ ->
            AppActions.openApp(c, g(m, 1).removeSuffix(" app"))
        },

        // ---- any other question → Google it ----
        rule("""^(what|who|where|when|why|how|which|kya|kaun|kahan|kab|kyun|kaise|explain|tell me)\b.+""") { c, _, t ->
            if (LaptopBridge.isConfigured(c)) LaptopBridge.MARKER + t else AppActions.search(c, t)
        },
    )

    private fun clean(raw: String): String =
        raw.lowercase()
            .replace(Regex("""[!?,"]"""), " ")
            .replace(Regex("""(?<!\d)\.(?!\d)"""), " ")        // keep 7.30
            .replace(Regex("""^\s*(hey|hello|hi|ok|okay)?\s*jarvis\b"""), " ")
            .replace(Regex("""\b(please|plz|zara|jaldi)\b"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    fun handle(context: Context, raw: String): String {
        val text = clean(raw)
        if (text.isEmpty()) return "Yes?"
        for (r in rules) {
            val m = r.regex.find(text) ?: continue
            return r.run(context, m, text)
        }
        return if (LaptopBridge.isConfigured(context)) LaptopBridge.MARKER + text
        else "Sorry, I don't know how to do \"$text\" yet"
    }
}
