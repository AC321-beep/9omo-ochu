package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    companion object {
        const val TAG = "FamilyPorn"
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        Log.e(TAG, "EX getUrl(list) url=[$url] referer=[$referer]")
        val links = mutableListOf<ExtractorLink>()
        getUrl(url, referer, subtitleCallback = {}, callback = { links.add(it) })
        Log.e(TAG, "EX getUrl(list) returning ${links.size} links")
        return links
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.e(TAG, "EX getUrl() url=[$url]")
        Log.e(TAG, "EX getUrl() referer=[$referer]")
        Log.e(TAG, "EX getUrl() url isBlank=${url.isBlank()} url length=${url.length}")

        try {
            when {
                url.contains("watchstreamhd.com") -> {
                    Log.e(TAG, "EX getUrl() matched watchstreamhd.com")
                    fetchFirePlayerContent(url, referer, callback)
                }
                url.contains("videostreamingworld.com") -> {
                    Log.e(TAG, "EX getUrl() matched videostreamingworld.com")
                    fetchFirePlayerContent(url, referer, callback)
                }
                url.contains("bestwish.lol") -> {
                    Log.e(TAG, "EX getUrl() matched bestwish.lol")
                    fetchFirePlayerContent(url, referer, callback)
                }
                else -> {
                    Log.e(TAG, "EX getUrl() no FirePlayer host match, routing to loadExtractor for [$url]")
                    loadExtractor(url, referer, subtitleCallback, callback)
                    Log.e(TAG, "EX getUrl() loadExtractor returned")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "EX getUrl() unhandled exception", e)
        }
    }

    private suspend fun fetchFirePlayerContent(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        Log.e(TAG, "FP fetchFirePlayerContent() START url=[$url] referer=[$referer]")

        val uri = android.net.Uri.parse(url)
        val host = uri.host
        if (host == null) {
            Log.e(TAG, "FP ABORT: uri.host is null for url=[$url]")
            return
        }

        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")
        Log.e(TAG, "FP host=[$host] videoid=[$videoid]")
        Log.e(TAG, "FP uri.scheme=[${uri.scheme}] uri.path=[${uri.path}] uri.query=[${uri.query}]")

        if (videoid.isBlank()) {
            Log.e(TAG, "FP WARNING: videoid is blank — substringAfterLast('/') yielded empty")
        }

        try {
            // 1. Establish session context by hitting the player frame wrapper first
            val playerHeaders = mapOf(
                "User-Agent" to CFState.userAgent,
                "Referer" to (referer ?: "https://familypornhd.com/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            Log.e(TAG, "FP step1 GET player frame: url=[$url] headers=$playerHeaders")

            val iframeResponse = FamilyPornProvider.appGet(url, headers = playerHeaders)
            Log.e(TAG, "FP step1 response code=${iframeResponse.code} url=${iframeResponse.url}")
            val iframeHtml = if (iframeResponse.isSuccessful) iframeResponse.text else ""
            Log.e(TAG, "FP step1 iframeHtml length=${iframeHtml.length}")
            Log.e(TAG, "FP step1 iframeHtml preview=${iframeHtml.take(500).replace("\n", " ")}")

            // 2. Query FirePlayer's official stream router endpoint
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to CFState.userAgent
            )
            val postBody = mapOf("hash" to videoid, "r" to (referer ?: ""))
            Log.e(TAG, "FP step2 POST url=[$postUrl]")
            Log.e(TAG, "FP step2 POST body=$postBody")
            Log.e(TAG, "FP step2 POST headers=$postHeaders")

            val apiResponse = FamilyPornProvider.appPost(
                url = postUrl,
                data = postBody,
                headers = postHeaders
            )

            Log.e(TAG, "FP step2 response code=${apiResponse.code} url=${apiResponse.url}")
            val responseText = if (apiResponse.isSuccessful) apiResponse.text else ""
            Log.e(TAG, "FP step2 responseText length=${responseText.length}")
            Log.e(TAG, "FP step2 responseText preview=${responseText.take(500).replace("\n", " ")}")

            if (responseText.isBlank()) {
                Log.e(TAG, "FP step2 responseText is BLANK — API returned nothing")
            }

            // 3. Check if response is valid JSON or needs regex scraping
            if (responseText.isNotBlank() && !responseText.trim().startsWith("<")) {
                Log.e(TAG, "FP step3 attempting JSON parse")
                try {
                    val json = AppUtils.parseJson<MasterResponse>(responseText)
                    Log.e(TAG, "FP step3 parsed json:")
                    Log.e(TAG, "FP step3   securedLink=[${json.securedlink}]")
                    Log.e(TAG, "FP step3   videoSource=[${json.videosource}]")
                    Log.e(TAG, "FP step3   video_source=[${json.videoSource}]")
                    Log.e(TAG, "FP step3   file=[${json.file}]")
                    Log.e(TAG, "FP step3   streaming_url=[${json.streamingUrl}]")

                    val streamUrl = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl
                    Log.e(TAG, "FP step3 chosen streamUrl=[$streamUrl]")

                    if (!streamUrl.isNullOrBlank()) {
                        val isM3u8 = streamUrl.contains(".m3u8")
                        Log.e(TAG, "FP step3 emitting ExtractorLink isM3u8=$isM3u8 url=[$streamUrl]")
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
                        Log.e(TAG, "FP step3 callback emitted, returning")
                        return
                    } else {
                        Log.e(TAG, "FP step3 all JSON fields were null/blank, falling through to regex")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "FP step3 JSON parse FAILED: ${e.message}", e)
                    Log.e(TAG, "FP step3 offending body=[$responseText]")
                }
            } else if (responseText.isNotBlank()) {
                Log.e(TAG, "FP step3 response looks like HTML (starts with '<'), skipping JSON parse")
            }

            // 4. Fallback: Scan both iframe HTML and response text for any stray stream links
            Log.e(TAG, "FP step4 fallback regex scan")
            val combinedSource = iframeHtml + "\n" + responseText
            Log.e(TAG, "FP step4 combinedSource length=${combinedSource.length}")
            val linkRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)

            val allMatches = linkRegex.findAll(combinedSource).map { it.groupValues[1] }.toList()
            Log.e(TAG, "FP step4 raw regex matches (${allMatches.size}): $allMatches")

            var emitted = false
            for (match in allMatches) {
                val link = match
                if (link.contains("jquery") || link.contains("bootstrap") || link.contains("loading")) {
                    Log.e(TAG, "FP step4 skipping excluded match: [$link]")
                    continue
                }

                val isM3u8 = link.contains(".m3u8")
                Log.e(TAG, "FP step4 emitting fallback ExtractorLink isM3u8=$isM3u8 url=[$link]")
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
                emitted = true
                break
            }

            if (!emitted) {
                Log.e(TAG, "FP ABORT: no stream URL found via JSON or regex. host=[$host] videoid=[$videoid]")
                Log.e(TAG, "FP iframeHtml FULL (first 2000): ${iframeHtml.take(2000)}")
                Log.e(TAG, "FP responseText FULL (first 2000): ${responseText.take(2000)}")
            }

        } catch (e: Exception) {
            Log.e(TAG, "FP FirePlayer resolution error: ${e.message}", e)
        }
        Log.e(TAG, "FP fetchFirePlayerContent() END")
    }

    data class MasterResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null,
        @JsonProperty("video_source") val videoSource: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}
