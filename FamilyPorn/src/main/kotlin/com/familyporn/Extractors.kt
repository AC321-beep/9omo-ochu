package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        getUrl(url, referer, subtitleCallback = {}, callback = { links.add(it) })
        return links
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("watchstreamhd.com") || 
            url.contains("videostreamingworld.com") || 
            url.contains("bestwish.lol") -> fetchFirePlayerContent(url, referer, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun fetchFirePlayerContent(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return
        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")

        try {
            // Replicate standard browser initialization headers for FirePlayer
            val initialHeaders = mapOf(
                "User-Agent" to CFState.userAgent,
                "Referer" to (referer ?: "https://familypornhd.com/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
            )

            // Step 1: Initialize session on the host iframe
            FamilyPornProvider.appGet(url, headers = initialHeaders)

            // Step 2: Target the FirePlayer backend router endpoint (standard for FireVideoPlayer scripts)
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to CFState.userAgent
            )

            val apiResponseText = FamilyPornProvider.appPost(
                url = postUrl,
                data = mapOf("hash" to videoid, "r" to (referer ?: "")),
                headers = postHeaders
            ).text

            // Step 3: Parse response safely handling schema variations
            val json = AppUtils.parseJson<FirePlayerResponse>(apiResponseText)
            val streamUrl = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl

            if (!streamUrl.isNullOrBlank()) {
                val isM3u8 = streamUrl.contains(".m3u8") || streamUrl.contains("m3u8")
                callback(
                    newExtractorLink(
                        source = "FirePlayer",
                        name = "FirePlayer",
                        url = streamUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.headers = mapOf(
                            "Origin" to "https://$host",
                            "Referer" to url,
                            "User-Agent" to CFState.userAgent
                        )
                    }
                )
            } else {
                // Fallback: Regex scan if JSON properties are obfuscated or empty
                val fallbackRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
                for (match in fallbackRegex.findAll(apiResponseText)) {
                    val link = match.groupValues[1]
                    if (link.contains("jquery") || link.contains("logo")) continue
                    val isM3u8 = link.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "FirePlayer",
                            name = "FirePlayer (Direct)",
                            url = link,
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.headers = mapOf("Origin" to "https://$host")
                        }
                    )
                    break
                }
            }

        } catch (e: Exception) {
            Log.e("FamilyPorn", "FirePlayer resolution failed: ${e.message}")
        }
    }

    data class FirePlayerResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null,
        @JsonProperty("video_source") val videoSource: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}
