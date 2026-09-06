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
        Log.d("FP_DEBUG", "Extractor received URL: $url | Referer: $referer")
        when {
            url.contains("watchstreamhd.com") -> fetchFireplayer(url, referer, callback)
            url.contains("videostreamingworld.com") -> fetchVideoStreamingWorld(url, referer, callback)
            url.contains("bestwish.lol") -> fetchBestWish(url, referer, callback)
            else -> loadExtractor(url, referer, subtitleCallback, callback)
        }
    }

    private suspend fun fetchFireplayer(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val videoid = url.trimEnd('/').substringAfter("/video/").substringBefore("?")
        val posturl = "https://watchstreamhd.com/player/index.php?data=$videoid&do=getVideo"
        
        Log.d("FP_DEBUG", "Fireplayer Target ID: $videoid | POST URL: $posturl")

        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://watchstreamhd.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        try {
            val response = FamilyPornProvider.appPost(url = posturl, data = mapOf("hash" to videoid, "r" to (referer ?: "")), headers = headers)
            val responseText = response.text
            
            Log.d("FP_DEBUG", "Fireplayer API HTTP Code: ${response.code}")
            Log.d("FP_DEBUG", "Fireplayer API Response: $responseText")

            val json = AppUtils.parseJson<FireResponse>(responseText)
            val link = json.securedlink ?: json.videosource
            
            Log.d("FP_DEBUG", "Fireplayer Parsed Link: $link")

            if (link != null) {
                val isM3u8 = link.contains(".m3u8")
                callback(newExtractorLink(source = "Fireplayer", name = "Fireplayer", url = link, type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "https://watchstreamhd.com/"
                    this.headers = mapOf("Origin" to "https://watchstreamhd.com")
                })
            }
        } catch (e: Exception) {
            Log.e("FP_DEBUG", "Fireplayer Extractor Crashed: ${e.message}")
        }
    }

    private suspend fun fetchVideoStreamingWorld(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.trimEnd('/').substringAfterLast("/")
        val posturl = "https://videostreamingworld.com/player/index.php?data=$data&do=getVideo"
        
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to "https://videostreamingworld.com",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        try {
            val responseText = FamilyPornProvider.appPost(url = posturl, data = emptyMap(), headers = headers).text
            Log.d("FP_DEBUG", "VideoStreamingWorld API Response: $responseText")
            
            val video = AppUtils.parseJson<Video>(responseText)
            
            if (video.videoSource != null) {
                val isM3u8 = video.videoSource.contains(".m3u8")
                callback(newExtractorLink(source = "VideoStreamingWorld", name = "VideoStreamingWorld", url = video.videoSource, type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "https://videostreamingworld.com/"
                })
            }
        } catch (e: Exception) {
            Log.e("FP_DEBUG", "VideoStreamingWorld Extractor Crashed: ${e.message}")
        }
    }

    private suspend fun fetchBestWish(url: String, referer: String?, callback: (ExtractorLink) -> Unit) {
        val data = url.trimEnd('/').substringAfterLast("/")
        val getUrl = "https://bestwish.lol/ajax/stream?filecode=$data"
        
        val headers = mapOf(
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive",
            "Referer" to url
        )
        
        try {
            val responseText = FamilyPornProvider.appGet(url = getUrl, headers = headers).text
            Log.d("FP_DEBUG", "BestWish API Response: $responseText")
            
            val stream = AppUtils.parseJson<Stream>(responseText)
            
            if (stream.streaming_url != null) {
                val isM3u8 = stream.streaming_url.contains(".m3u8")
                callback(newExtractorLink(source = "BestWish", name = "BestWish", url = stream.streaming_url, type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = "https://bestwish.lol/"
                })
            }
        } catch (e: Exception) {
            Log.e("FP_DEBUG", "BestWish Extractor Crashed: ${e.message}")
        }
    }

    data class FireResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null
    )
    
    data class Video(
        @JsonProperty("videoSource") val videoSource: String? = null
    )
    
    data class Stream(
        @JsonProperty("streaming_url") val streaming_url: String? = null
    )
}
