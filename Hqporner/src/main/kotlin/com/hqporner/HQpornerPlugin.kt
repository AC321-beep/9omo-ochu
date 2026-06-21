package com.hqporner

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class HQPornerPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HQPornerProvider())
    }
}
