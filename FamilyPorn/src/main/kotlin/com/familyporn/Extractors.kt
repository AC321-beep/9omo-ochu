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
            // 1. Hit the player container page
            val iframeHtml = FamilyPornProvider.appGet(url, headers = mapOf(
                "Referer" to (referer ?: ""),
                "User-Agent" to CFState.userAgent
            )).text

            // 2. Fetch the player's core script asset shown in your logs
            val scriptUrl = "https://$host/player/assets/scripts.php?v=6"
            val scriptText = FamilyPornProvider.appGet(scriptUrl, headers = mapOf(
                "Referer" to url,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to CFState.userAgent
            )).text

            // Combine both texts to search for embedded streams or configuration keys
            val combinedCode = iframeHtml + "\n" + scriptText

            // 3. Search for direct .m3u8 or .mp4 links
            val rawLinkRegex = Regex("""(https?://[^"'\s]+(?:\.m3u8|\.mp4)[^"'\s]*)""", RegexOption.IGNORE_CASE)
            var found = false
            for (match in rawLinkRegex.findAll(combinedCode)) {
                val link = match.groupValues[1]
                if (link.contains("jquery") || link.contains("bootstrap") || link.contains("font-awesome")) continue
                
                found = true
                val isM3u8 = link.contains(".m3u8")
                callback(newExtractorLink(
                    source = host, 
                    name = "$host (Stream)", 
                    url = link, 
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.headers = mapOf("Origin" to "https://$host")
                })
            }

            if (found) return

            // 4. Fallback to the AJAX backend endpoint
            val posturl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            
            val apiResponse = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers).text
            val json = AppUtils.parseJson<MasterResponse>(apiResponse)
            val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
            
            if (!link.isNullOrBlank()) {
                val isM3u8 = link.contains(".m3u8")
                callback(newExtractorLink(source = host, name = "$host (API)", url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.headers = mapOf("Origin" to "https://$host")
                })
            }

        } catch (e: Exception) {
            Log.e("FamilyPorn", "Extraction failed: ${e.message}")
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
