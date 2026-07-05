package com.familyporn

import android.content.Context
import androidx.fragment.app.Fragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FamilyPorn())
        // Register only the merged extractor – it handles all three hosters
        registerExtractorAPI(FamilyPornExtractor())
    }

    override fun getSettingsFragment(): Fragment? {
        return FamilyPornSettingsFragment()
    }

    companion object {
        var cfCookies: String = ""
        var cfCookieHost: String = ""
        var cfUserAgent: String = ""
    }
}
