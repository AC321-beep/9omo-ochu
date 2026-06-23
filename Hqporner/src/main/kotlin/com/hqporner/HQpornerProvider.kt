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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }
        // document is nullable – safe call with ?.let
        val items = app.get(url, headers = baseHeaders).document?.select("div.img-container")
            ?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNext = app.get(url, headers = baseHeaders).document?.let { doc ->
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

        // 1. Try built-in extractor directly on the video page
        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) {
            return true
        }

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val document = app.get(videoPageUrl, headers = pageHeaders).document ?: return false

        // 2. Look for the usual iframe
        val iframeSrc = document.selectFirst("div.video-container iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='mydaddy.cc']")?.attr("src")
            ?: document.selectFirst("iframe[src]")?.attr("src")
        if (!iframeSrc.isNullOrBlank()) {
            val iframeUrl = fixUrl(iframeSrc)
            val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            if (loadExtractor(iframeUrl, subtitleCallback, callback)) return true
            if (extractVideoFromPage(iframeUrl, iframeHeaders, videoPageUrl, subtitleCallback, callback)) return true
        }

        // 3. Fallback: look for an "Alternative Player" button/link
        val altPlayerLink = document.selectFirst("a[href*='alt_player'], a.btn-alt, a:contains(Alternative)")?.attr("href")
            ?: document.selectFirst("[data-alt-player]")?.attr("data-alt-player")
        if (!altPlayerLink.isNullOrBlank()) {
            val altUrl = fixUrl(altPlayerLink)
            val altHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            if (extractVideoFromPage(altUrl, altHeaders, videoPageUrl, subtitleCallback, callback)) return true
        }

        // 4. Try to extract directly from the video page itself
        if (extractVideoFromPage(videoPageUrl, pageHeaders, videoPageUrl, subtitleCallback, callback)) return true

        return false
    }

    private suspend fun extractVideoFromPage(
        pageUrl: String,
        headers: Map<String, String>,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val page = app.get(pageUrl, headers = headers).document ?: return false

            // <video><source> tags
            val sources = page.select("video source")
            if (sources.isNotEmpty()) {
                val best = sources.maxByOrNull { s ->
                    guessQualityFromLabel(s.attr("label") ?: s.attr("title") ?: "")
                } ?: sources.first()
                val src = best.attr("src")
                if (src.isNotBlank()) {
                    emitLink(fixUrl(src), referer, callback, guessQualityFromLabel(best.attr("label")))
                    return true
                }
            }

            // video[src]
            val videoSrc = page.selectFirst("video[src]")?.attr("src")
            if (!videoSrc.isNullOrBlank()) {
                emitLink(fixUrl(videoSrc), referer, callback)
                return true
            }

            // og:video meta
            val metaVideo = page.selectFirst("meta[property='og:video']")?.attr("content")
            if (!metaVideo.isNullOrBlank()) {
                emitLink(fixUrl(metaVideo), referer, callback)
                return true
            }

            // Scan <script> blocks for any .mp4 / .m3u8 URLs
            val scriptText = page.select("script").joinToString("\n") { it.html() }
            val urlPattern = Regex("""['"](https?://[^"']+?\.(?:mp4|m3u8)[^"']*?)['"]""", RegexOption.IGNORE_CASE)
            val match = urlPattern.find(scriptText)
            if (match != null) {
                emitLink(fixUrl(match.groupValues[1]), referer, callback)
                return true
            }

            // JSON-like fields (file, video_url, src)
            val jsonPattern = Regex("""["'](?:file|video_url|src)["']\s*:\s*['"]([^"']+\.(?:mp4|m3u8))['"]""", RegexOption.IGNORE_CASE)
            val jsonMatch = jsonPattern.find(scriptText)
            if (jsonMatch != null) {
                emitLink(fixUrl(jsonMatch.groupValues[1]), referer, callback)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080", ignoreCase = true) -> 1080
            url.contains("720", ignoreCase = true) -> 720
            url.contains("360", ignoreCase = true) -> 360
            else -> 0
        }
    }

    private fun guessQualityFromLabel(label: String): Int {
        return when {
            label.contains("1080") -> 1080
            label.contains("720") -> 720
            label.contains("360") -> 360
            else -> 0
        }
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
