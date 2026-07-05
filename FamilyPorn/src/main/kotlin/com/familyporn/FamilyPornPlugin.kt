package com.familyporn

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FamilyPorn())
        registerExtractorAPI(FamilyPornExtractor())

        // Correct settings hook
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = FamilyPornSettingsFragment()
            frag.show(activity.supportFragmentManager, "familyporn_settings")
        }
    }

    companion object {
        var cfCookies: String
            get() = getKey("FAMILYPORN_CF_COOKIES") ?: ""
            set(value) { setKey("FAMILYPORN_CF_COOKIES", value) }

        var cfUserAgent: String
            get() = getKey("FAMILYPORN_CF_USER_AGENT") ?: ""
            set(value) { setKey("FAMILYPORN_CF_USER_AGENT", value) }

        var cfCookieHost: String
            get() = getKey("FAMILYPORN_CF_COOKIE_HOST") ?: ""
            set(value) { setKey("FAMILYPORN_CF_COOKIE_HOST", value) }
    }
}
