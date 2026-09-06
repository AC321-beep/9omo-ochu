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
            // 1. Fetch the host iframe page
            val iframeHtml = try {
                FamilyPornProvider.appGet(url, headers = mapOf(
                    "Referer" to (referer ?: "https://familypornhd.com/"),
                    "User-Agent" to CFState.userAgent
                )).text
            } catch (e: Exception) { "" }

            // 2. Query the FirePlayer backend API router (`do=getVideo`)
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to CFState.userAgent
            )

            val apiResponseText = try {
                FamilyPornProvider.appPost(
                    url = postUrl,
                    data = mapOf("hash" to videoid, "r" to (referer ?: "")),
                    headers = postHeaders
                ).text
            } catch (e: Exception) { "" }

            val combinedSource = iframeHtml + "\n" + apiResponseText

            // 3. Safe Regex extraction (avoids JSON parsing crashes entirely)
            val linkRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""", RegexOption.IGNORE_CASE)
            var found = false

            for (match in linkRegex.findAll(combinedSource)) {
                val link = match.groupValues[1]
                if (link.contains("jquery") || link.contains("bootstrap") || link.contains("loading")) continue

                found = true
                val isM3u8 = link.contains(".m3u8")
                callback(
                    newExtractorLink(
                        source = "FirePlayer",
                        name = "FirePlayer",
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
            }

            if (found) return

            // 4. Only attempt JSON parsing if the response does NOT look like HTML
            if (!apiResponseText.trim().startsWith("<")) {
                val json = AppUtils.parseJson<MasterResponse>(apiResponseText)
                val streamUrl = json.securedlink ?: json.videosource ?: json.file ?: json.videoSource ?: json.streamingUrl

                if (!streamUrl.isNullOrBlank()) {
                    val isM3u8 = streamUrl.contains(".m3u8")
                    callback(
                        newExtractorLink(
                            source = "FirePlayer",
                            name = "FirePlayer (API)",
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
                }
            } else {
                Log.e("FamilyPorn", "Server returned HTML instead of a stream link. Response preview: ${apiResponseText.take(150)}")
            }

        } catch (e: Exception) {
            Log.e("FamilyPorn", "FirePlayer resolution error: ${e.message}")
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
