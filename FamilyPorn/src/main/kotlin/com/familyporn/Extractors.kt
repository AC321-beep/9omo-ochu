package com.familyporn

import android.net.Uri
import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    companion object { const val TAG = "FamilyPorn" }

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
        try {
            when {
                url.contains("watchstreamhd.com") ||
                url.contains("videostreamingworld.com") ||
                url.contains("bestwish.lol") -> {
                    Log.e(TAG, "EX getUrl() -> fetchFirePlayerContent")
                    fetchFirePlayerContent(url, referer, callback)
                }
                else -> {
                    Log.e(TAG, "EX getUrl() -> loadExtractor fallback")
                    loadExtractor(url, referer, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "EX getUrl() unhandled", e)
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
            // 1. Prime session (sets cookies on the FirePlayer host).
            val playerHeaders = mapOf(
                "User-Agent" to CFState.userAgent,
                "Referer" to (referer ?: "https://familypornhd.com/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            Log.e(TAG, "FP step1 GET [$url]")
            val iframeResponse = FamilyPornProvider.appGet(url, headers = playerHeaders)
            val iframeHtml = if (iframeResponse.isSuccessful) iframeResponse.text else ""
            Log.e(TAG, "FP step1 code=${iframeResponse.code} htmlLen=${iframeHtml.length}")

            // 2. Query FirePlayer's do=getVideo endpoint.
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest", // <-- must survive interceptor
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to CFState.userAgent
            )
            val postBody = mapOf("hash" to videoid, "r" to (referer ?: ""))
            Log.e(TAG, "FP step2 POST [$postUrl] body=$postBody")

            val apiResponse = FamilyPornProvider.appPost(
                url = postUrl,
                data = postBody,
                headers = postHeaders
            )
            val responseText = if (apiResponse.isSuccessful) apiResponse.text else ""
            Log.e(TAG, "FP step2 code=${apiResponse.code} bodyLen=${responseText.length}")
            Log.e(TAG, "FP step2 preview=${responseText.take(500).replace("\n", " ")}")

            // 3. Try JSON first.
            if (responseText.isNotBlank() && !responseText.trim().startsWith("<")) {
                try {
                    val json = AppUtils.parseJson<MasterResponse>(responseText)
                    Log.e(TAG, "FP step3 securedLink=[${json.securedlink}]")
                    Log.e(TAG, "FP step3 videoSource=[${json.videosource}]")
                    Log.e(TAG, "FP step3 video_source=[${json.videoSource}]")
                    Log.e(TAG, "FP step3 file=[${json.file}]")
                    Log.e(TAG, "FP step3 streaming_url=[${json.streamingUrl}]")

                    val streamUrl = json.securedlink
                        ?: json.videosource
                        ?: json.file
                        ?: json.videoSource
                        ?: json.streamingUrl
                    Log.e(TAG, "FP step3 chosen=[$streamUrl]")

                    if (!streamUrl.isNullOrBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8")
                        Log.e(TAG, "FP step3 emitting isM3u8=$isM3u8")
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
                        return
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FP step3 JSON parse failed: ${e.message}", e)
                }
            } else if (responseText.isNotBlank()) {
                Log.e(TAG, "FP step3 response is HTML, skipping JSON")
            }

            // 4. Fallback: regex scan.
            Log.e(TAG, "FP step4 regex fallback")
            val combined = iframeHtml + "\n" + responseText
            val linkRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            val matches = linkRegex.findAll(combined).map { it.groupValues[1] }.toList()
            Log.e(TAG, "FP step4 matches (${matches.size}): $matches")

            for (link in matches) {
                if (link.contains("jquery") || link.contains("bootstrap") || link.contains("loading")) continue
                val isM3u8 = link.contains(".m3u8")
                Log.e(TAG, "FP step4 emitting fallback isM3u8=$isM3u8 [$link]")
                callback(
                    newExtractorLink(
                        source = "FirePlayer",
                        name = "FirePlayer (Direct)",
                        url = link,
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
                return
            }

            Log.e(TAG, "FP FAILURE: no stream URL for host=[$host] videoid=[$videoid]")
            Log.e(TAG, "FP iframeHtml FULL: ${iframeHtml.take(2000)}")
            Log.e(TAG, "FP responseText FULL: ${responseText.take(2000)}")

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
