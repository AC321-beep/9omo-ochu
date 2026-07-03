package com.perverzija

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.Jsoup

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz"
    override val requiresReferer = true
    private val client = OkHttpClient()

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = fetchHtml(url) ?: return

        // ----- Method 1: Direct <video> or <source> tags -----
        extractFromHtml(html, url)?.let { links ->
            links.forEach { callback.invoke(it) }
            return
        }

        // ----- Method 2: JavaScript variables (video_id + m3u8_loader_url) -----
        extractFromScript(html, url)?.let { links ->
            links.forEach { callback.invoke(it) }
            return
        }

        // ----- Method 3: JSON config inside scripts (common in Plyr/VideoJS) -----
        extractFromJson(html, url)?.let { links ->
            links.forEach { callback.invoke(it) }
            return
        }

        // ----- Method 4: Guess manifest from data parameter (fallback) -----
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isNotBlank()) {
            val base = url.substringBefore("/player/")
            val possibleManifests = listOf(
                "$base/api/video/$dataParam/master.m3u8",
                "$base/api/manifest/$dataParam",
                "$base/manifest/$dataParam.m3u8"
            )
            possibleManifests.forEach { manifest ->
                callback.invoke(
                    createLink(manifest, url, quality = 0, isM3u8 = true)
                )
            }
        }
    }

    // ---------- Helper methods ----------

    private suspend fun fetchHtml(url: String): String? {
        return runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Referer", url)
                .build()
            client.newCall(request).execute().body?.string()
        }.getOrNull()
    }

    private fun extractFromHtml(html: String, pageUrl: String): List<ExtractorLink>? {
        val doc = Jsoup.parse(html)
        val sources = doc.select("video source[src]").map { it.attr("src") } +
                doc.select("video[src]").map { it.attr("src") }
        if (sources.isNotEmpty()) {
            return sources.map { src ->
                createLink(src, pageUrl, quality = guessQuality(src), isM3u8 = src.contains(".m3u8"))
            }
        }
        return null
    }

    private fun extractFromScript(html: String, pageUrl: String): List<ExtractorLink>? {
        val script = Jsoup.parse(html).selectXpath("//script[contains(text(),'var video_id')]").html()
        if (script.isBlank()) return null

        val videoId = script.substringAfter("var video_id = `").substringBefore("`;")
        val loaderUrl = script.substringAfter("var m3u8_loader_url = `").substringBefore("`;")
        if (videoId.isBlank() || loaderUrl.isBlank()) return null

        return listOf(1080, 720, 480, 360).map { quality ->
            val link = "${loaderUrl}/${videoId}&q=${quality}"
            createLink(link, pageUrl, quality = quality, isM3u8 = true)
        }
    }

    private fun extractFromJson(html: String, pageUrl: String): List<ExtractorLink>? {
        // Common patterns in player configuration
        val patterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""link"\s*:\s*"([^"]+\.(mp4|m3u8))""")
        )
        for (pattern in patterns) {
            val match = pattern.find(html)
            val videoUrl = match?.groupValues?.get(1)
            if (!videoUrl.isNullOrBlank()) {
                return listOf(
                    createLink(videoUrl, pageUrl, quality = guessQuality(videoUrl), isM3u8 = videoUrl.contains(".m3u8"))
                )
            }
        }
        return null
    }

    private fun createLink(
        url: String,
        referer: String,
        quality: Int = 0,
        isM3u8: Boolean = false
    ): ExtractorLink {
        return newExtractorLink(
            source = name,
            name = name,
            url = url,
            type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE,
            quality = quality,
            headers = mapOf(
                "Referer" to referer,
                "User-Agent" to USER_AGENT
            )
        )
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") || url.contains("1080p") -> 1080
            url.contains("720") || url.contains("720p") -> 720
            url.contains("480") || url.contains("480p") -> 480
            url.contains("360") || url.contains("360p") -> 360
            else -> 0
        }
    }
}
