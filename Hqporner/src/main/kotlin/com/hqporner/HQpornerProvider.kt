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

    // Set a mobile User-Agent to match the HTML you provided
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
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

        // Fetch with custom headers
        val document = app.get(url, headers = headers).document

        // Try multiple selectors for video containers
        var containers = document.select("div.img-container")
        if (containers.isEmpty()) containers = document.select("div.video-item")
        if (containers.isEmpty()) containers = document.select("div.item")
        if (containers.isEmpty()) containers = document.select("div[class*='img-container']")
        if (containers.isEmpty()) containers = document.select("div[class*='video']")
        if (containers.isEmpty()) containers = document.select("div[class*='item']")
        // Last resort: find any <a> that points to /hdporn/ and use its parent div
        if (containers.isEmpty()) {
            val links = document.select("a[href*='/hdporn/']")
            containers = links.mapNotNull { link ->
                link.parents().find { it.tagName() == "div" && it.hasClass("img-container") }
                    ?: link.parents().find { it.tagName() == "div" && it.hasClass("video-item") }
                    ?: link.parents().find { it.tagName() == "div" && it.hasClass("item") }
                    ?: link.parent()
            }.filter { it.tagName() == "div" }.let { org.jsoup.select.Elements().apply { addAll(it) } }
        }

        val items = containers.mapNotNull { container ->
            extractVideoInfo(container)
        }

        // Pagination detection
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
        val document = app.get(url, headers = headers).document

        var containers = document.select("div.img-container")
        if (containers.isEmpty()) containers = document.select("div.video-item")
        if (containers.isEmpty()) containers = document.select("div.item")
        if (containers.isEmpty()) {
            val links = document.select("a[href*='/hdporn/']")
            containers = links.mapNotNull { link ->
                link.parents().find { it.tagName() == "div" && it.hasClass("img-container") }
                    ?: link.parents().find { it.tagName() == "div" && it.hasClass("video-item") }
                    ?: link.parents().find { it.tagName() == "div" && it.hasClass("item") }
                    ?: link.parent()
            }.filter { it.tagName() == "div" }.let { org.jsoup.select.Elements().apply { addAll(it) } }
        }

        return containers.mapNotNull { container ->
            extractVideoInfo(container)
        }
    }

    private fun extractVideoInfo(container: Element): SearchResponse? {
        // Find the video link
        val link = container.selectFirst("a[href*='/hdporn/']")
            ?: container.selectFirst("a[href^='/hdporn/']")
            ?: container.selectFirst("a[href^='/video/']")
            ?: container.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Get poster
        val img = container.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")

        // Title extraction: image alt is most reliable
        var title = img?.attr("alt")?.trim().orEmpty()

        if (title.isBlank()) {
            // Try h2 in next sibling
            var next = container.nextElementSibling()
            while (next != null) {
                val h2 = next.selectFirst("h2")
                if (h2 != null) {
                    title = h2.text().trim()
                    break
                }
                next = next.nextElementSibling()
            }
        }

        if (title.isBlank()) title = link.attr("title").trim()
        if (title.isBlank()) title = link.text().trim()
        if (title.isBlank()) title = "No Title"

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    // ---------- load, loadLinks, guessQuality remain identical ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers).document
        val title = document.selectFirst("h1.title")?.text()
            ?: document.selectFirst("h1[itemprop='name']")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Unknown"
        val poster = document.selectFirst("video")?.attr("poster")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""
        val description = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }
        val episode = newEpisode("Full Video") { this.posterUrl = poster }
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
        val document = app.get(data, headers = headers).document
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
