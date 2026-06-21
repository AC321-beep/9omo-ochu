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

    // Main page now uses the root URL and is named "Recent"
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build the URL: page 1 -> mainUrl, else mainUrl?page=page
        val url = if (page == 1) mainUrl else "$mainUrl?page=$page"
        val document = app.get(url).document

        // Multiple selectors to catch video items
        val videoItems = document.select("div.video-item")
            .ifEmpty { document.select("div.item") }
            .ifEmpty { document.select("div.video-block") }
            .ifEmpty { document.select("div[class*='video']") }
            .ifEmpty { document.select("div[class*='item']") }

        val items = videoItems.mapNotNull { it.toSearchResult() }

        // Detect if there is a next page
        val hasNext = document.select("a.next").isNotEmpty() ||
                document.select("a[rel='next']").isNotEmpty() ||
                document.select("a.pagination-next").isNotEmpty() ||
                // Some sites use a "Load More" or page parameter; we assume if we got items, there might be more
                (items.isNotEmpty() && page < 10)  // fallback

        return newHomePageResponse(
            list = HomePageList(
                name = "Recent",   // Explicitly name the list "Recent"
                list = items,
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

        val title = document.selectFirst("h1.title")?.text()
            ?: document.selectFirst("h1[itemprop='name']")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Unknown"

        val poster = document.selectFirst("video")?.attr("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        val description = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }

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

        val iframe = document.selectFirst("iframe[src*='/embed/']")
        if (iframe != null) {
            return loadLinks(iframe.attr("src"), isCasting, subtitleCallback, callback)
        }

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
        val link = this.selectFirst("a[href*='/video/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        val title = link.attr("title")
            .orEmpty()
            .ifEmpty { link.text() }
            .ifEmpty { this.select("h4 a")?.text().orEmpty() }
            .ifEmpty { this.select("div.title a")?.text().orEmpty() }
            .ifEmpty { this.select("p.title a")?.text().orEmpty() }
            .trim()
            .ifEmpty { "No Title" }

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
