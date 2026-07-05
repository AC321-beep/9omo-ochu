package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com" // not used directly
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Priority: Fireplayer first, then others
        when {
            url.contains("watchstreamhd.com") -> fetchFireplayer(url, referer, callback)
            url.contains("videostreamingworld.com") -> fetchVideoStreamingWorld(url, referer, callback)
            url.contains("bestwish.lol") -> fetchBestWish(url, referer, callback)
            else -> {
                Log.d("FamilyPornExtractor", "Unknown hoster: $url")
                // Fallback: try generic loadExtractor with the URL
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }

    // ----- Fireplayer logic -----
    private suspend fun fetchFireplayer(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoid = url.substringAfter("/video/").substringBefore("?")
        Log.d("Fireplayer", "Video ID: $videoid")

        val posturl = "https://watchstreamhd.com/player/index.php?data=$videoid&do=getVideo"
        val postdata = mapOf("hash" to videoid, "r" to (referer ?: ""))
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://watchstreamhd.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )

        val response = FamilyPorn.postText(
            url = posturl,
            data = postdata,
            headers = headers,
            referer = url
        )

        val mapper = jacksonObjectMapper()
        val json = mapper.readValue(response, FireResponse::class.java)
        val videolink = json.securedlink ?: json.videosource

        if (videolink != null) {
            callback(
                newExtractorLink(
                    source = "Fireplayer",
                    name = "Fireplayer",
                    url = videolink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://watchstreamhd.com/"
                    this.headers = mapOf("Origin" to "https://watchstreamhd.com")
                }
            )
        }
    }

    // ----- VideoStreamingWorld logic -----
    private suspend fun fetchVideoStreamingWorld(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val data = url.substringAfterLast("/")
        val posturl = "https://videostreamingworld.com/player/index.php?data=$data&do=getVideo"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/120.0.0.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
            "X-Requested-With" to "XMLHttpRequest",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-ch-ua" to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Opera\";v=\"120\"",
            "sec-ch-ua-mobile" to "?0"
        )

        val response = FamilyPorn.postText(
            url = posturl,
            data = emptyMap(),
            headers = headers,
            referer = "https://videostreamingworld.com/"
        )

        val mapper = jacksonObjectMapper()
        val video: Video = mapper.readValue(response)
        val videoUrl = video.videoSource

        Log.d("VideoStreamingWorld", "videoUrl = $videoUrl")

        callback(
            newExtractorLink(
                source = "VideoStreamingWorld",
                name = "VideoStreamingWorld",
                url = videoUrl,
                type = ExtractorLinkType.M3U8,
                initializer = { this.referer = "https://videostreamingworld.com/" }
            )
        )
    }

    // ----- BestWish logic -----
    private suspend fun fetchBestWish(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val data = url.substringAfterLast("/")
        val getUrl = "https://bestwish.lol/ajax/stream?filecode=$data"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/120.0.0.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "same-origin",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-ch-ua" to "\"Not/A)Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Opera\";v=\"120\"",
            "sec-ch-ua-mobile" to "?0"
        )

        val response = FamilyPorn.getText(
            url = getUrl,
            headers = headers,
            referer = url
        )

        val mapper = jacksonObjectMapper()
        val stream: Stream = mapper.readValue(response)
        val videoUrl = stream.streaming_url

        Log.d("BestWish", "videoUrl = $videoUrl")

        callback(
            newExtractorLink(
                source = "BestWish",
                name = "BestWish",
                url = videoUrl,
                type = ExtractorLinkType.M3U8,
                initializer = { this.referer = "https://bestwish.lol/" }
            )
        )
    }

    // Data classes (same as before)
    data class FireResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(val videoSource: String)

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(val streaming_url: String)
}
