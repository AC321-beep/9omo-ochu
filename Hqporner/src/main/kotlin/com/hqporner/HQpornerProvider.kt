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

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/anal-sex-hd" to "Anal",
        "$mainUrl/category/blowjob" to "Blowjob"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }

        val document = app.get(url, headers = headers).document
        val items = document.select("div.img-container")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.select("div.pagi a[href*='/hdporn/']").isNotEmpty() ||
                document.select("div.pagi a[href*='/category/']").isNotEmpty() ||
                items.isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null

        val titleElement = this.parent()?.selectFirst("h2") ?: return null
        val title = titleElement.text().trim()
        if (title.isBlank()) return null

        val poster = link.selectFirst("img")?.attr("src")?.let {
            if (it.startsWith("//")) "https:$it" else it
        } ?: return null

        return newMovieSearchResponse(
            title,
            LoadUrl(fixUrl(href), poster).toJson(),
            TvType.NSFW
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = headers).document
            val items = document.select("div.img-container")
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = headers).document

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
        // Stage 1: Try CloudStream's built‑in extractor on the main video page.
        if (loadExtractor(data, subtitleCallback, callback)) {
            return true
        }

        // Stage 2: Manually extract iframe URL and try a custom extractor.
        val document = app.get(data, headers = headers).document
        val iframeSrc = document.selectFirst("div.video-container iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='mydaddy.cc']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            return false
        }

        val fullIframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

        // Try custom extraction from iframe (or loadExtractor on it)
        return extractFromIframe(fullIframeUrl, data, subtitleCallback, callback)
    }

    private suspend fun extractFromIframe(
        iframeUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // First try loadExtractor on the iframe URL
        if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
            return true
        }

        // Fallback: manual parsing of the iframe content
        try {
            val page = app.get(iframeUrl, headers = headers).document

            // Try to find video source in <video>, <source>, or meta tags
            val videoSrc = page.selectFirst("video source")?.attr("src")
                ?: page.selectFirst("video")?.attr("src")
                ?: page.selectFirst("source[src*='.mp4']")?.attr("src")
                ?: page.selectFirst("source[src*='.m3u8']")?.attr("src")
                ?: page.selectFirst("meta[property='og:video']")?.attr("content")

            if (!videoSrc.isNullOrBlank()) {
                val url = fixUrl(videoSrc)
                emitLink(url, referer, callback)
                return true
            }

            // Search in scripts for file or video_url
            val scriptData = page.select("script").map { it.html() }.joinToString("\n")
            val pattern = Regex("""(?:file|video_url|src)\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8))['"]""", RegexOption.IGNORE_CASE)
            val match = pattern.find(scriptData)
            if (match != null) {
                val url = fixUrl(match.groupValues[1])
                emitLink(url, referer, callback)
                return true
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * Builds and emits an ExtractorLink via the non-deprecated `newExtractorLink` builder.
     *
     * Note: outer values are copied into local vals (qualityValue/refererValue/headersValue)
     * BEFORE entering the builder lambda. ExtractorLink's own `quality`/`referer`/`headers`
     * properties have the same names, and inside a `Receiver.() -> Unit` lambda those
     * receiver properties shadow identically-named variables from the enclosing scope, so
     * referencing the outer values directly inside the lambda would silently read the
     * (still-default) receiver property instead - this copy step avoids that trap.
     */
    private suspend fun emitLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val qualityValue = guessQuality(url)
        val refererValue = referer
        val headersValue = headers
        val linkType = if (url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                source = "HQPorner",
                name = "HQPorner ${qualityValue}p",
                url = url,
                type = linkType
            ) {
                this.referer = refererValue
                this.quality = qualityValue
                this.headers = headersValue
            }
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

    private fun fixUrl(url: String): String {
        return if (url.startsWith("//")) "https:$url" else url
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
