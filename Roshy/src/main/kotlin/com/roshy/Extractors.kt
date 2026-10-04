package com.roshy

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*

class RoshyExtractor : ExtractorApi() {
    private val TAG = "RoshyExtractor"

    override val name: String = "Roshy"
    override val mainUrl: String = "https://roshy.tv"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "--------------------------------------------------")
        Log.d(TAG, "getUrl() called url=$url referer=$referer")

        if (url.endsWith(".mp4") || url.endsWith(".m3u8") || url.contains(".m3u8?")) {
            Log.d(TAG, "getUrl() Direct media detected. Sending callback.")

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = url,
                    type = if (url.contains(".m3u8")) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                ) {
                    this.referer = referer ?: mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            Log.d(TAG, "getUrl() callback invoked for $url")
        } else {
            Log.w(TAG, "getUrl() NOT a direct .mp4/.m3u8. URL=$url")
            Log.w(TAG, "getUrl() Custom iframe scraping not implemented yet for this host.")

            // Attempt to fetch the iframe page and look for direct links inside it.
            try {
                val doc = app.get(url, referer = referer ?: mainUrl).document
                Log.d(TAG, "getUrl() fetched iframe page title=${doc.title()}")

                // Look for direct media inside the iframe
                doc.select("source, video, a").forEachIndexed { i, el ->
                    val src = el.attr("src")
                        .ifEmpty { el.attr("href") }
                        .ifEmpty { el.attr("data-src") }
                    if (src.isNotBlank() &&
                        (src.contains(".mp4") || src.contains(".m3u8"))
                    ) {
                        val fixed = fixUrl(src)
                        Log.d(TAG, "getUrl() found direct media in iframe [$i]: $fixed")

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = this.name,
                                url = fixed,
                                type = if (fixed.contains(".m3u8")) {
                                    ExtractorLinkType.M3U8
                                } else {
                                    ExtractorLinkType.VIDEO
                                }
                            ) {
                                this.referer = referer ?: mainUrl
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "getUrl() failed to scrape iframe $url", e)
            }
        }
        Log.d(TAG, "--------------------------------------------------")
    }
}
