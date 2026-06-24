package com.perverzija

import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import com.lagradost.cloudstream3.USER_AGENT
import org.jsoup.Jsoup
import java.net.URI

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
        val html = fetchHtml(url, referer) ?: return
        var iframeUrl: String? = null

        // 1. Try to extract from the main page
        var links = extractLinksFromHtml(html, url, referer).toMutableList()

        // 2. If no links, try to follow iframe
        if (links.isEmpty()) {
            val extractedIframeUrl = extractIframeUrl(html, url)
            if (extractedIframeUrl != null) {
                iframeUrl = extractedIframeUrl
                // Use the iframe URL as referer to mimic a browser
                val iframeHtml = fetchHtml(extractedIframeUrl, extractedIframeUrl)
                if (iframeHtml != null) {
                    links = extractLinksFromHtml(iframeHtml, extractedIframeUrl, extractedIframeUrl).toMutableList()
                }
            }
        }

        // 3. Last resort: guess manifest using the correct pattern
        if (links.isEmpty()) {
            val dataParam = url.substringAfter("data=").takeIf { it != url }?.substringBefore("&")
                ?: iframeUrl?.substringAfter("data=")?.substringBefore("&")
                ?: html.substringAfter("data=").takeIf { it != html }?.substringBefore("\"")

            if (!dataParam.isNullOrBlank()) {
                val baseUrl = if (iframeUrl != null) iframeUrl!!.substringBefore("/player/")
                              else url.substringBefore("/player/")
                // Correct pattern: /api/video/<data>&q=1080
                val resolutions = listOf(1080, 720, 480)
                for (res in resolutions) {
                    val manifestUrl = "$baseUrl/api/video/$dataParam&q=$res"
                    links.add(
                        newExtractorLink(
                            name,
                            name,
                            manifestUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = res
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
            }
        }

        // Submit all found links
        links.forEach { callback.invoke(it) }
    }

    private suspend fun fetchHtml(url: String, referer: String?): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer ?: mainUrl)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().body?.string()
    }

    private fun extractIframeUrl(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html)
        val iframe = doc.selectFirst("iframe[src*='player/index.php?data=']")
        val src = iframe?.attr("src") ?: return null
        return try {
            URI(baseUrl).resolve(src).toString()
        } catch (e: Exception) {
            src
        }
    }

    private suspend fun extractLinksFromHtml(
        html: String,
        pageUrl: String,
        referer: String?
    ): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        // ----- Method 1: regex for var video_id and m3u8_loader_url -----
        // Look for any script that contains both variables.
        val scriptRegex = Regex("""<script[^>]*>([\s\S]*?)</script>""")
        for (scriptMatch in scriptRegex.findAll(html)) {
            val scriptContent = scriptMatch.groupValues[1]
            val videoIdRegex = Regex("""video_id\s*=\s*["'`](\w+)["'`]""")
            val loaderRegex = Regex("""m3u8_loader_url\s*=\s*["'`]([^"']+)["'`]""")
            val videoIdMatch = videoIdRegex.find(scriptContent)
            val loaderMatch = loaderRegex.find(scriptContent)
            if (videoIdMatch != null && loaderMatch != null) {
                val videoId = videoIdMatch.groupValues[1]
                var m3u8LoaderUrl = loaderMatch.groupValues[1]
                if (!m3u8LoaderUrl.startsWith("http")) {
                    m3u8LoaderUrl = try {
                        URI(pageUrl).resolve(m3u8LoaderUrl).toString()
                    } catch (e: Exception) {
                        m3u8LoaderUrl
                    }
                }
                val resolutions = listOf(1080, 720, 480)
                for (resolution in resolutions) {
                    links.add(
                        newExtractorLink(
                            name,
                            name,
                            "${m3u8LoaderUrl}/${videoId}&q=${resolution}",
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = resolution
                            this.referer = pageUrl
                            this.headers = mapOf(
                                "Accept" to "*/*",
                                "Referer" to pageUrl,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
                return links
            }
        }

        // ----- Method 2: <video> / <source> tags + regex for direct URLs -----
        val doc = Jsoup.parse(html)
        val videoUrls = mutableListOf<String>()

        val videoSources = doc.select("video source")
        videoSources.forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        val videoTag = doc.selectFirst("video")
        videoTag?.let {
            val src = it.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }

        val regex = Regex("""(https?://[^\s"']+\.(mp4|m3u8))""")
        regex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.isNotBlank() && !videoUrls.contains(videoUrl)) {
                videoUrls.add(videoUrl)
            }
        }

        if (videoUrls.isNotEmpty()) {
            for (videoUrl in videoUrls) {
                val isM3u8 = videoUrl.contains(".m3u8")
                links.add(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = pageUrl
                        this.quality = guessQuality(videoUrl)
                        this.headers = mapOf(
                            "Referer" to pageUrl,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return links
        }

        // ----- Method 3: JSON config inside scripts -----
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video_url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""hls_url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""stream"\s*:\s*"([^"]+\.(mp4|m3u8))""")
        )
        for (pattern in jsonPatterns) {
            for (match in pattern.findAll(html)) {
                var videoUrl = match.groupValues[1]
                if (!videoUrl.startsWith("http")) {
                    videoUrl = try {
                        URI(pageUrl).resolve(videoUrl).toString()
                    } catch (e: Exception) {
                        videoUrl
                    }
                }
                if (videoUrl.isNotBlank()) {
                    links.add(
                        newExtractorLink(
                            name,
                            name,
                            videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = pageUrl
                            this.quality = guessQuality(videoUrl)
                            this.headers = mapOf(
                                "Referer" to pageUrl,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    return links
                }
            }
        }

        return links
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 0
        }
    }
}
