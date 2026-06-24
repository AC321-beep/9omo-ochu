package com.hqporner

import android.util.Log
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

    // ============== Debug helper ==============
    private fun debug(tag: String, msg: String) {
        Log.d("HQP_DEBUG", "[$tag] $msg")
    }

    // ============== Main Page ==============
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

    // ============== Link Extraction ==============
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadUrl>(data)
        val videoPageUrl = loadData?.href ?: data
        debug("START", "Video page: $videoPageUrl")

        // 1. Built-in extractor
        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) {
            debug("SUCCESS", "Built-in extractor worked on video page")
            return true
        }

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val document = app.get(videoPageUrl, headers = pageHeaders).document
        if (document == null) {
            debug("FAIL", "Could not load video page")
            return false
        }

        // 2. Extract from all iframes
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val iframeUrl = fixUrl(src)
                debug("IFRAME", "Found iframe: $iframeUrl")
                val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
                if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
                    debug("SUCCESS", "Built-in extractor worked on iframe")
                    return true
                }
                if (extractVideoFromPage(iframeUrl, iframeHeaders, videoPageUrl, subtitleCallback, callback)) {
                    return true
                }
            }
        }

        // 3. Look for an "Alternative Player" link
        val altLink = document.selectFirst("a[href*='alt_player'], a.btn-alt, a:contains(Alternative)")?.attr("href")
            ?: document.selectFirst("[data-alt-player]")?.attr("data-alt-player")
        if (!altLink.isNullOrBlank()) {
            val altUrl = fixUrl(altLink)
            debug("ALT_PLAYER", "Alternative player URL: $altUrl")
            val altHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            if (extractVideoFromPage(altUrl, altHeaders, videoPageUrl, subtitleCallback, callback)) {
                return true
            }
        }

        // 4. Try the video page itself (some sites load player dynamically)
        if (extractVideoFromPage(videoPageUrl, pageHeaders, videoPageUrl, subtitleCallback, callback)) {
            return true
        }

        debug("FAIL", "No source found after all attempts")
        return false
    }

    // Common extraction from a given page
    private suspend fun extractVideoFromPage(
        pageUrl: String,
        headers: Map<String, String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        debug("EXTRACTING", "From $pageUrl")
        try {
            val response = app.get(pageUrl, headers = headers)
            val page = response.document ?: return false
            val pageText = response.text

            // 1. <video> sources
            val videoSources = page.select("video source")
            for (source in videoSources) {
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    debug("SOURCE_TAG", src)
                    val q = guessQualityFromLabel(source.attr("label"))
                    emitLink(fixUrl(src), referer, callback, q)
                    return true
                }
            }

            // 2. video[src]
            val videoSrc = page.selectFirst("video[src]")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                debug("VIDEO_SRC", videoSrc)
                emitLink(fixUrl(videoSrc), referer, callback)
                return true
            }

            // 3. meta og:video
            val metaVideo = page.selectFirst("meta[property='og:video']")?.attr("content")
            if (!metaVideo.isNullOrBlank()) {
                debug("META_OG", metaVideo)
                emitLink(fixUrl(metaVideo), referer, callback)
                return true
            }

            // 4. Search for URLs in scripts / JSON / data-attributes
            val patterns = listOf(
                """https?://[^"'\s]+?\.(?:mp4|m3u8)[^"'\s]*""".toRegex(RegexOption.IGNORE_CASE),
                """["'](?:file|video_url|src)["']\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""".toRegex(RegexOption.IGNORE_CASE),
                """['"]source['"]\s*:\s*['"]([^'"]+\.mp4)['"]""".toRegex(RegexOption.IGNORE_CASE),
                """var\s+\w+\s*=\s*['"]([^'"]+\.m3u8)['"]""".toRegex(RegexOption.IGNORE_CASE)
            )
            for (pat in patterns) {
                val match = pat.find(pageText)
                if (match != null) {
                    val url = match.groupValues[1].ifBlank { match.value }
                    if (url.contains(".mp4") || url.contains(".m3u8")) {
                        debug("REGEX_MATCH", url)
                        emitLink(fixUrl(url), referer, callback)
                        return true
                    }
                }
            }

            // 5. Check for Base64 encoded iframe/data (common on adult sites)
            val base64Pattern = """atob\('([^']+)'\)""".toRegex()
            val b64Match = base64Pattern.find(pageText)
            if (b64Match != null) {
                val decoded = android.util.Base64.decode(b64Match.groupValues[1], android.util.Base64.DEFAULT)
                val decodedStr = String(decoded)
                val innerUrl = """https?://[^"'\s]+""".toRegex().find(decodedStr)?.value
                if (innerUrl != null) {
                    debug("BASE64_DECODED", innerUrl)
                    // try to extract from that inner URL (maybe it's an iframe/m3u8)
                    if (extractVideoFromPage(innerUrl, headers, referer, subtitleCallback, callback)) {
                        return true
                    }
                }
            }

            // 6. External embed (e.g., mydaddy.cc, etc.) – look for any iframe
            val embedIframe = page.selectFirst("iframe[src]")
            if (embedIframe != null) {
                val embedUrl = fixUrl(embedIframe.attr("src"))
                debug("EMBED_IFRAME", embedUrl)
                if (extractVideoFromPage(embedUrl, headers, referer, subtitleCallback, callback)) {
                    return true
                }
            }

        } catch (e: Exception) {
            debug("ERROR", e.message ?: "unknown")
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

        debug("LINK", "Found: $url (${qualityValue}p)")

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

    private fun guessQualityFromLabel(label: String): Int = when {
        label.contains("1080") -> 1080
        label.contains("720") -> 720
        label.contains("360") -> 360
        else -> 0
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
