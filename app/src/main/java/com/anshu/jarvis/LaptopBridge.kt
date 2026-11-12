package com.anshu.jarvis

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Talks to the laptop's Jarvis (phone_server.py) over Wi-Fi.
 *
 *  phone: "open vs code on laptop" ──POST /ask──► laptop LLM + PC tools ──► {"reply": "..."} ──► phone speaks it
 *
 * Network calls must NOT run on the main thread — callers use askAsync().
 */
object LaptopBridge {

    const val MARKER = "@@LAPTOP:"
    private const val KEY_HOST = "laptop_host"
    private const val KEY_CODE = "laptop_code"

    private fun prefs(c: Context) = c.getSharedPreferences(JarvisService.PREFS, Context.MODE_PRIVATE)

    fun host(c: Context) = prefs(c).getString(KEY_HOST, "").orEmpty()
    fun code(c: Context) = prefs(c).getString(KEY_CODE, "").orEmpty()
    fun isConfigured(c: Context) = host(c).isNotBlank() && code(c).isNotBlank()

    fun save(c: Context, host: String, code: String) {
        prefs(c).edit().putString(KEY_HOST, host.trim()).putString(KEY_CODE, code.trim().uppercase()).apply()
    }

    private fun base(c: Context): String {
        var h = host(c).removePrefix("http://").removeSuffix("/")
        if (!h.contains(":")) h += ":8765"
        return "http://$h"
    }

    /** Blocking. Returns the laptop's spoken reply, or a friendly error. */
    fun ask(c: Context, text: String): String {
        if (!isConfigured(c)) return "Laptop link isn't set up yet. Add the laptop address and code in the app"
        return try {
            val conn = (URL(base(c) + "/ask").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000
                readTimeout = 60000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
            val body = JSONObject().put("token", code(c)).put("text", text).toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            when (conn.responseCode) {
                200 -> JSONObject(conn.inputStream.bufferedReader().readText()).optString("reply", "Done")
                403 -> "The laptop rejected the pairing code"
                else -> "The laptop returned an error"
            }
        } catch (e: Exception) {
            "I can't reach the laptop. Is Jarvis running there, and are both on the same Wi-Fi?"
        }
    }

    /** Blocking connectivity check for the Test button. */
    fun ping(c: Context): String = try {
        val conn = URL(base(c) + "/ping?token=" + code(c)).openConnection() as HttpURLConnection
        conn.connectTimeout = 4000
        conn.readTimeout = 4000
        when (conn.responseCode) {
            200 -> "✅ Connected to " + JSONObject(conn.inputStream.bufferedReader().readText()).optString("name", "laptop")
            403 -> "❌ Wrong pairing code"
            else -> "❌ Laptop answered with an error"
        }
    } catch (e: Exception) {
        "❌ Can't reach ${base(c)} — same Wi-Fi? Jarvis running on laptop?"
    }

    /** Run ask() on a background thread and deliver the reply on the main thread. */
    fun askAsync(c: Context, text: String, onReply: (String) -> Unit) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread { val r = ask(c.applicationContext, text); main.post { onReply(r) } }.start()
    }
}
