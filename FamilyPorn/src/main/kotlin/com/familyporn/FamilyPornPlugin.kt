package com.familyporn

import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornPlugin : Plugin() {
    override fun load() {
        registerMainAPI(FamilyPorn())
        registerExtractorAPI(Fireplayer())
        registerExtractorAPI(VideoStreamingWorld())
        registerExtractorAPI(BestWish())

        // ✅ Correct settings hook – opens a DialogFragment
        this.openSettings = { ctx ->
            val activity = ctx as AppCompatActivity
            val frag = FamilyPornSettingsFragment()
            frag.show(activity.supportFragmentManager, "familyporn_settings")
        }
    }

    companion object {
        // ✅ Persistent storage using CloudStreamApp's getKey/setKey
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
