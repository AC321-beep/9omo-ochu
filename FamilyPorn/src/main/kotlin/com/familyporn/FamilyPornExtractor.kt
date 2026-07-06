package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kotlinx.coroutines.delay

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d("FamilyPornExtractor", "⭐ getUrl called with URL: $url")
        when {
            url.contains("watchstreamhd.com") -> {
                Log.d("FamilyPornExtractor", "➡️ Using Fireplayer branch")
                fetchFireplayer(url, referer, callback)
            }
            url.contains("videostreamingworld.com") -> {
                Log.d("FamilyPornExtractor", "➡️ Using VideoStreamingWorld branch")
                fetchVideoStreamingWorld(url, referer, callback)
            }
            url.contains("bestwish.lol") -> {
                Log.d("FamilyPornExtractor", "➡️ Using BestWish branch")
                fetchBestWish(url, referer, callback)
            }
            else -> {
                Log.d("FamilyPornExtractor", "⚠️ Unknown hoster, falling back to generic loadExtractor")
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }

    private suspend fun fetchFireplayer(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val videoid = url.substringAfter("/video/").substringBefore("?")
        val posturl = "https://watchstreamhd.com/player/index.php?data=$videoid&do=getVideo"
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://watchstreamhd.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        var response = FamilyPorn.postText(
            url = posturl,
            data = mapOf("hash" to videoid, "r" to (referer ?: "")),
            headers = headers,
            referer = url
        )
        Log.d("Fireplayer", "POST response (first 200 chars): ${response.take(200)}")
        if (response.isBlank() || response == "[]" || response == "{}") {
            Log.w("Fireplayer", "Empty response, retrying after 2s")
            delay(2000)
            response = FamilyPorn.postText(
                url = posturl,
                data = mapOf("hash" to videoid, "r" to (referer ?: "")),
                headers = headers,
                referer = url
            )
        }
        val json = jacksonObjectMapper().readValue(response, FireResponse::class.java)
        val link = json.securedlink ?: json.videosource
        if (link != null) {
            Log.d("Fireplayer", "✅ Video link: $link")
            callback(newExtractorLink(
                source = "Fireplayer",
                name = "Fireplayer",
                url = link,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://watchstreamhd.com/"
                this.headers = mapOf("Origin" to "https://watchstreamhd.com")
            })
        } else {
            Log.e("Fireplayer", "No video link in response")
        }
    }

    private suspend fun fetchVideoStreamingWorld(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.substringAfterLast("/")
        val posturl = "https://videostreamingworld.com/player/index.php?data=$data&do=getVideo"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/120.0.0.0",
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://videostreamingworld.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        var response = FamilyPorn.postText(
            url = posturl,
            data = emptyMap(),
            headers = headers,
            referer = "https://videostreamingworld.com/"
        )
        Log.d("VideoStreamingWorld", "POST response (first 200 chars): ${response.take(200)}")
        if (response.isBlank() || response == "[]" || response == "{}") {
            delay(2000)
            response = FamilyPorn.postText(
                url = posturl,
                data = emptyMap(),
                headers = headers,
                referer = "https://videostreamingworld.com/"
            )
        }
        val video = jacksonObjectMapper().readValue(response, Video::class.java)
        callback(newExtractorLink(
            source = "VideoStreamingWorld",
            name = "VideoStreamingWorld",
            url = video.videoSource,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://videostreamingworld.com/"
        })
    }

    private suspend fun fetchBestWish(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.substringAfterLast("/")
        val getUrl = "https://bestwish.lol/ajax/stream?filecode=$data"
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36 OPR/120.0.0.0",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Referer" to url
        )
        var response = FamilyPorn.getText(url = getUrl, headers = headers, referer = url)
        Log.d("BestWish", "GET response (first 200 chars): ${response.take(200)}")
        if (response.isBlank() || response == "[]" || response == "{}") {
            delay(2000)
            response = FamilyPorn.getText(url = getUrl, headers = headers, referer = url)
        }
        val stream = jacksonObjectMapper().readValue(response, Stream::class.java)
        callback(newExtractorLink(
            source = "BestWish",
            name = "BestWish",
            url = stream.streaming_url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = "https://bestwish.lol/"
        })
    }

    data class FireResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null
    )
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Video(val videoSource: String)
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Stream(val streaming_url: String)
}
