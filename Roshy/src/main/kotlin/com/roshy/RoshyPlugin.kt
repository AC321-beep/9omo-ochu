package com.roshy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RoshyPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(RoshyProvider())
        registerExtractorAPI(RoshyExtractor())
    }
}
