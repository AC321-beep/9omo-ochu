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

    // Full URLs for each category (including "Recent")
    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Build URL for pagination: page 1 uses base, page >1 appends /page
        val baseUrl = request.data
        val url = if (page == 1) {
            baseUrl
        } else {
            when {
                baseUrl == mainUrl -> "$mainUrl/hdporn/$page"
                else -> "$baseUrl/$page"
            }
        }

        val document = app.get(url).document

        // Use the WORKING selector from the sample
        val items = document.select("div.box.page-content div.row section").mapNotNull { section ->
            section.toSearchResult()
        }

        // Detect next page
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
        // Link and title
        val link = this.selectFirst("h3 a") ?: return null
        val titleRaw = link.text().trim()
        val title = if (titleRaw.isNotEmpty()) {
            titleRaw.split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        } else {
            "No Title"
        }

        val href = fixUrl(link.attr("href"))
        val poster = fixUrlNull(this.select("img").attr("src"))

        // Store both href and poster in JSON for later
        return newMovieSearchResponse(title, LoadUrl(href, poster).toJson(), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val document = app.get("$mainUrl/?q=$query&p=$page").document
            val items = document.select("div.box.page-content div.row section")
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href).document

        // Title from the video page – same as working sample
        var title = document.selectFirst("header > h1")?.text()?.trim().orEmpty()
        if (title.isEmpty()) {
            title = document.selectFirst("h1.title")?.text()?.trim().orEmpty()
        }
        if (title.isEmpty()) {
            title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim().orEmpty()
        }
        if (title.isEmpty()) title = "Unknown"

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
        // Use the exact regex from the working sample
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
