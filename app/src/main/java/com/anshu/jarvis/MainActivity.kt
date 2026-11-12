package com.anshu.jarvis

import android.app.Activity
import android.content.Intent
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Phase 4: voice replies
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
    val context = androidx.compose.ui.platform.LocalContext.current
    var typed by remember { mutableStateOf("") }
    val log = remember { mutableStateListOf<String>() }

    fun run(cmd: String) {
        if (cmd.isBlank()) return
        val reply = onCommand(cmd)
        log.add(0, "You: $cmd\nJarvis: $reply")
    }

    // Phase 3: voice input via Google's speech recognizer
    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val heard = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (heard != null) run(heard)
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
        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Bolo, kya karna hai?")
                }
                try {
                    speechLauncher.launch(intent)
                } catch (e: Exception) {
                    log.add(0, "Speech recognition not available on this phone")
                }
            },
            modifier = Modifier.size(140.dp)
        ) { Text("🎙️ Speak", fontSize = 20.sp) }

        Spacer(Modifier.height(20.dp))

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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { run("volume up") }) { Text("Vol +") }
            OutlinedButton(onClick = { run("volume down") }) { Text("Vol −") }
            OutlinedButton(onClick = { run("play lofi music") }) { Text("Lofi") }
        }
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Enable lock / power controls") }

        Spacer(Modifier.height(16.dp))
        log.forEach {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(it, modifier = Modifier.padding(12.dp))
            }
        }
    }
}
