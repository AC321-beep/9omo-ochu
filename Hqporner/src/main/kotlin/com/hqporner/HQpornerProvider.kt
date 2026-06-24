package com.hqporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

@Suppress("DEPRECATION")
class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://m.hqporner.com"
    override var name = "HQPorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/blowjob" to "Blowjob"
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> url
        }
    }

    private fun debug(tag: String, msg: String) {
        // truncate long messages to avoid log overflow
        val maxLen = 2000
        val out = if (msg.length > maxLen) msg.substring(0, maxLen) + "... [TRUNCATED]" else msg
        println("HQP_DEBUG [$tag] $out")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }
        val document = app.get(url, headers = baseHeaders).document
        val items = document?.select("div.img-container")?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNext = document?.let { doc ->
            doc.select("div.pagi a[href*='/hdporn/']").isNotEmpty() ||
                    doc.select("div.pagi a[href*='/category/']").isNotEmpty()
        } ?: false
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null
        val titleDiv = this.nextElementSibling()
        val titleElement = titleDiv?.selectFirst("h2")
        val title = titleElement?.text()?.trim() ?: return null
        if (title.isBlank()) return null
        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: return null
        val loadData = LoadUrl(fixUrl(href), poster).toJson()
        return newMovieSearchResponse(title, loadData, TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = baseHeaders).document ?: break
            val items = document.select("div.img-container").mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = baseHeaders).document ?: return null
        val title = document.selectFirst("h1")?.text()?.trim() ?: loadData.title ?: "Unknown"
        val poster = loadData.posterUrl
        val description = document.selectFirst("meta[name='description']")?.attr("content") ?: ""
        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadUrl>(data)
        val videoPageUrl = loadData?.href ?: data
        debug("START", "Video page: $videoPageUrl")

        // Try built-in first
        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) {
            debug("SUCCESS", "Built-in worked")
            return true
        }

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val response = app.get(videoPageUrl, headers = pageHeaders)
        val document = response.document
        if (document == null) {
            debug("FAIL", "Could not load video page")
            return false
        }

        // Dump first 3000 chars of HTML to see what's there
        debug("HTML", response.text.take(3000))

        // Try iframes
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val iframeUrl = fixUrl(src)
                debug("IFRAME", iframeUrl)
                val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
                if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
                    debug("SUCCESS", "Built-in on iframe")
                    return true
                }
                val iframeResponse = app.get(iframeUrl, headers = iframeHeaders)
                debug("IFRAME_HTML", iframeResponse.text.take(3000))
                if (extractVideoFromPage(iframeUrl, iframeHeaders, videoPageUrl, subtitleCallback, callback, iframeResponse.text)) {
                    return true
                }
            }
        }

        // Alt player link
        val altLink = document.selectFirst("a[href*='alt_player'], a.btn-alt, a:contains(Alternative)")?.attr("href")
            ?: document.selectFirst("[data-alt-player]")?.attr("data-alt-player")
        if (!altLink.isNullOrBlank()) {
            val altUrl = fixUrl(altLink)
            debug("ALT_PLAYER", altUrl)
            val altHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            val altResponse = app.get(altUrl, headers = altHeaders)
            debug("ALT_HTML", altResponse.text.take(3000))
            if (extractVideoFromPage(altUrl, altHeaders, videoPageUrl, subtitleCallback, callback, altResponse.text)) {
                return true
            }
        }

        // Try the video page itself with its own text
        if (extractVideoFromPage(videoPageUrl, pageHeaders, videoPageUrl, subtitleCallback, callback, response.text)) {
            return true
        }

        debug("FAIL", "No source found")
        return false
    }

    private suspend fun extractVideoFromPage(
        pageUrl: String,
        headers: Map<String, String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        pageText: String
    ): Boolean {
        debug("EXTRACTING", pageUrl)

        // Standard HTML5 tags
        val regexVideoSrc = Regex("""<video[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val matchVideo = regexVideoSrc.find(pageText)
        if (matchVideo != null) {
            val src = matchVideo.groupValues[1]
            debug("VIDEO_TAG", src)
            emitLink(fixUrl(src), referer, callback)
            return true
        }

        // <source> inside video
        val regexSource = Regex("""<source[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val matchSource = regexSource.find(pageText)
        if (matchSource != null) {
            val src = matchSource.groupValues[1]
            debug("SOURCE_TAG", src)
            emitLink(fixUrl(src), referer, callback)
            return true
        }

        // Any direct URL in text (most common)
        val urlPattern = Regex("""(https?://[^\s"']+?\.(?:mp4|m3u8)[^\s"']*)""", RegexOption.IGNORE_CASE)
        val matchUrl = urlPattern.find(pageText)
        if (matchUrl != null) {
            val src = matchUrl.groupValues[1]
            debug("DIRECT_URL", src)
            emitLink(src, referer, callback)
            return true
        }

        // JSON-like fields
        val jsonPattern = Regex("""["'](?:file|video_url|src|source)["']\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""", RegexOption.IGNORE_CASE)
        val matchJson = jsonPattern.find(pageText)
        if (matchJson != null) {
            val src = matchJson.groupValues[1]
            debug("JSON_URL", src)
            emitLink(fixUrl(src), referer, callback)
            return true
        }

        return false
    }

    private suspend fun emitLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        quality: Int? = null
    ) {
        val qualityValue = quality ?: guessQuality(url)
        val linkType = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        val headers = baseHeaders.toMutableMap().apply { remove("Referer") }

        debug("LINK", "$url (${qualityValue}p)")

        callback.invoke(
            newExtractorLink(
                source = "HQPorner",
                name = "HQPorner ${if (qualityValue > 0) qualityValue.toString() + "p" else "Stream"}",
                url = url,
                type = linkType
            ) {
                this.referer = referer
                this.quality = qualityValue
                this.headers = headers
            }
        )
    }

    private fun guessQuality(url: String): Int = when {
        url.contains("1080", ignoreCase = true) -> 1080
        url.contains("720", ignoreCase = true) -> 720
        url.contains("360", ignoreCase = true) -> 360
        else -> 0
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
