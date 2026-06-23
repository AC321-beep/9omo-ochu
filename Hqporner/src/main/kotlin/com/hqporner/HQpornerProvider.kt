package com.hqporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element

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

        val duration = this.parent()?.selectFirst("span.meta_data i.fa-clock-o")?.parent()?.text()?.trim() ?: ""

        return newMovieSearchResponse(
            title,
            LoadUrl(fixUrl(href), poster, duration).toJson(),
            TvType.NSFW
        ) {
            this.posterUrl = poster
            this.plot = duration
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

        val title = document.selectFirst("h1")?.text()?.trim() ?: loadData.title
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
        val doc = app.get(data, headers = headers).document

        // 1. Find the main iframe (mydaddy.cc)
        var iframeSrc = doc.selectFirst("div.video-container iframe")?.attr("src")
            ?: doc.selectFirst("iframe[src*='mydaddy.cc']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            return tryAltPlayerFallback(doc, callback)
        }

        if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"

        // 2. Extract video ID for altplayer fallback
        val videoId = Regex("""/video/([^/]+)/""").find(iframeSrc)?.groupValues?.get(1)

        // 3. Try main iframe first
        val success = extractVideoFromIframe(iframeSrc, callback)
        if (success) return true

        // 4. If main fails and we have an ID, try the alternative player
        if (!videoId.isNullOrBlank()) {
            val altUrl = "$mainUrl/blocks/altplayer.php?i=//mydaddy.cc/video/$videoId/"
            val altSuccess = extractVideoFromIframe(altUrl, callback)
            if (altSuccess) return true
        }

        // 5. Last resort: try any other mydaddy.cc iframe on the page
        return tryAltPlayerFallback(doc, callback)
    }

    private suspend fun tryAltPlayerFallback(doc: Element, callback: (ExtractorLink) -> Unit): Boolean {
        val iframes = doc.select("iframe[src*='mydaddy.cc']")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                val fullSrc = if (src.startsWith("//")) "https:$src" else src
                val success = extractVideoFromIframe(fullSrc, callback)
                if (success) return true
            }
        }
        return false
    }

    private suspend fun extractVideoFromIframe(
        iframeUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val page = app.get(iframeUrl, headers = headers).document

            // Look for video in <video> or <source> tags
            val videoSrc = page.selectFirst("video source")?.attr("src")
                ?: page.selectFirst("video")?.attr("src")
                ?: page.selectFirst("source[src*='.mp4']")?.attr("src")
                ?: page.selectFirst("source[src*='.m3u8']")?.attr("src")
                ?: page.selectFirst("meta[property='og:video']")?.attr("content")

            if (!videoSrc.isNullOrBlank()) {
                val url = fixUrl(videoSrc)
                val quality = guessQuality(url)
                callback.invoke(
                    ExtractorLink(
                        source = "HQPorner",
                        url = url,
                        name = "HQPorner ${quality}p",
                        quality = quality,
                        isM3u8 = url.contains(".m3u8"),
                        referer = iframeUrl,
                        headers = headers
                    )
                )
                return true
            }

            // Search in scripts for file or video_url
            val scriptData = page.select("script").map { it.html() }.joinToString("\n")
            val patterns = listOf(
                Regex("""(?:file|video_url|src)\s*[:=]\s*['"]([^'"]+\.(?:mp4|m3u8))['"]""", RegexOption.IGNORE_CASE),
                Regex("""https?://[^\s'"]+\.(mp4|m3u8)""")
            )

            for (pattern in patterns) {
                val match = pattern.find(scriptData)
                if (match != null) {
                    val url = fixUrl(match.groupValues[1])
                    val quality = guessQuality(url)
                    callback.invoke(
                        ExtractorLink(
                            source = "HQPorner",
                            url = url,
                            name = "HQPorner ${quality}p",
                            quality = quality,
                            isM3u8 = url.contains(".m3u8"),
                            referer = iframeUrl,
                            headers = headers
                        )
                    )
                    return true
                }
            }

            return false
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
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
