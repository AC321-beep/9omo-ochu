package com.familyporn

import android.net.Uri
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import org.json.JSONObject
import org.jsoup.Jsoup

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

    // 4-Tier Extractor Engine
    private suspend fun fetchGenericIframe(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")
        
        // Fetch the raw HTML of the video player first
        val iframeHtml = try {
            FamilyPornProvider.appGet(url, headers = mapOf("Referer" to (referer ?: ""))).text
        } catch (e: Exception) { "" }

        // TIER 1: Videostr Engine (The new player backend they upgraded to)
        if (iframeHtml.isNotBlank()) {
            try {
                val document = Jsoup.parse(iframeHtml)
                val videoTag = document.selectFirst("[id$=\"-player\"]")
                val fileId = videoTag?.attr("data-id")
                
                if (!fileId.isNullOrEmpty()) {
                    val tripleRegex = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""", RegexOption.DOT_MATCHES_ALL)
                    val singleRegex = Regex("""\b[a-zA-Z0-9]{48}\b""")

                    val tripleMatch = tripleRegex.find(iframeHtml)
                    val singleMatch = singleRegex.find(iframeHtml)

                    val nonce = if (tripleMatch != null) {
                        tripleMatch.groupValues[1] + tripleMatch.groupValues[2] + tripleMatch.groupValues[3]
                    } else {
                        singleMatch?.value
                    }

                    if (nonce != null) {
                        val apiUrl = "https://$host/embed-1/v3/e-1/getSources?id=$fileId&_k=$nonce"
                        val jsonResponse = FamilyPornProvider.appGet(apiUrl, headers = mapOf(
                            "Accept" to "*/*",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url,
                            "User-Agent" to CFState.userAgent
                        )).text

                        val rootObj = JSONObject(jsonResponse)
                        val sourcesArray = rootObj.optJSONArray("sources")
                        if (sourcesArray != null && sourcesArray.length() > 0) {
                            val m3u8 = sourcesArray.optJSONObject(0)?.optString("file")
                            if (!m3u8.isNullOrBlank()) {
                                val isM3u8 = m3u8.contains(".m3u8")
                                callback(newExtractorLink(source = host, name = "$host (VStr)", url = m3u8, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                    this.referer = url
                                    this.headers = mapOf("Origin" to "https://$host")
                                })
                                return
                            }
                        }
                    }
                }
            } catch (e: Exception) {}
        }

        // TIER 2: Legacy Fireplayer API (with dynamic Javascript Hash extraction)
        try {
            val hashRegex = Regex("""var\s+hash\s*=\s*['"]([^'"]+)['"]""")
            val realHash = hashRegex.find(iframeHtml)?.groupValues?.get(1) ?: videoid

            val posturl = "https://$host/player/index.php?data=$realHash&do=getVideo"
            val headers = mapOf(
                "Accept" to "*/*",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
            )
            
            val responseText = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to realHash, "r" to (referer ?: "")), headers = headers).text
            val json = AppUtils.parseJson<MasterResponse>(responseText)
            val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
            
            if (!link.isNullOrBlank()) {
                val isM3u8 = link.contains(".m3u8")
                callback(newExtractorLink(source = host, name = "$host (Fire)", url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                    this.headers = mapOf("Origin" to "https://$host")
                })
                return
            }
        } catch (e: Exception) {}

        // TIER 3: BestWish GET API
        try {
            val getUrl = "https://$host/ajax/stream?filecode=$videoid"
            val responseText = FamilyPornProvider.appGet(url = getUrl, headers = mapOf("Referer" to url)).text
            val json = AppUtils.parseJson<MasterResponse>(responseText)
            val link = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
            
            if (!link.isNullOrBlank()) {
                val isM3u8 = link.contains(".m3u8")
                callback(newExtractorLink(source = host, name = "$host (BW)", url = link, type = if(isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = url
                })
                return
            }
        } catch (e: Exception) {}

        // TIER 4: JS Unpack & Regex HTML Scraping Fallback
        try {
            val unpacked = JsUnpacker(iframeHtml).unpack() ?: iframeHtml
            
            val patterns = listOf(
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file:\s*["'](https?://[^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources:\s*\[\s*\{\s*["']?file["']?\s*:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""source\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""data-stream-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""src=["']([^"']+)["'][^>]*type=["']video/mp4["']""", RegexOption.IGNORE_CASE)
            )

            for (pattern in patterns) {
                val match = pattern.find(unpacked)
                if (match != null) {
                    val link = match.groupValues[1]
                    val isM3u8 = link.contains(".m3u8")
                    callback(newExtractorLink(
                        source = "$host (Direct)", 
                        name = "$host (Raw)", 
                        url = link, 
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                    })
                    return
                }
            }
        } catch (e: Exception) {}
    }

    data class MasterResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null,
        @JsonProperty("video_source") val videoSource: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}
