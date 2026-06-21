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

    // Use full URLs for each category
    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // For "Recent", page 1 is mainUrl; pages >1 go to /hdporn/2 etc.
        // For categories, page 1 is the category URL; pages >1 go to /category/xxx/2 etc.
        val baseUrl = request.data
        val url = if (page == 1) {
            baseUrl
        } else {
            // For Recent, base is mainUrl, so append /hdporn/page
            // For category, base already contains /category/xxx, append /page
            when {
                baseUrl == mainUrl -> "$mainUrl/hdporn/$page"
                else -> "$baseUrl/$page"
            }
        }

        val document = app.get(url).document

        // Select containers – main site uses div.img-container
        var containers = document.select("div.img-container")
        if (containers.isEmpty()) containers = document.select("div.video-item")
        if (containers.isEmpty()) containers = document.select("div.item")
        // Fallback: find any a[href*='/hdporn/'] and take its parent div
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
            container.toSearchResult()
        }

        // Detect next page (pagination links)
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

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the link to the video
        val link = this.selectFirst("a[href*='/hdporn/']")
            ?: this.selectFirst("a[href^='/hdporn/']")
            ?: this.selectFirst("a[href^='/video/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Poster from image
        val img = this.selectFirst("img")
        var poster = img?.attr("src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-src")
        if (poster.isNullOrBlank()) poster = img?.attr("data-original")

        // Title: primary from alt, fallback to h2 in next sibling
        var title = img?.attr("alt")?.trim().orEmpty()
        if (title.isBlank()) {
            var next = this.nextElementSibling()
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

        // Capitalise each word (optional, like the working sample)
        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        // Store both href and poster in a JSON string for later use in load()
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
            val document = app.get("$mainUrl/?q=$query&p=$page").document
            val items = document.select("div.img-container")
                .ifEmpty { document.select("div.video-item") }
                .ifEmpty { document.select("div.item") }
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href).document

        // Title from the video page
        var title = document.selectFirst("h1.title")?.text()
            ?: document.selectFirst("header > h1")?.text()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: "Unknown"

        // Capitalise
        title = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        val poster = loadData.posterUrl
        val plot = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }

        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
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
        // Use the regex from the working sample to find the player URL
        val document = app.get(data).document
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
