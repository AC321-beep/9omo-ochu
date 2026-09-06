package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import android.util.Base64

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
            
            val responseText = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers).text
            
            // TIER 1: Unpack and aggressively Regex
            var unpacked = JsUnpacker(responseText).unpack() ?: responseText

            // Sometimes the script is double-packed. Unpack it again if necessary.
            if (unpacked.contains("eval(function(p,a,c,k,e,d)")) {
                unpacked = JsUnpacker(unpacked).unpack() ?: unpacked
            }

            var foundLink = false

            // Look for ANY m3u8 or mp4 link in the decrypted source
            val rawLinkRegex = Regex("""(https?://[^"'\s]+(?:\.m3u8|\.mp4)[^"'\s]*)""", RegexOption.IGNORE_CASE)
            for (match in rawLinkRegex.findAll(unpacked)) {
                val link = match.groupValues[1]
                val isM3u8 = link.contains(".m3u8")
                foundLink = true
                callback(newExtractorLink(
                    source = host, 
                    name = "$host (Direct)", 
                    url = link, 
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.headers = mapOf("Origin" to "https://$host")
                })
            }

            // Look for Base64 encoded links: atob("aHR0cHM...")
            val b64Regex = Regex("""atob\(['"]([^'"]+)['"]\)""")
            for (match in b64Regex.findAll(unpacked)) {
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT))
                    if (decoded.contains(".m3u8") || decoded.contains(".mp4")) {
                        foundLink = true
                        val isM3u8 = decoded.contains(".m3u8")
                        callback(newExtractorLink(
                            source = host, 
                            name = "$host (Decoded)", 
                            url = decoded, 
                            type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.headers = mapOf("Origin" to "https://$host")
                        })
                    }
                } catch (e: Exception) {}
            }

            if (foundLink) return

            // TIER 2: Legacy JSON parsing (in case they revert their API)
            try {
                val json = AppUtils.parseJson<MasterResponse>(responseText)
                val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
                if (!link.isNullOrBlank()) {
                    val isM3u8 = link.contains(".m3u8")
                    callback(newExtractorLink(source = host, name = "$host (JSON)", url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.headers = mapOf("Origin" to "https://$host")
                    })
                    return
                }
            } catch (e: Exception) {
                // Silently swallow JSON errors because we already know it's returning HTML
            }

            // TIER 3: If absolutely NO links were found, print the unpacked HTML as an Error so it shows up in Logcat!
            Log.e("FamilyPorn", "NO VIDEO LINKS FOUND! Here is the decrypted site code:")
            Log.e("FamilyPorn", unpacked.take(3500)) // Prints the first 3500 characters of the site's code

        } catch (e: Exception) {
            Log.e("FamilyPorn", "Extractor crashed completely: ${e.message}")
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
