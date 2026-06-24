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

    // Known ad/tracker domains to skip when looking for video iframes
    private val adDomains = listOf(
        "adtng.com", "doubleclick.net", "googleadservices.com",
        "adservice", "trafficjunky", "exoclick", "popads", "adnxs"
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
        val maxLen = 2000
        val out = if (msg.length > maxLen) msg.substring(0, maxLen) + "... [TRUNCATED]" else msg
        println("HQP_DEBUG [$tag] $out")
    }

    private fun isAdDomain(url: String): Boolean {
        return adDomains.any { url.contains(it, ignoreCase = true) }
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

        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) {
            debug("SUCCESS", "Built-in worked")
            return true
        }

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val response = app.get(videoPageUrl, headers = pageHeaders)
        val document = response.document ?: run {
            debug("FAIL", "Could not load video page")
            return false
        }

        val pageText = response.text

        // 1. Direct video source on the main page (e.g., <video> tag, m3u8 in scripts)
        if (extractVideoFromText(pageText, videoPageUrl, callback)) {
            return true
        }

        // 2. Look for all iframes, skip ad domains
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val iframeUrl = fixUrl(src)
                debug("IFRAME_FOUND", iframeUrl)
                if (isAdDomain(iframeUrl)) {
                    debug("IFRAME_SKIP", "Skipping ad iframe")
                    continue
                }
                val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
                if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
                    debug("SUCCESS", "Built-in on iframe")
                    return true
                }
                val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
                if (extractVideoFromText(iframeResp.text, videoPageUrl, callback)) {
                    return true
                }
            }
        }

        // 3. Alternative player link
        val altLink = document.selectFirst("a[href*='alt_player'], a.btn-alt, a:contains(Alternative)")?.attr("href")
            ?: document.selectFirst("[data-alt-player]")?.attr("data-alt-player")
        if (!altLink.isNullOrBlank()) {
            val altUrl = fixUrl(altLink)
            debug("ALT_PLAYER", altUrl)
            val altHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            val altResp = app.get(altUrl, headers = altHeaders)
            if (extractVideoFromText(altResp.text, videoPageUrl, callback)) {
                return true
            }
        }

        debug("FAIL", "No playable source found")
        return false
    }

    private suspend fun extractVideoFromText(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Patterns for direct mp4/m3u8 URLs
        val directUrl = Regex("""(https?://[^\s"']+?\.(?:mp4|m3u8)[^\s"']*)""", RegexOption.IGNORE_CASE)
        val match = directUrl.find(text)
        if (match != null && !isAdDomain(match.groupValues[1])) {
            val url = match.groupValues[1]
            debug("DIRECT_URL", url)
            emitLink(url, referer, callback)
            return true
        }

        // Patterns inside JSON/JavaScript
        val jsonPattern = Regex("""["'](?:file|video_url|src|source)["']\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""", RegexOption.IGNORE_CASE)
        val jsonMatch = jsonPattern.find(text)
        if (jsonMatch != null && !isAdDomain(jsonMatch.groupValues[1])) {
            val url = jsonMatch.groupValues[1]
            debug("JSON_URL", url)
            emitLink(fixUrl(url), referer, callback)
            return true
        }

        // <video> tag src
        val videoTag = Regex("""<video[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val videoMatch = videoTag.find(text)
        if (videoMatch != null && !isAdDomain(videoMatch.groupValues[1])) {
            val url = videoMatch.groupValues[1]
            debug("VIDEO_TAG", url)
            emitLink(fixUrl(url), referer, callback)
            return true
        }

        // <source> tag inside video
        val sourceTag = Regex("""<source[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8)[^"']*)["']""", RegexOption.IGNORE_CASE)
        val sourceMatch = sourceTag.find(text)
        if (sourceMatch != null && !isAdDomain(sourceMatch.groupValues[1])) {
            val url = sourceMatch.groupValues[1]
            debug("SOURCE_TAG", url)
            emitLink(fixUrl(url), referer, callback)
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
