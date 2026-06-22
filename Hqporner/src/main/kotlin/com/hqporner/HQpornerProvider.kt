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

    // Browser headers to avoid blocking
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
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
        val url = if (page == 1) {
            baseUrl
        } else {
            when {
                baseUrl == mainUrl -> "$mainUrl/hdporn/$page"
                else -> "$baseUrl/$page"
            }
        }

        val document = app.get(url, headers = headers).document

        // 1. Find all links to video pages
        val videoLinks = document.select("a[href*='/hdporn/']")
        if (videoLinks.isEmpty()) {
            // If no links, fallback to selectors (just in case)
            val fallbackItems = document.select("section.box.features div.6u section.box.feature")
                .ifEmpty { document.select("div.box.page-content div.row section") }
                .ifEmpty { document.select("div.img-container") }
                .mapNotNull { it.toSearchResult() }
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = fallbackItems, isHorizontalImages = true),
                hasNext = fallbackItems.isNotEmpty() && page < 10
            )
        }

        // 2. For each link, create a search result
        val items = videoLinks.mapNotNull { link ->
            link.toSearchResult()
        }.distinctBy { it.url } // avoid duplicates

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

    // Extract video info directly from the <a> element
    private fun Element.toSearchResult(): SearchResponse? {
        val href = this.attr("href")
        if (href.isBlank()) return null

        // Title: try title attribute, then link text, then image alt
        var title = this.attr("title").trim()
        if (title.isBlank()) title = this.text().trim()
        if (title.isBlank()) {
            val img = this.selectFirst("img")
            title = img?.attr("alt")?.trim().orEmpty()
        }
        if (title.isBlank()) title = "No Title"

        // Capitalise each word
        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        // Poster: find image inside the link or its parent
        var poster: String? = this.select("img").attr("src")
        if (poster.isNullOrBlank()) poster = this.select("img").attr("data-src")
        if (poster.isNullOrBlank()) poster = this.select("img").attr("data-original")
        if (poster.isNullOrBlank()) {
            // If the image is not inside the link, try the parent (common in some layouts)
            val parent = this.parent()
            if (parent != null) {
                poster = parent.select("img").attr("src")
                if (poster.isNullOrBlank()) poster = parent.select("img").attr("data-src")
                if (poster.isNullOrBlank()) poster = parent.select("img").attr("data-original")
            }
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

    // For fallback containers (old selectors) – kept for compatibility
    private fun Element.toSearchResultFallback(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        return link.toSearchResult()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = headers).document
            val links = document.select("a[href*='/hdporn/']")
            val pageResults = links.mapNotNull { it.toSearchResult() }.distinctBy { it.url }
            results.addAll(pageResults)
            if (pageResults.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = headers).document

        var title = document.selectFirst("header > h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) {
            title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
        }
        if (title.isEmpty()) {
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
        }
        if (title.isEmpty()) title = "Unknown"

        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        val poster = loadData.posterUrl
        val plot = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }

        return newMovieLoadResponse(formattedTitle, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document
        val doc = document.toString()
        val rawUrl = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""").find(doc)?.groupValues?.get(1) ?: ""
        if (rawUrl.isBlank()) return false
        val href = "https://$rawUrl"
        loadExtractor(href, subtitleCallback, callback)
        return true
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
