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

    // Define all home screen categories.
    // The first entry ("") is the main "Recent" page.
    override val mainPage = mainPageOf(
        "" to "Recent",
        "category/creampie" to "Creampie",
        "category/milf" to "Milf",
        "category/teen-porn" to "Teen",
        "category/ebony" to "Ebony",
        "category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build the URL:
        // - For "Recent" (request.data == ""): use the base URL with pagination.
        // - For categories: use "mainUrl/category/xxx" with pagination.
        val url = if (request.data.isBlank()) {
            if (page == 1) mainUrl else "$mainUrl?page=$page"
        } else {
            if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(url).document

        // Try multiple selectors to catch video items.
        val videoItems = document.select("div.video-item")
            .ifEmpty { document.select("div.item") }
            .ifEmpty { document.select("div.video-block") }
            .ifEmpty { document.select("div[class*='video']") }
            .ifEmpty { document.select("div[class*='item']") }
            // Fallback: look for "box feature" inside the main content area.
            .ifEmpty { document.select("section.box.features div.row section.box.feature") }

        val items = videoItems.mapNotNull { it.toSearchResult() }

        // Fallback: if still empty and this is the first page, try the main URL without any path.
        val finalItems = if (items.isEmpty() && page == 1) {
            val altDoc = app.get(mainUrl).document
            altDoc.select("div.video-item")
                .ifEmpty { altDoc.select("div.item") }
                .ifEmpty { altDoc.select("div.video-block") }
                .ifEmpty { altDoc.select("section.box.features div.row section.box.feature") }
                .mapNotNull { it.toSearchResult() }
        } else items

        // Detect if there is a next page.
        val hasNext = document.select("a.next").isNotEmpty() ||
                document.select("a[rel='next']").isNotEmpty() ||
                document.select("a.pagination-next").isNotEmpty() ||
                (finalItems.isNotEmpty() && page < 10) // fallback

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
            .ifEmpty { document.select("section.box.features div.row section.box.feature") }

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
        // Try to find the video link.
        val link = this.selectFirst("a[href*='/hdporn/']")
            ?: this.selectFirst("a[href*='/video/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Build title with safe null handling.
        val title = link.attr("title")
            .orEmpty()
            .ifEmpty { link.text() }
            .ifEmpty { this.select("h4 a")?.text().orEmpty() }
            .ifEmpty { this.select("div.title a")?.text().orEmpty() }
            .ifEmpty { this.select("p.title a")?.text().orEmpty() }
            .trim()
            .ifEmpty { "No Title" }

        // Extract thumbnail.
        val img = this.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")
        if (poster.isNullOrBlank()) poster = this.selectFirst("div.thumb img")?.attr("src")
        // For the "box feature" layout, the image might be inside a div with class "image".
        if (poster.isNullOrBlank()) {
            poster = this.selectFirst("div.image img")?.attr("src")
        }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }
}
