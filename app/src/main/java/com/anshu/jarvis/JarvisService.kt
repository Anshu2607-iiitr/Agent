package com.anshu.jarvis

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Phase 7: "Jarvis mode".
 * A foreground service that keeps listening for the wake word "Jarvis",
 * even when the app is closed or the screen is locked.
 *
 *   [Porcupine: waiting for "Jarvis"] --heard--> beep --> [SpeechRecognizer: hear command]
 *          ^                                                        |
 *          |                                                        v
 *          +------------ resume <-- speak reply <-- CommandRouter.handle()
 */
class JarvisService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var busy = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { JarvisVoice.setup(it) }
                tts?.speak(JarvisVoice.ONLINE, TextToSpeech.QUEUE_FLUSH, null, "online")
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { afterSpeech(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeech(id) } }
        })
    }

    /** "Yes, sir?" finished → start listening. Reply finished → wait for "Jarvis" again. */
    private fun afterSpeech(id: String?) {
        when (id) {
            "ack" -> listenForCommand()
            "reply" -> resume()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Say \"Jarvis\"…"), type)

        if (porcupine == null) startWakeWord()
        return START_STICKY
    }

    private fun startWakeWord() {
        val key = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCESS, "").orEmpty()
        if (key.isBlank()) {
            updateNotification("Add your Picovoice AccessKey in the app")
            return
        }
        try {
            porcupine = PorcupineManager.Builder()
                .setAccessKey(key)
                .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
                .setSensitivity(0.7f)
                .build(applicationContext) { _ -> main.post { onWakeWord() } }
            porcupine?.start()
            running = true
        } catch (e: Exception) {
            porcupine = null
            updateNotification("Wake word error: ${e.message}")
        }
    }

    private fun onWakeWord() {
        if (busy) return
        busy = true
        // Release the mic so the speech recognizer can use it
        try { porcupine?.stop() } catch (_: Exception) {}
        updateNotification("Listening…")
        listening = false
        val ok = tts?.speak(JarvisVoice.WAKE_ACK, TextToSpeech.QUEUE_FLUSH, null, "ack")
        if (ok != TextToSpeech.SUCCESS) { beep(); main.postDelayed({ listenForCommand() }, 250) }
        // Safety net if the voice engine never reports "done"
        main.postDelayed({ if (busy && !listening) listenForCommand() }, 2500)
    }

    private var listening = false

    private fun listenForCommand() {
        if (listening) return
        listening = true
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            say(JarvisVoice.style("Speech recognition is not available on this phone"))
            return
        }
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle?) {
                    val heard = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (heard.isNullOrBlank()) resume()
                    else {
                        val reply = JarvisVoice.style(
                            try {
                                CommandRouter.handle(this@JarvisService, heard)
                            } catch (e: Exception) {
                                "Sorry, something went wrong"
                            }
                        )
                        lastLog = "You: $heard\nJarvis: $reply"
                        say(reply)
                    }
                }

                override fun onError(error: Int) {
                    say(JarvisVoice.style("Sorry, I didn't catch that"))
                }
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    private fun say(text: String) {
        val ok = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reply")
        // Resume when the reply finishes (afterSpeech); fallback in case TTS fails
        main.removeCallbacks(resumeSafety)
        main.postDelayed(resumeSafety, if (ok == TextToSpeech.SUCCESS) 20000 else 500)
    }

    private val resumeSafety = Runnable { resume() }

    private fun resume() {
        main.removeCallbacks(resumeSafety)
        if (!busy) return
        listening = false
        recognizer?.destroy()
        recognizer = null
        try { porcupine?.start() } catch (_: Exception) {}
        busy = false
        updateNotification("Say \"Jarvis\"…")
    }

    private fun beep() {
        try {
            ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150)
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        try {
            porcupine?.stop()
            porcupine?.delete()
        } catch (_: Exception) {}
        porcupine = null
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------- notification ----------

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Jarvis listening", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Jarvis is on")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val PREFS = "jarvis"
        const val KEY_ACCESS = "picovoice_access_key"
        private const val CHANNEL = "jarvis_listening"
        private const val NOTIF_ID = 7
        @Volatile var running = false
        @Volatile var lastLog: String? = null
    }
}
