package com.anshu.jarvis.actions

import android.content.Context
import android.content.Intent
import android.net.Uri

/** Apps, YouTube, Spotify, Google search, Maps. */
object AppActions {

    private fun view(c: Context, uri: String, pkg: String? = null) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (pkg != null) i.setPackage(pkg)
        try {
            c.startActivity(i)
        } catch (e: Exception) {
            if (pkg == null) throw e
            view(c, uri, null) // app missing → let Android pick
        }
    }

    fun youtube(c: Context, query: String): String {
        view(c, "https://www.youtube.com/results?search_query=" + Uri.encode(query))
        // Robot step: tap the first video so it actually plays
        JarvisAccessibilityService.clickFirstVideoLater(3500)
        return "Playing $query on YouTube"
    }

    fun spotify(c: Context, query: String): String {
        view(c, "spotify:search:" + Uri.encode(query), "com.spotify.music")
        return "Searching $query on Spotify"
    }

    fun search(c: Context, query: String): String {
        view(c, "https://www.google.com/search?q=" + Uri.encode(query))
        return "Searching for $query"
    }

    fun navigate(c: Context, place: String): String {
        view(c, "google.navigation:q=" + Uri.encode(place), "com.google.android.apps.maps")
        return "Starting navigation to $place"
    }

    fun openApp(c: Context, name: String): String {
        val pm = c.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val clean = name.replace(" ", "")
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(name, true) }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().replace(" ", "").contains(clean, true) }
            ?: return "I couldn't find an app called $name"

        val intent = pm.getLaunchIntentForPackage(match.activityInfo.packageName)
            ?: return "I couldn't open $name"
        c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opening ${match.loadLabel(pm)}"
    }
}
