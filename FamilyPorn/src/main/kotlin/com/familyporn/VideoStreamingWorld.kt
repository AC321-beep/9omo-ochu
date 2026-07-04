package com.familypornhd

import android.util.Log
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule

class VideoStreamingWorld : ExtractorApi() {
    override val name = "VideoStreamingWorld"
    override val mainUrl = "https://videostreamingworld.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val data = url.substringAfterLast("/")
        val posturl = "${mainUrl}/player/index.php?data=$data&do=getVideo"
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
            referer = "$mainUrl/"
        )

        val mapper = jacksonObjectMapper().registerKotlinModule()
        val video: Video = mapper.readValue(response)
        val videoUrl = video.videoSource

        Log.d("VideoStreamingWorld", "videoUrl = $videoUrl")

        callback(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = videoUrl,
                type = ExtractorLinkType.M3U8,
                initializer = { this.referer = "$mainUrl/" }
            )
        )
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Video(val videoSource: String)
