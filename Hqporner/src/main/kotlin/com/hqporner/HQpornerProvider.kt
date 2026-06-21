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

    // Home page categories (each row uses the same logic)
    override val mainPage = mainPageOf(
        "" to "Recent",
        "category/creampie" to "Creampie",
        "category/milf" to "Milf",
        "category/teen-porn" to "Teen",
        "category/ebony" to "Ebony",
        "category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build URL for the main page or category
        val url = if (request.data.isBlank()) {
            // "Recent" – base URL
            if (page == 1) mainUrl else "$mainUrl/hdporn/$page"
        } else {
            // Categories: e.g., category/creampie?page=2? (but page is appended as query)
            if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}?page=$page"
        }

        val document = app.get(url).document

        // Select all video thumbnails containers
        var videoContainers = document.select("div.img-container")
        // Fallback selectors if structure changes
        if (videoContainers.isEmpty()) {
            videoContainers = document.select("div.video-item")
        }
        if (videoContainers.isEmpty()) {
            videoContainers = document.select("div.item")
        }

        val items = videoContainers.mapNotNull { container ->
            extractVideoInfo(container)
        }

        // Detect next page – look for "Next" link
        val hasNext = document.select("div.pagi a[href^='/hdporn/']").isNotEmpty() ||
                document.select("a.next").isNotEmpty() ||
                (items.isNotEmpty() && page < 10) // fallback

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val document = app.get(url).document

        // Search results might use the same structure
        val videoContainers = document.select("div.img-container")
            .ifEmpty { document.select("div.video-item") }
            .ifEmpty { document.select("div.item") }

        return videoContainers.mapNotNull { container ->
            extractVideoInfo(container)
        }
    }

    // Helper to extract video info from a container (img-container) and its siblings
    private fun extractVideoInfo(container: Element): SearchResponse? {
        // Find the link inside the container
        val link = container.selectFirst("a[href*='/hdporn/']")
            ?: container.selectFirst("a[href^='/video/']")
            ?: container.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Get thumbnail from img inside the container
        val img = container.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")

        // Title is in the NEXT sibling div containing an h2
        var title = ""
        var nextSibling = container.nextElementSibling()
        while (nextSibling != null) {
            val h2 = nextSibling.selectFirst("h2")
            if (h2 != null) {
                title = h2.text().trim()
                break
            }
            nextSibling = nextSibling.nextElementSibling()
        }

        // If title still empty, try fallback
        if (title.isBlank()) {
            title = link.attr("title")
                .ifEmpty { link.text() }
                .trim()
        }

        if (title.isBlank()) title = "No Title"

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ----- The rest (load, loadLinks, guessQuality) stays the same as before -----
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
}
