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

        // First, try to get the data parameter from the iframe on the main page
        val dataParam = extractDataFromIframe(html, url)
            ?: url.substringAfter("data=").takeIf { it != url }?.substringBefore("&")
            ?: html.substringAfter("data=").takeIf { it != html }?.substringBefore("\"")

        val links = mutableListOf<ExtractorLink>()

        if (!dataParam.isNullOrBlank()) {
            // Build the correct manifest URLs using the data parameter
            val baseUrl = url.substringBefore("/player/")
            val resolutions = listOf(1080, 720, 480)

            // Primary pattern: /api/video/<data>&q=<res>
            for (res in resolutions) {
                links.add(
                    newExtractorLink(
                        name,
                        name,
                        "$baseUrl/api/video/$dataParam&q=$res",
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = res
                        this.referer = url
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }

            // Fallback patterns (in case the primary fails)
            listOf(
                "$baseUrl/api/manifest/$dataParam",
                "$baseUrl/manifest/$dataParam.m3u8"
            ).forEach { manifestUrl ->
                links.add(
                    newExtractorLink(
                        name,
                        name,
                        manifestUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = 0
                        this.referer = url
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
        } else {
            // Fallback to legacy extraction (for pages without iframe)
            val legacyLinks = extractLinksFromHtml(html, url, referer)
            links.addAll(legacyLinks)
        }

        // Submit all links
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

    private fun extractDataFromIframe(html: String, baseUrl: String): String? {
        val doc = Jsoup.parse(html)
        val iframe = doc.selectFirst("iframe[src*='player/index.php?data=']")
        val src = iframe?.attr("src") ?: return null
        val fullUrl = try {
            URI(baseUrl).resolve(src).toString()
        } catch (e: Exception) {
            src
        }
        return fullUrl.substringAfter("data=").substringBefore("&").takeIf { it.isNotBlank() }
    }

    // Legacy extraction for pages that embed the player directly (no iframe)
    private suspend fun extractLinksFromHtml(
        html: String,
        pageUrl: String,
        referer: String?
    ): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()

        // Method 1: var video_id and m3u8_loader_url (original working case)
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

        // Method 2: direct video URLs (mp4/m3u8)
        val doc = Jsoup.parse(html)
        val videoUrls = mutableListOf<String>()
        doc.select("video source").forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        doc.selectFirst("video")?.let {
            val src = it.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        Regex("""(https?://[^\s"']+\.(mp4|m3u8))""")
            .findAll(html)
            .forEach { match ->
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

        // Method 3: JSON configs
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))""")
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
