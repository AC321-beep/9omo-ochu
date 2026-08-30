package com.familyporn

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
            url.contains("watchstreamhd.com") -> fetchFireplayer(url, referer, callback)
            url.contains("videostreamingworld.com") -> fetchVideoStreamingWorld(url, referer, callback)
            url.contains("bestwish.lol") -> fetchBestWish(url, referer, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
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
        
        // Uses the centralized safe API call
        val response = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers).text
        val json = AppUtils.parseJson<FireResponse>(response)
        val link = json.securedlink ?: json.videosource
        if (link != null) {
            callback(newExtractorLink(source = "Fireplayer", name = "Fireplayer", url = link, type = ExtractorLinkType.M3U8) {
                this.referer = "https://watchstreamhd.com/"
                this.headers = mapOf("Origin" to "https://watchstreamhd.com")
            })
        }
    }

    private suspend fun fetchVideoStreamingWorld(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.substringAfterLast("/")
        val posturl = "https://videostreamingworld.com/player/index.php?data=$data&do=getVideo"
        
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://videostreamingworld.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        val response = FamilyPornProvider.appPost(url = posturl, data = emptyMap(), headers = headers).text
        val video = AppUtils.parseJson<Video>(response)
        callback(newExtractorLink(source = "VideoStreamingWorld", name = "VideoStreamingWorld", url = video.videoSource, type = ExtractorLinkType.M3U8) {
            this.referer = "https://videostreamingworld.com/"
        })
    }

    private suspend fun fetchBestWish(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.substringAfterLast("/")
        val getUrl = "https://bestwish.lol/ajax/stream?filecode=$data"
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Referer" to url
        )
        
        val response = FamilyPornProvider.appGet(url = getUrl, headers = headers).text
        val stream = AppUtils.parseJson<Stream>(response)
        callback(newExtractorLink(source = "BestWish", name = "BestWish", url = stream.streaming_url, type = ExtractorLinkType.M3U8) {
            this.referer = "https://bestwish.lol/"
        })
    }

    data class FireResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null
    )
    data class Video(val videoSource: String)
    data class Stream(val streaming_url: String)
}
