package com.roshy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class RoshyPlugin : Plugin() {
    override fun load(context: Context) {
        // Register the main provider
        registerMainAPI(RoshyProvider())
        
        // Register the custom extractor (if the site hosts its own videos)
        registerExtractorAPI(RoshyExtractor())
    }
}
