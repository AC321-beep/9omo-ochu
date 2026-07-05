package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue

class Fireplayer : ExtractorApi() {
    override var name = "Fireplayer"
    override var mainUrl = "https://watchstreamhd.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val videoid = url.substringAfter("/video/").substringBefore("?")
        Log.d("Fireplayer", "Video ID: $videoid")

        val posturl = "$mainUrl/player/index.php?data=$videoid&do=getVideo"
        val postdata = mapOf("hash" to videoid, "r" to (referer ?: ""))
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to url,
            "Origin" to mainUrl,
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )

        // The interceptor will inject the saved cf_clearance cookie automatically.
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
                    source = this.name,
                    name = this.name,
                    url = videolink,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf("Origin" to mainUrl)
                }
            )
        }
    }
}

data class FireResponse(
    @JsonProperty("securedLink") val securedlink: String? = null,
    @JsonProperty("videoSource") val videosource: String? = null
)
