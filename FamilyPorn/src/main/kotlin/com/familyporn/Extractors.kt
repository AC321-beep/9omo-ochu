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
            // STEP 1: Fetch the raw HTML of the iframe player
            val htmlResponse = FamilyPornProvider.appGet(url, headers = mapOf(
                "Referer" to (referer ?: ""),
                "User-Agent" to CFState.userAgent
            )).text
            
            var analyzedText = htmlResponse
            
            // STEP 2: Defeat the Decoy Trap! Find and unpack EVERY encrypted script on the page
            var tempHtml = htmlResponse
            while(tempHtml.contains("eval(function(")) {
                val unpacked = JsUnpacker(tempHtml).unpack()
                if (!unpacked.isNullOrBlank() && unpacked != tempHtml) {
                    analyzedText += "\n" + unpacked
                    
                    // Defeat Double-Encryption
                    if (unpacked.contains("eval(function(")) {
                        val doubleUnpacked = JsUnpacker(unpacked).unpack()
                        if (!doubleUnpacked.isNullOrBlank()) analyzedText += "\n" + doubleUnpacked
                    }
                    // Mark this block as solved so the loop moves to the next encrypted script
                    tempHtml = tempHtml.replaceFirst("eval(function(", "solved_eval(")
                } else {
                    // Prevent infinite loop if an unpack fails
                    tempHtml = tempHtml.replaceFirst("eval(function(", "failed_eval(")
                }
            }

            var foundLink = false

            // STEP 3: Aggressive Regex targeting for direct .m3u8 or .mp4 links in the decrypted code
            val rawLinkRegex = Regex("""(https?://[^"'\s]+(?:\.m3u8|\.mp4)[^"'\s]*)""", RegexOption.IGNORE_CASE)
            for (match in rawLinkRegex.findAll(analyzedText)) {
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

            // STEP 4: Look for Base64 encoded links: atob("aHR0cHM...")
            val b64Regex = Regex("""atob\(['"]([^'"]+)['"]\)""")
            for (match in b64Regex.findAll(analyzedText)) {
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

            // STEP 5: Fallback to the Legacy POST API just in case they revert their player logic
            val posturl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            
            try {
                val apiResponseText = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers).text
                val json = AppUtils.parseJson<MasterResponse>(apiResponseText)
                val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
                if (!link.isNullOrBlank()) {
                    val isM3u8 = link.contains(".m3u8")
                    callback(newExtractorLink(source = host, name = "$host (API)", url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.referer = url
                        this.headers = mapOf("Origin" to "https://$host")
                    })
                }
            } catch (e: Exception) {}

        } catch (e: Exception) {
            Log.e("FamilyPorn", "Extractor crashed: ${e.message}")
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
