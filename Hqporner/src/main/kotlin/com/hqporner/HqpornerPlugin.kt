package com.hqporner

import android.content.Context
import com.lagradost.cloudstream3.CloudstreamPlugin
import com.lagradost.cloudstream3.Plugin
import com.lagradost.cloudstream3.registerMainAPI

@CloudstreamPlugin
class HQPornerPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(HQPornerProvider())
    }
}
