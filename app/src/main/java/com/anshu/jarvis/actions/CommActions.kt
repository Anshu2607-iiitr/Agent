package com.anshu.jarvis.actions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager

/** Calls, SMS and WhatsApp. */
object CommActions {

    private fun has(c: Context, perm: String) =
        c.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Returns (displayName, number) for a spoken name or number. */
    fun resolve(c: Context, who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 5) return who to digits
        if (!has(c, Manifest.permission.READ_CONTACTS)) return null

        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(who))
        c.contentResolver.query(
            uri,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )?.use { cur ->
            if (cur.moveToFirst()) return cur.getString(0) to cur.getString(1)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun call(c: Context, who: String): String {
        if (!has(c, Manifest.permission.READ_CONTACTS) && who.none { it.isDigit() })
            return "Give Jarvis contacts permission first"
        val (name, number) = resolve(c, who) ?: return "I couldn't find $who in your contacts"
        val action = if (has(c, Manifest.permission.CALL_PHONE)) Intent.ACTION_CALL else Intent.ACTION_DIAL
        c.startActivity(Intent(action, Uri.parse("tel:$number")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Calling $name"
    }

    @SuppressLint("MissingPermission")
    fun answer(c: Context): String {
        if (!has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return "Give Jarvis call permission first"
        c.getSystemService(TelecomManager::class.java).acceptRingingCall()
        return "Answering"
    }

    @SuppressLint("MissingPermission")
    fun endCall(c: Context): String {
        if (!has(c, Manifest.permission.ANSWER_PHONE_CALLS)) return "Give Jarvis call permission first"
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return "Not supported on this phone"
        c.getSystemService(TelecomManager::class.java).endCall()
        return "Call ended"
    }

    fun sms(c: Context, who: String, text: String): String {
        if (!has(c, Manifest.permission.SEND_SMS)) return "Give Jarvis SMS permission first"
        val (name, number) = resolve(c, who) ?: return "I couldn't find $who in your contacts"
        val sms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            c.getSystemService(SmsManager::class.java) else @Suppress("DEPRECATION") SmsManager.getDefault()
        sms.sendTextMessage(number, null, text, null, null)
        return "Message sent to $name: $text"
    }

    fun whatsapp(c: Context, who: String, text: String): String {
        val (name, raw) = resolve(c, who) ?: return "I couldn't find $who in your contacts"
        var number = raw.filter { it.isDigit() }
        if (number.length == 10) number = "91$number"           // Indian numbers without country code
        if (number.startsWith("0") && number.length == 11) number = "91" + number.drop(1)
        val url = "https://wa.me/$number?text=" + Uri.encode(text)
        c.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .setPackage("com.whatsapp")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        // Robot step: press WhatsApp's Send button for you
        JarvisAccessibilityService.clickLater("send", 3000)
        return "Sending WhatsApp message to $name"
    }
}
