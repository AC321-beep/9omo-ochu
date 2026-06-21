package com.hqporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://hqporner.com"
    override var name = "HQPorner"
    override var lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Try multiple possible main page URLs
        val url = when (page) {
            1 -> "$mainUrl/new-videos"
            else -> "$mainUrl/new-videos?page=$page"
        }

        val document = app.get(url).document

        // Multiple selectors to catch different layouts
        val videoItems = document.select("div.video-item")
            .ifEmpty { document.select("div.item") }
            .ifEmpty { document.select("div.video-block") }
            .ifEmpty { document.select("div[class*='video']") }
            .ifEmpty { document.select("div[class*='item']") }

        val items = videoItems.mapNotNull { it.toSearchResult() }

        // Fallback: if no items, try the main page without /new-videos
        val finalItems = if (items.isEmpty() && page == 1) {
            val altDoc = app.get(mainUrl).document
            altDoc.select("div.video-item")
                .ifEmpty { altDoc.select("div.item") }
                .ifEmpty { altDoc.select("div.video-block") }
                .mapNotNull { it.toSearchResult() }
        } else items

        val hasNext = document.select("a.next").isNotEmpty() ||
                document.select("a[rel='next']").isNotEmpty() ||
                document.select("a.pagination-next").isNotEmpty()

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = finalItems,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val document = app.get(url).document

        val videoItems = document.select("div.video-item")
            .ifEmpty { document.select("div.item") }
            .ifEmpty { document.select("div.video-block") }
            .ifEmpty { document.select("div[class*='video']") }

        return videoItems.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // Title: multiple possibilities
        val title = document.selectFirst("h1.title")?.text()
            ?: document.selectFirst("h1[itemprop='name']")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Unknown"

        // Poster: from video tag or meta
        val poster = document.selectFirst("video")?.attr("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // Description
        val description = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content") }

        val episode = newEpisode("Full Video") {
            this.posterUrl = poster
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, listOf(episode)) {
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
        val document = app.get(data).document

        // Try direct video source
        val videoSource = document.selectFirst("video source")
        val videoUrl = videoSource?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = guessQuality(videoUrl)
                }
            )
            return true
        }

        // Try iframe embed (some videos are embedded)
        val iframe = document.selectFirst("iframe[src*='/embed/']")
        if (iframe != null) {
            return loadLinks(iframe.attr("src"), isCasting, subtitleCallback, callback)
        }

        // Try to find video URL in JavaScript (some sites use data-attributes)
        val dataVideo = document.selectFirst("video[data-src]")?.attr("data-src")
        if (!dataVideo.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = dataVideo,
                    type = INFER_TYPE
                ) {
                    this.referer = mainUrl
                    this.quality = guessQuality(dataVideo)
                }
            )
            return true
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

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the link
        val link = this.selectFirst("a[href*='/video/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Title: from link's title attribute, or text, or from a separate title element
        val title = link.attr("title")
            .ifEmpty { link.text() }
            .ifEmpty { this.select("h4 a")?.text() }
            .ifEmpty { this.select("div.title a")?.text() }
            .ifEmpty { this.select("p.title a")?.text() }
            .trim()
            .ifEmpty { "No Title" }

        // Thumbnail: try src, data-src, data-original
        val img = this.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")
        if (poster.isNullOrBlank()) poster = this.selectFirst("div.thumb img")?.attr("src")

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }
}
