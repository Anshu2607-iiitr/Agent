package com.anshu.jarvis

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale("en", "IN")
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JarvisScreen(onCommand = ::runCommand)
                }
            }
        }
    }

    private fun runCommand(text: String): String {
        val reply = try {
            CommandRouter.handle(this, text)
        } catch (e: Exception) {
            "Something went wrong: ${e.message}"
        }
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        return reply
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

@Composable
fun JarvisScreen(onCommand: (String) -> String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(JarvisService.PREFS, Context.MODE_PRIVATE) }
    var accessKey by remember { mutableStateOf(prefs.getString(JarvisService.KEY_ACCESS, "").orEmpty()) }
    var alwaysOn by remember { mutableStateOf(JarvisService.running) }
    var typed by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun run(cmd: String) {
        if (cmd.isBlank()) return
        log.add(0, "You: $cmd\nJarvis: ${onCommand(cmd)}")
    }

    fun startService() {
        prefs.edit().putString(JarvisService.KEY_ACCESS, accessKey.trim()).apply()
        ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        alwaysOn = true
    }

    // Mic + notification permissions, then start the always-listening service
    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted[Manifest.permission.RECORD_AUDIO] == true) startService()
        else log.add(0, "Microphone permission is needed for \"Jarvis\" wake word")
    }

    // Manual speak button (still useful)
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()?.let { run(it) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        Text("J.A.R.V.I.S", fontSize = 32.sp)
        Text(
            if (alwaysOn) "🟢 Always listening — just say \"Jarvis\"" else "⚪ Wake word is off",
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))

        // ---- Always-on setup ----
        OutlinedTextField(
            value = accessKey,
            onValueChange = { accessKey = it },
            label = { Text("Picovoice AccessKey") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://console.picovoice.ai/"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Get a free AccessKey") }

        if (!alwaysOn) {
            Button(
                onClick = {
                    if (accessKey.isBlank()) {
                        log.add(0, "Paste your Picovoice AccessKey first")
                        return@Button
                    }
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    permLauncher.launch(perms.toTypedArray())
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Start Jarvis (always on)", fontSize = 18.sp) }
        } else {
            OutlinedButton(
                onClick = {
                    context.stopService(Intent(context, JarvisService::class.java))
                    alwaysOn = false
                },
                modifier = Modifier.fillMaxWidth().height(56.dp)
            ) { Text("Stop Jarvis", fontSize = 18.sp) }
        }

        Spacer(Modifier.height(8.dp))
        Text("One-time setup:", modifier = Modifier.fillMaxWidth())
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("1. Allow \"Display over other apps\" (to open YouTube/apps)") }
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("2. Enable Jarvis Controls (lock / power menu)") }
        TextButton(onClick = {
            context.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:${context.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("3. Battery → Unrestricted (so Jarvis isn't killed)") }

        HorizontalDivider(Modifier.padding(vertical = 12.dp))

        // ---- Manual control ----
        Button(onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            }
            try { speechLauncher.launch(intent) } catch (e: Exception) {
                log.add(0, "Speech recognition not available")
            }
        }) { Text("🎙️ Speak once") }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Or type a command") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { run(typed); typed = "" }, modifier = Modifier.fillMaxWidth()) {
            Text("Run")
        }

        Spacer(Modifier.height(12.dp))
        JarvisService.lastLog?.let {
            Text("Last voice command:", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }
        log.forEach {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
