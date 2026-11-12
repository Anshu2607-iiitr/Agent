package com.anshu.jarvis

import android.content.Context
import com.anshu.jarvis.actions.Actions

/**
 * Phase 2: the "brain" (rule-based for now; an LLM replaces this in Phase 5).
 * Understands simple English + Hinglish commands.
 */
object CommandRouter {

    fun handle(context: Context, raw: String): String {
        val text = raw.lowercase().trim()
            .removePrefix("hey jarvis").removePrefix("jarvis").trim(' ', ',')

        fun has(vararg words: String) = words.any { text.contains(it) }

        return when {
            // Volume
            has("mute", "silent", "chup") -> Actions.setVolumeMax(context, max = false)
            has("full volume", "max volume", "volume full") -> Actions.setVolumeMax(context, max = true)
            has("volume") && has("up", "increase", "badha", "jyada", "zyada", "louder") ->
                Actions.changeVolume(context, up = true)
            has("volume") && has("down", "decrease", "kam", "lower", "ghata") ->
                Actions.changeVolume(context, up = false)
            has("louder") -> Actions.changeVolume(context, up = true)
            has("quieter", "softer") -> Actions.changeVolume(context, up = false)

            // Flashlight
            has("torch", "flashlight", "flash light") ->
                Actions.torch(context, on = !has("off", "band", "bujha"))

            // Lock / power
            has("lock") -> Actions.lockScreen()
            has("switch off", "turn off the phone", "power off", "shut down", "shutdown",
                "phone band", "phone off", "restart", "power menu") -> Actions.powerMenu()

            // Play video: "play arijit songs" / "arijit songs chalao" / "youtube pe lofi chalao"
            text.startsWith("play ") -> Actions.playOnYouTube(context, text.removePrefix("play ").removeSuffix(" on youtube"))
            has("chalao", "laga do", "bajao") -> Actions.playOnYouTube(
                context,
                text.replace(Regex("(youtube pe|youtube par|chalao|laga do|bajao)"), "").trim()
            )

            // Open app: "open whatsapp" / "whatsapp kholo"
            text.startsWith("open ") -> Actions.openApp(context, text.removePrefix("open ").trim())
            has("kholo", "khol do") -> Actions.openApp(
                context, text.replace(Regex("(kholo|khol do)"), "").trim()
            )

            else -> "Sorry, I don't know how to do that yet"
        }
    }
}
