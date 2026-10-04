package com.roshy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.* // Added wildcard for newExtractorLink

class RoshyExtractor : ExtractorApi() {
    override val name: String = "Roshy"
    override val mainUrl: String = "https://roshy.tv"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // If the URL parsed in RoshyProvider.kt is already a direct link
        if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.contains(".m3u8?")) {
            callback.invoke(
                newExtractorLink( // Changed from ExtractorLink to newExtractorLink
                    source = this.name,
                    name = this.name,
                    url = url,
                    referer = referer ?: mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = url.contains(".m3u8")
                )
            )
        } else {
            // If the URL is an iframe that needs to be scraped further:
            // val document = app.get(url, referer = referer).document
            // val rawVideoUrl = document.select("video source").attr("src")
            // callback.invoke(...)
        }
    }
}
