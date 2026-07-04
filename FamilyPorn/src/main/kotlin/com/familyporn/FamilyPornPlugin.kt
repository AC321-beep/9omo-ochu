package com.familyporn

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FamilyPornPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(FamilyPorn())
        registerExtractorAPI(Fireplayer())
        registerExtractorAPI(VideoStreamingWorld())
        registerExtractorAPI(BestWish())
    }
}
