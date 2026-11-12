package com.anshu.jarvis

import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

/**
 * Gives Jarvis a movie-JARVIS *style*: calm British male voice, slightly deeper,
 * and the polite "sir" way of talking. (Uses the phone's built-in voices.)
 */
object JarvisVoice {

    // Google TTS British male voices, best first
    private val PREFERRED = listOf("en-gb-x-rjs", "en-gb-x-gbd", "en-gb-x-gbb")

    fun setup(tts: TextToSpeech) {
        val voices: Set<Voice> = try { tts.voices ?: emptySet() } catch (e: Exception) { emptySet() }
        val british = voices.filter { it.locale.language == "en" && it.locale.country == "GB" }

        val pick = PREFERRED.firstNotNullOfOrNull { p -> british.firstOrNull { it.name.startsWith(p) && !it.isNetworkConnectionRequired } }
            ?: PREFERRED.firstNotNullOfOrNull { p -> british.firstOrNull { it.name.startsWith(p) } }
            ?: british.firstOrNull { v -> v.features.any { it.contains("male") && !it.contains("female") } }
            ?: british.firstOrNull()

        if (pick != null) tts.voice = pick else tts.language = Locale.UK
        tts.setPitch(0.88f)        // a little deeper
        tts.setSpeechRate(1.02f)   // crisp, not rushed
    }

    private val ACKS = listOf("Right away, sir.", "Done, sir.", "Of course, sir.", "As you wish, sir.")

    /** Turn "Volume increased" into "Volume increased, sir." JARVIS-style. */
    fun style(reply: String): String {
        val r = reply.trim().trimEnd('.')
        return when {
            r.isEmpty() -> ACKS.random()
            r.contains("sir", ignoreCase = true) -> "$r."
            r.endsWith("?") -> r.dropLast(1) + ", sir?"
            r.length > 90 -> "$r."                       // long answers: no extra fluff
            r.startsWith("Sorry") || r.startsWith("I couldn't") || r.startsWith("I can't") ->
                "Apologies, sir. " + r.removePrefix("Sorry, ").replaceFirstChar { it.uppercase() } + "."
            else -> "$r, sir."
        }
    }

    const val WAKE_ACK = "Yes, sir?"
    // (no word "Jarvis" here, so the wake word doesn't trigger on itself)
    const val ONLINE = "All systems online. At your service, sir."
}
