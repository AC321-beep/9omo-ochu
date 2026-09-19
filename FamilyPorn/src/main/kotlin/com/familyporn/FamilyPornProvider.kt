package com.familyporn

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    companion object {
        const val TAG = "FamilyPorn"
        private const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun ensureUa(): String {
        if (CFState.userAgent.isBlank()) {
            val seeded = try {
                WebSettings.getDefaultUserAgent(CommonActivity.activity)
            } catch (e: Exception) { FALLBACK_UA }
            CFState.userAgent = seeded.ifBlank { FALLBACK_UA }
            Log.e(TAG, "UA seeded -> [${CFState.userAgent}]")
        }
        return CFState.userAgent
    }

    /**
     * Minimal header set for KVS/FirePlayer stream URLs.
     *
     * Deliberately NOT sent:
     *   - Origin            : KVS treats presence as a CORS preflight hint and
     *                         serves the anti-hotlink HTML page instead of video
     *   - Sec-Fetch-Dest/Mode/Site : same problem, plus a direct media GET is
     *                         not a browser-initiated navigation so these are
     *                         meaningless
     *   - Accept-Language   : not needed for a byte-range GET
     */
    private fun streamHeaders(streamUrl: String, embedUrl: String): Map<String, String> {
        val ua = ensureUa()
        val cookie = try {
            CookieManager.getInstance().getCookie(streamUrl)
                ?: CookieManager.getInstance().getCookie(embedUrl)
                ?: ""
        } catch (e: Exception) { "" }

        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Referer" to embedUrl,
            "Accept" to "*/*"
        )
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        Log.e(TAG, "streamHeaders: url=[$streamUrl] ua=[$ua] cookieLen=${cookie.length}")
        return headers
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        Log.e(TAG, "EX getUrl(list) url=[$url] referer=[$referer]")
        val links = mutableListOf<ExtractorLink>()
        getUrl(url, referer, subtitleCallback = {}, callback = { links.add(it) })
        Log.e(TAG, "EX getUrl(list) -> ${links.size} links")
        return links
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "EX getUrl() url=[$url] referer=[$referer]")
        if (url.isBlank()) {
            Log.e(TAG, "EX getUrl() ABORT: blank url")
            return
        }
        ensureUa()

        val host = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        val isOwnDomain = host == "familypornhd.com" || host.endsWith(".familypornhd.com")
        Log.e(TAG, "EX getUrl() host=[$host] isOwnDomain=$isOwnDomain")

        val isFirePlayerHost =
            isOwnDomain ||
            url.contains("watchstreamhd.com") ||
            url.contains("videostreamingworld.com") ||
            url.contains("bestwish.lol")

        if (isFirePlayerHost) {
            Log.e(TAG, "EX getUrl() -> fetchFirePlayerContent")
            try {
                fetchFirePlayerContent(url, referer, callback)
            } catch (e: Exception) {
                Log.e(TAG, "EX getUrl() fetchFirePlayerContent threw", e)
            }
        } else {
            Log.e(TAG, "EX getUrl() -> loadExtractor (external host)")
            try {
                loadExtractor(url, referer, subtitleCallback, callback)
            } catch (e: Exception) {
                Log.e(TAG, "EX getUrl() loadExtractor threw", e)
            }
        }
    }

    private suspend fun fetchFirePlayerContent(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "FP START url=[$url] referer=[$referer]")

        val uri = Uri.parse(url)
        val host = uri.host ?: run {
            Log.e(TAG, "FP ABORT: null host for [$url]")
            return
        }
        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")
        Log.e(TAG, "FP host=[$host] videoid=[$videoid]")

        try {
            val playerHeaders = mapOf(
                "User-Agent" to ensureUa(),
                "Referer" to (referer ?: "https://familypornhd.com/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            Log.e(TAG, "FP step1 GET [$url]")
            val iframeResponse = FamilyPornProvider.appGet(url, headers = playerHeaders)
            val iframeHtml = if (iframeResponse.isSuccessful) iframeResponse.text else ""
            Log.e(TAG, "FP step1 code=${iframeResponse.code} htmlLen=${iframeHtml.length}")

            // -- FirePlayer do=getVideo (only works on watchstream/bestwish/vsw)
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to ensureUa()
            )
            val postBody = mapOf("hash" to videoid, "r" to (referer ?: ""))
            Log.e(TAG, "FP step2 POST [$postUrl]")

            val apiResponse = FamilyPornProvider.appPost(
                url = postUrl,
                data = postBody,
                headers = postHeaders
            )
            val responseText = if (apiResponse.isSuccessful) apiResponse.text else ""
            Log.e(TAG, "FP step2 code=${apiResponse.code} bodyLen=${responseText.length}")
            Log.e(TAG, "FP step2 preview=${responseText.take(400).replace("\n", " ")}")

            if (apiResponse.code == 200 &&
                responseText.isNotBlank() &&
                !responseText.trim().startsWith("<")
            ) {
                try {
                    val json = AppUtils.parseJson<MasterResponse>(responseText)
                    val streamUrl = json.securedlink
                        ?: json.videosource
                        ?: json.file
                        ?: json.videoSource
                        ?: json.streamingUrl
                    Log.e(TAG, "FP step3 chosen=[$streamUrl]")
                    if (!streamUrl.isNullOrBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8")
                        callback(
                            newExtractorLink(
                                source = "FirePlayer",
                                name = "FirePlayer",
                                url = streamUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.headers = streamHeaders(streamUrl, url)
                            }
                        )
                        Log.e(TAG, "FP step3 emitted")
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FP step3 JSON parse failed: ${e.message}", e)
                }
            } else {
                Log.e(TAG, "FP step2 not usable (code=${apiResponse.code}); falling back to scrape")
            }

            // -- KVS scrape. Order matters: prefer the "&embed=true" variants,
            //    they are the ones the KVS iframe player actually uses.
            Log.e(TAG, "FP step4 regex fallback")
            val linkRegex = Regex(
                """["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
            val rawMatches = linkRegex.findAll(iframeHtml)
                .map { it.groupValues[1] }
                .filterNot { it.contains("preview", true) }
                .filterNot { it.contains("screenshot", true) }
                .filterNot { it.contains("jquery") }
                .filterNot { it.contains("bootstrap") }
                .distinct()
                .toList()

            // embed=true first, then the rest
            val ordered = rawMatches.sortedByDescending { it.contains("&embed=true") }
            Log.e(TAG, "FP step4 usable matches (${ordered.size}): $ordered")

            var idx = 0
            for (link in ordered) {
                val isM3u8 = link.contains(".m3u8")
                idx++
                Log.e(TAG, "FP step4 emitting #$idx isM3u8=$isM3u8 [$link]")
                callback(
                    newExtractorLink(
                        source = "FirePlayer",
                        name = "FirePlayer (Direct #$idx)",
                        url = link,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.headers = streamHeaders(link, url)
                    }
                )
            }
            if (idx > 0) return

            Log.e(TAG, "FP FAILURE: no stream URL for host=[$host] videoid=[$videoid]")
            Log.e(TAG, "FP iframeHtml (2000): ${iframeHtml.take(2000)}")

        } catch (e: Exception) {
            Log.e(TAG, "FP error: ${e.message}", e)
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
