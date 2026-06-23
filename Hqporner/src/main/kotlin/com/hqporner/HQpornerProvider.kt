package com.hqporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://hqporner.com"
    override var name = "HQPorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }

        val document = app.get(url, headers = headers).document
        val items = document.select("section.box.features div.6u section.box.feature")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.select("ul.actions.pagination a[href*='/hdporn/']").isNotEmpty() ||
                document.select("ul.actions.pagination a[href*='/category/']").isNotEmpty() ||
                document.select("a.next").isNotEmpty() ||
                (items.isNotEmpty() && page < 10)

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null

        var title = this.selectFirst("h3.meta-data-title a")?.text()?.trim()
        if (title.isNullOrBlank()) title = link.attr("title").trim()
        if (title.isNullOrBlank()) title = link.text().trim()
        if (title.isNullOrBlank()) title = "No Title"

        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        // Improved poster extraction: try multiple sources
        var poster: String? = link.selectFirst("img")?.attr("src")
        if (poster.isNullOrBlank()) poster = link.selectFirst("img")?.attr("data-src")
        if (poster.isNullOrBlank()) {
            poster = this.selectFirst("img")?.attr("src")
        }
        if (!poster.isNullOrBlank() && poster.startsWith("//")) {
            poster = "https:$poster"
        }
        poster = fixUrlNull(poster)

        return newMovieSearchResponse(
            formattedTitle,
            LoadUrl(fixUrl(href), poster).toJson(),
            TvType.NSFW
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = headers).document
            val items = document.select("section.box.features div.6u section.box.feature")
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = headers).document

        var title = document.selectFirst("header > h1")?.text()?.trim()
        if (title.isNullOrBlank()) title = document.selectFirst("h1.title")?.text()?.trim()
        if (title.isNullOrBlank()) title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        if (title.isNullOrBlank()) title = "Unknown"

        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        // Use og:image for poster (more reliable)
        var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) {
            poster = loadData.posterUrl
        }
        poster = fixUrlNull(poster)

        val plot = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }

        return newMovieLoadResponse(formattedTitle, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // ---------- IMPROVED loadLinks ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        var videoUrl: String? = null

        // 1. Try to extract from iframe and its page
        val iframe = document.selectFirst("iframe[src*='mydaddy.cc']")
            ?: document.selectFirst("iframe[src*='/video/']")
            ?: document.selectFirst("iframe[src*='embed']")

        if (iframe != null) {
            var iframeSrc = iframe.attr("src")
            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"
            if (iframeSrc.isNotBlank()) {
                // Fetch iframe page
                val iframeDoc = app.get(iframeSrc, headers = headers).document
                val iframeHtml = iframeDoc.toString()

                // Look for <video> or <source>
                videoUrl = iframeDoc.selectFirst("video source")?.attr("src")
                if (videoUrl.isNullOrBlank()) {
                    videoUrl = iframeDoc.selectFirst("video")?.attr("src")
                }

                // Look for JavaScript variable (like var video_id)
                if (videoUrl.isNullOrBlank()) {
                    val videoId = Regex("""var video_id = ['"`]([^'"`]+)['"`]""").find(iframeHtml)?.groupValues?.get(1)
                    val m3u8Loader = Regex("""var m3u8_loader_url = ['"`]([^'"`]+)['"`]""").find(iframeHtml)?.groupValues?.get(1)
                    if (!videoId.isNullOrBlank() && !m3u8Loader.isNullOrBlank()) {
                        // This is similar to the Xtremestream pattern
                        videoUrl = "$m3u8Loader/$videoId&q=1080" // or multiple qualities
                        // We can just pass this URL to loadExtractor, it may handle it.
                        // But we also need to handle quality selection if possible.
                        // For simplicity, we'll just let loadExtractor handle it.
                    }
                }

                if (!videoUrl.isNullOrBlank()) {
                    return loadExtractor(videoUrl, subtitleCallback, callback)
                }

                // If no direct video, try altplayer endpoint
                val videoIdFromIframe = Regex("""/video/([^/]+)/""").find(iframeSrc)?.groupValues?.get(1)
                if (!videoIdFromIframe.isNullOrBlank()) {
                    val altUrl = "$mainUrl/blocks/altplayer.php?i=//mydaddy.cc/video/$videoIdFromIframe/"
                    val altDoc = app.get(altUrl, headers = headers).document
                    videoUrl = altDoc.selectFirst("video source")?.attr("src")
                    if (videoUrl.isNullOrBlank()) {
                        videoUrl = altDoc.selectFirst("video")?.attr("src")
                    }
                    if (!videoUrl.isNullOrBlank()) {
                        return loadExtractor(videoUrl, subtitleCallback, callback)
                    }

                    // Try nativeplayer
                    val nativeUrl = "$mainUrl/blocks/nativeplayer.php?i=//mydaddy.cc/video/$videoIdFromIframe/"
                    val nativeDoc = app.get(nativeUrl, headers = headers).document
                    videoUrl = nativeDoc.selectFirst("video source")?.attr("src")
                    if (videoUrl.isNullOrBlank()) {
                        videoUrl = nativeDoc.selectFirst("video")?.attr("src")
                    }
                    if (!videoUrl.isNullOrBlank()) {
                        return loadExtractor(videoUrl, subtitleCallback, callback)
                    }
                }
            }
        }

        // 2. Fallback to altplayer regex from original code
        val docHtml = document.toString()
        val rawUrl = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""").find(docHtml)?.groupValues?.get(1)
        if (!rawUrl.isNullOrBlank()) {
            val href = "https://$rawUrl"
            return loadExtractor(href, subtitleCallback, callback)
        }

        // 3. Final fallback: direct mp4/m3u8 in the page
        val directUrl = Regex("""(https?://[^\s"']+\.(mp4|m3u8))""").find(docHtml)?.groupValues?.get(1)
        if (!directUrl.isNullOrBlank()) {
            return loadExtractor(directUrl, subtitleCallback, callback)
        }

        return false
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

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
