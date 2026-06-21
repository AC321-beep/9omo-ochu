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

    override val mainPage = mainPageOf(
        "" to "Recent",
        "category/creampie" to "Creampie",
        "category/milf" to "Milf",
        "category/teen-porn" to "Teen",
        "category/ebony" to "Ebony",
        "category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = when {
            request.data.isBlank() -> {
                if (page == 1) mainUrl else "$mainUrl/hdporn/$page"
            }
            else -> {
                if (page == 1) "$mainUrl/${request.data}" else "$mainUrl/${request.data}/$page"
            }
        }

        val document = app.get(url).document

        val videoContainers = document.select("div.img-container")
            .ifEmpty { document.select("div.video-item") }
            .ifEmpty { document.select("div.item") }

        val items = videoContainers.mapNotNull { container ->
            extractVideoInfo(container)
        }

        val hasNext = document.select("div.pagi a[href*='/hdporn/']").isNotEmpty() ||
                document.select("div.pagi a[href*='/category/']").isNotEmpty() ||
                document.select("a.next").isNotEmpty() ||
                (items.isNotEmpty() && page < 10)

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

        val videoContainers = document.select("div.img-container")
            .ifEmpty { document.select("div.video-item") }
            .ifEmpty { document.select("div.item") }

        return videoContainers.mapNotNull { container ->
            extractVideoInfo(container)
        }
    }

    private fun extractVideoInfo(container: Element): SearchResponse? {
        // Find the video link
        val link = container.selectFirst("a[href*='/hdporn/']")
            ?: container.selectFirst("a[href^='/video/']")
            ?: container.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Poster from image
        val img = container.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")

        // ----- Title extraction (multiple fallbacks) -----
        var title = ""

        // 1. Try to find the <h2> in the next sibling div
        var nextSibling = container.nextElementSibling()
        while (nextSibling != null) {
            val h2 = nextSibling.selectFirst("h2")
            if (h2 != null) {
                title = h2.text().trim()
                break
            }
            nextSibling = nextSibling.nextElementSibling()
        }

        // 2. If still empty, use the image's alt attribute (common)
        if (title.isBlank()) {
            title = img?.attr("alt")?.trim().orEmpty()
        }

        // 3. If still empty, use link's title attribute
        if (title.isBlank()) {
            title = link.attr("title").trim()
        }

        // 4. Last resort: link's own text (rarely set)
        if (title.isBlank()) {
            title = link.text().trim()
        }

        if (title.isBlank()) title = "No Title"

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ----- The rest is unchanged -----
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
