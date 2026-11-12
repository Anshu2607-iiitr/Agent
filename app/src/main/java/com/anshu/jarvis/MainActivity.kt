package com.anshu.jarvis

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.anshu.jarvis.actions.JarvisAccessibilityService

class MainActivity : ComponentActivity() {

    private var tts: TextToSpeech? = null
    private val resumeTick = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.let { JarvisVoice.setup(it) }
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    JarvisScreen(tick = resumeTick.intValue, onCommand = ::runCommand)
                }
            }
        }
    }

    // Re-check permissions every time the user comes back from Settings
    override fun onResume() {
        super.onResume()
        resumeTick.intValue++
    }

    private fun runCommand(text: String): String {
        val reply = JarvisVoice.style(
            try {
                CommandRouter.handle(this, text)
            } catch (e: Exception) {
                "Sorry, something went wrong: ${e.message}"
            }
        )
        tts?.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "jarvis")
        return reply
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }
}

private val PHONE_PERMS = arrayOf(
    Manifest.permission.READ_CONTACTS,
    Manifest.permission.CALL_PHONE,
    Manifest.permission.ANSWER_PHONE_CALLS,
    Manifest.permission.SEND_SMS,
)

private fun granted(c: Context, p: String) =
    ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

private fun open(c: Context, action: String, withPackage: Boolean = false) {
    val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (withPackage) i.data = Uri.parse("package:${c.packageName}")
    try { c.startActivity(i) } catch (e: Exception) {
        c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(if (done) "✅" else "⬜", modifier = Modifier.width(32.dp))
        Text(label, modifier = Modifier.weight(1f))
        if (!done) TextButton(onClick = onClick) { Text("Allow") }
    }
}

@Composable
fun JarvisScreen(tick: Int, onCommand: (String) -> String) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(JarvisService.PREFS, Context.MODE_PRIVATE) }
    var accessKey by remember { mutableStateOf(prefs.getString(JarvisService.KEY_ACCESS, "").orEmpty()) }
    var alwaysOn by remember { mutableStateOf(JarvisService.running) }
    var typed by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    val log = remember { mutableStateListOf<String>() }

    // Status of every power Jarvis needs (recomputed when we come back from Settings)
    val t = tick + refresh
    val nm = context.getSystemService(NotificationManager::class.java)
    val pm = context.getSystemService(PowerManager::class.java)
    val micOk = remember(t) { granted(context, Manifest.permission.RECORD_AUDIO) }
    val phoneOk = remember(t) { PHONE_PERMS.all { granted(context, it) } }
    val overlayOk = remember(t) { Settings.canDrawOverlays(context) }
    val a11yOk = remember(t) { JarvisAccessibilityService.isOn() }
    val notifOk = remember(t) { NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) }
    val writeOk = remember(t) { Settings.System.canWrite(context) }
    val dndOk = remember(t) { nm.isNotificationPolicyAccessGranted }
    val batteryOk = remember(t) { pm.isIgnoringBatteryOptimizations(context.packageName) }

    fun run(cmd: String) {
        if (cmd.isBlank()) return
        log.add(0, "You: $cmd\nJarvis: ${onCommand(cmd)}")
    }

    fun startService() {
        prefs.edit().putString(JarvisService.KEY_ACCESS, accessKey.trim()).apply()
        ContextCompat.startForegroundService(context, Intent(context, JarvisService::class.java))
        alwaysOn = true
    }

    val startPerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        refresh++
        if (result[Manifest.permission.RECORD_AUDIO] == true) startService()
        else log.add(0, "Microphone permission is needed for the \"Jarvis\" wake word")
    }
    val phonePerms = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refresh++ }

    val speechLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { run(it) }
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
            if (alwaysOn) "🟢 Always listening — say \"Jarvis, volume up\"" else "⚪ Wake word is off",
            modifier = Modifier.padding(top = 8.dp)
        )
        Spacer(Modifier.height(16.dp))

        if (!alwaysOn) {
            Button(
                onClick = {
                    val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    startPerms.launch(perms.toTypedArray())
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


        // ---- Wake word ----
        OutlinedTextField(
            value = accessKey,
            onValueChange = { accessKey = it },
            label = { Text("Picovoice AccessKey (optional, saves battery)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://console.picovoice.ai/")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("Optional: get a free AccessKey") }

        // ---- Setup checklist ----
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Text("Give Jarvis its powers", fontSize = 18.sp, modifier = Modifier.fillMaxWidth())
        Text(
            "Android 13+: if Accessibility / Notification access is greyed out, open App info → ⋮ → \"Allow restricted settings\" first.",
            fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        )
        SetupRow("Microphone", micOk) { startPerms.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
        SetupRow("Calls, contacts & SMS", phoneOk) { phonePerms.launch(PHONE_PERMS) }
        SetupRow("Robot controls (Accessibility)", a11yOk) { open(context, Settings.ACTION_ACCESSIBILITY_SETTINGS) }
        SetupRow("Read notifications", notifOk) { open(context, Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }
        SetupRow("Display over other apps", overlayOk) { open(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION, true) }
        SetupRow("Change brightness & rotation", writeOk) { open(context, Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
        SetupRow("Do Not Disturb & silent mode", dndOk) { open(context, Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS) }
        SetupRow("Run in background (battery)", batteryOk) {
            open(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, true)
        }
        TextButton(onClick = { open(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS, true) }) {
            Text("Open App info")
        }

        // ---- Manual control ----
        HorizontalDivider(Modifier.padding(vertical = 12.dp))
        Button(onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            }
            try { speechLauncher.launch(intent) } catch (e: Exception) { log.add(0, "Speech recognition not available") }
        }) { Text("🎙️ Speak once") }

        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Or type a command (try \"help\")") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(onClick = { run(typed); typed = "" }, modifier = Modifier.fillMaxWidth()) { Text("Run") }

        Spacer(Modifier.height(12.dp))
        JarvisService.lastLog?.let {
            Text("Last voice command:", modifier = Modifier.fillMaxWidth())
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(it, modifier = Modifier.padding(12.dp)) }
        }
        log.forEach {
            Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(it, modifier = Modifier.padding(12.dp)) }
        }
    }
}
