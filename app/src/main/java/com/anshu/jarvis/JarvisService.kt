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
 * Always-on Jarvis. Works even when the app is closed or the screen is locked.
 *
 * Two ways to hear the wake word:
 *  - LOOP mode (default, no setup): Google speech recognizer listens non-stop.
 *    "Jarvis volume badhao" in one breath runs immediately.
 *    "Jarvis" alone → "Yes, sir?" → next sentence is the command.
 *  - PORCUPINE mode (if a Picovoice AccessKey is saved): a tiny offline wake-word
 *    engine waits for "Jarvis" → "Yes, sir?" → recognizer hears the command. Uses less battery.
 */
class JarvisService : Service() {

    private enum class Mode { LOOP, PORCUPINE }

    private val main = Handler(Looper.getMainLooper())
    private var mode = Mode.LOOP
    private var porcupine: PorcupineManager? = null
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var engineStarted = false
    private var stopped = false
    private var busy = false              // speaking or running a command
    private var awaitingCommand = false   // heard "Jarvis" alone; next sentence is the command
    private var systemMuted = false

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------------- lifecycle ----------------

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.let { JarvisVoice.setup(it) }
                speak(JarvisVoice.ONLINE, "online")
            } else {
                startEngine()
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { main.post { afterSpeech(id) } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { main.post { afterSpeech(id) } }
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification("Starting…"), type)
        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        running = false
        main.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        try { porcupine?.stop(); porcupine?.delete() } catch (_: Exception) {}
        porcupine = null
        setSystemMuted(false)
        tts?.shutdown()
        super.onDestroy()
    }

    // ---------------- engine selection ----------------

    private fun startEngine() {
        if (engineStarted || stopped) return
        engineStarted = true
        val key = getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ACCESS, "").orEmpty().trim()
        if (key.isNotEmpty() && startPorcupine(key)) {
            mode = Mode.PORCUPINE
        } else {
            mode = Mode.LOOP
            setSystemMuted(true)   // hides the recognizer's start/stop beeps
        }
        idle()
    }

    private fun startPorcupine(key: String): Boolean = try {
        porcupine = PorcupineManager.Builder()
            .setAccessKey(key)
            .setKeyword(Porcupine.BuiltInKeyword.JARVIS)
            .setSensitivity(0.7f)
            .build(applicationContext) { _ -> main.post { onPorcupineWake() } }
        true
    } catch (e: Exception) {
        porcupine = null
        lastLog = "Picovoice key didn't work (${e.message}). Using built-in listening instead."
        false
    }

    /** Go back to waiting for "Jarvis". */
    private fun idle() {
        if (stopped) return
        busy = false
        awaitingCommand = false
        if (mode == Mode.PORCUPINE) {
            recognizer?.destroy(); recognizer = null
            try {
                porcupine?.start()
                updateNotification("Say \"Jarvis\"…")
            } catch (e: Exception) {
                // Wake-word engine died → fall back to loop mode
                mode = Mode.LOOP
                setSystemMuted(true)
                updateNotification("Listening — say \"Jarvis …\"")
                listen()
            }
        } else {
            updateNotification("Listening — say \"Jarvis …\"")
            listen()
        }
    }

    private fun onPorcupineWake() {
        if (busy) return
        busy = true
        awaitingCommand = true
        try { porcupine?.stop() } catch (_: Exception) {}
        updateNotification("Yes, sir?")
        speak(JarvisVoice.WAKE_ACK, "ack")
    }

    // ---------------- speech recognition ----------------

    private fun listen() {
        if (stopped) return
        recognizer?.destroy()
        recognizer = null
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition not available — install/enable the Google app")
            return
        }
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(listener)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val all = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            onHeard(all)
        }

        override fun onError(error: Int) {
            if (stopped) return
            when {
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    updateNotification("Microphone permission missing — open the app")
                awaitingCommand -> {
                    busy = true
                    awaitingCommand = false
                    speak(JarvisVoice.style("Sorry, I didn't catch that"), "reply")
                }
                mode == Mode.LOOP -> {
                    // Normal: silence / no match → just listen again
                    val wait = when (error) {
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 3000L
                        SpeechRecognizer.ERROR_CLIENT -> 600L
                        else -> 150L
                    }
                    main.postDelayed({ if (!busy && !stopped) listen() }, wait)
                }
                else -> idle()
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun onHeard(options: List<String>) {
        if (stopped) return

        // 1) We already said "Yes, sir?" → whatever comes now is the command
        if (awaitingCommand) {
            awaitingCommand = false
            val cmd = options.firstOrNull().orEmpty()
            if (cmd.isBlank()) idle()
            else execute(afterWakeWord(cmd)?.takeIf { it.isNotBlank() } ?: cmd)
            return
        }

        // 2) Only act when the sentence contains the wake word
        val withWake = options.firstNotNullOfOrNull { afterWakeWord(it) }
        when {
            withWake == null -> if (mode == Mode.LOOP) listen() else idle()   // random talk → ignore
            withWake.isBlank() -> {
                busy = true
                awaitingCommand = true
                updateNotification("Yes, sir?")
                speak(JarvisVoice.WAKE_ACK, "ack")
            }
            else -> execute(withWake)
        }
    }

    private fun execute(command: String) {
        busy = true
        updateNotification("Working on it…")
        val reply = JarvisVoice.style(
            try {
                CommandRouter.handle(this, command)
            } catch (e: Exception) {
                "Sorry, something went wrong"
            }
        )
        lastLog = "You: $command\nJarvis: $reply"
        speak(reply, "reply")
    }

    // ---------------- speaking ----------------

    private var pendingId: String? = null
    private val speechSafety = Runnable { pendingId?.let { afterSpeech(it) } }

    private fun speak(text: String, id: String) {
        // Stop listening while talking so Jarvis doesn't hear itself
        recognizer?.cancel()
        pendingId = id
        main.removeCallbacks(speechSafety)
        val ok = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (ok != TextToSpeech.SUCCESS) {
            if (id == "ack") beep()
            main.postDelayed(speechSafety, 300)
        } else {
            main.postDelayed(speechSafety, 20_000) // in case the engine never says "done"
        }
    }

    private fun afterSpeech(id: String?) {
        if (id == null || id != pendingId) return
        pendingId = null
        main.removeCallbacks(speechSafety)
        when (id) {
            "online" -> startEngine()
            "ack" -> listen()      // awaitingCommand is true → next sentence = command
            "reply" -> idle()
        }
    }

    // ---------------- helpers ----------------

    private fun beep() {
        try { ToneGenerator(AudioManager.STREAM_MUSIC, 80).startTone(ToneGenerator.TONE_PROP_BEEP, 150) } catch (_: Exception) {}
    }

    private fun setSystemMuted(mute: Boolean) {
        if (mute == systemMuted) return
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.adjustStreamVolume(
                AudioManager.STREAM_SYSTEM,
                if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0
            )
            systemMuted = mute
        } catch (_: Exception) {}
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
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
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            )
            .build()

    private fun updateNotification(text: String) {
        if (stopped) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    companion object {
        const val PREFS = "jarvis"
        const val KEY_ACCESS = "picovoice_access_key"
        private const val CHANNEL = "jarvis_listening"
        private const val NOTIF_ID = 7
        @Volatile var running = false
        @Volatile var lastLog: String? = null

        // Ways the recognizer may spell "Jarvis"
        private val WAKE = Regex(
            """\b(?:hey |hi |hello |ok |okay )?(jarvis|jarvis's|jervis|jarvas|jarves|jarvi|javis|jaarvis|charvis|garvis)\b[\s,.:!-]*(.*)""",
            RegexOption.IGNORE_CASE
        )

        /** "hey jarvis volume up" → "volume up"; "jarvis" → ""; no wake word → null */
        fun afterWakeWord(text: String): String? = WAKE.find(text)?.groupValues?.get(2)?.trim()
    }
}
