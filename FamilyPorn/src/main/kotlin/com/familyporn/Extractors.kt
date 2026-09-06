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
            url.contains("bestwish.lol") -> fetchGenericIframe(url, referer, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun fetchGenericIframe(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val uri = android.net.Uri.parse(url)
        val host = uri.host ?: return
        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")

        try {
            val posturl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            
            // The API is returning raw HTML instead of JSON. 
            val responseText = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers).text
            
            // TIER 1: Unpack the HTML/JS and Regex the video link directly
            val unpacked = JsUnpacker(responseText).unpack() ?: responseText

            val patterns = listOf(
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources:\s*\[\s*\{\s*["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""data-stream-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(unpacked)
                if (match != null) {
                    val link = match.groupValues[1]
                    val isM3u8 = link.contains(".m3u8")
                    callback(newExtractorLink(
                        source = host, 
                        name = host, 
                        url = link, 
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.headers = mapOf("Origin" to "https://$host")
                    })
                    return
                }
            }

            // TIER 2: Fallback to JSON parsing just in case they revert the API behavior
            try {
                val json = AppUtils.parseJson<MasterResponse>(responseText)
                val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
                if (!link.isNullOrBlank()) {
                    val isM3u8 = link.contains(".m3u8")
                    callback(newExtractorLink(source = host, name = host, url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.headers = mapOf("Origin" to "https://$host")
                    })
                    return
                }
            } catch (e: Exception) {}

        } catch (e: Exception) {
            Log.e("FP_DEBUG", "Extractor crashed: ${e.message}")
        }
    }

    data class MasterResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null,
        @JsonProperty("video_source") val videoSource: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}
