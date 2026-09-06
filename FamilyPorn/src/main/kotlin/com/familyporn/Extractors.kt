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
            // 1. Fetch the FirePlayer container page with proper headers
            val iframeHtml = FamilyPornProvider.appGet(url, headers = mapOf(
                "Referer" to (referer ?: ""),
                "User-Agent" to CFState.userAgent
            )).text

            // 2. FirePlayer loads its configuration via scripts.php and internal ajax endpoints.
            // We check the scripts asset or unpack any embedded packers directly.
            val scriptUrl = "https://$host/player/assets/scripts.php?v=6"
            val scriptText = try {
                FamilyPornProvider.appGet(scriptUrl, headers = mapOf(
                    "Referer" to url,
                    "X-Requested-With" to "XMLHttpRequest",
                    "User-Agent" to CFState.userAgent
                )).text
            } catch (e: Exception) { "" }

            val combinedSource = iframeHtml + "\n" + scriptText
            val unpacked = JsUnpacker(combinedSource).unpack() ?: combinedSource

            var linkFound = false

            // 3. Extract any streaming file declaration inside the JS scope
            val patterns = listOf(
                Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources\s*:\s*\[\s*\{\s*["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""(?:src|url)\s*[:=]\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            ]

            for (pattern in patterns) {
                for (match in pattern.findAll(unpacked)) {
                    val link = match.groupValues[1]
                    if (link.contains("jquery") || link.contains("bootstrap")) continue
                    
                    linkFound = true
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
                }
            }

            if (linkFound) return

            // 4. Fallback: Query the backend player endpoint using standard post parameters
            val posturl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to CFState.userAgent
            )
            
            val apiResponse = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = postHeaders).text
            val json = AppUtils.parseJson<MasterResponse>(apiResponse)
            val apiLink = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
            
            if (!apiLink.isNullOrBlank()) {
                val isM3u8 = apiLink.contains(".m3u8")
                callback(newExtractorLink(
                    source = host,
                    name = "$host (API)",
                    url = apiLink,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.headers = mapOf("Origin" to "https://$host")
                })
            }

        } catch (e: Exception) {
            Log.e("FamilyPorn", "Extractor error: ${e.message}")
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
