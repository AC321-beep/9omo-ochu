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

    // Use mobile user-agent to get consistent HTML
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; SM-G960F) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36"
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

        // Use headers to mimic mobile browser
        val document = app.get(url, headers = headers).document

        // Updated selectors based on actual HTML structure
        val items = document.select("section.box.features div.6u section.box.feature")
            .mapNotNull { section ->
                section.toSearchResult()
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

    private fun Element.toSearchResult(): SearchResponse? {
        // Find the link inside the section
        val link = this.selectFirst("a[href*='/hdporn/']")
            ?: this.selectFirst("a[href^='/hdporn/']")
            ?: this.selectFirst("a")
            ?: return null

        val href = link.attr("href")
        if (href.isBlank()) return null

        // Get title from the link's text or from a following header
        var title = link.text().trim()
        if (title.isBlank()) {
            // Try to find title in a nearby header
            val header = this.selectFirst("header h3 a, h3 a, h2 a")
            title = header?.text()?.trim().orEmpty()
        }
        if (title.isBlank()) title = "No Title"

        // Capitalise each word
        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        // Get poster from the image inside the link or nearby
        var poster = this.select("img").attr("src")
        if (poster.isBlank()) poster = this.select("img").attr("data-src")
        if (poster.isBlank()) {
            // Try to find image inside the link
            poster = link.select("img").attr("src")
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
