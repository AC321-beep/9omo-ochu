package com.perverzija

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Perverzija : MainAPI() {
    override var name = "Perverzija"
    override var mainUrl = "https://tube.perverzija.com"
    override val supportedTypes = setOf(TvType.NSFW)
    override val hasDownloadSupport = false
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

    // Realistic headers
    private val browserHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5",
        "Accept-Encoding" to "gzip, deflate, br",
        "Connection" to "keep-alive",
        "Upgrade-Insecure-Requests" to "1"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/tag/creampie/page/%d/" to "Creampie",
        "$mainUrl/tag/family-taboo/page/%d/" to "Family Taboo",
        "$mainUrl/tag/milf/page/%d/" to "Milf",
        "$mainUrl/tag/wife/page/%d/" to "Wife",
        "$mainUrl/tag/teen/page/%d/" to "Teen",
        "$mainUrl/featured-scenes/page/%d/?orderby=date" to "Featured",
        "$mainUrl/studio/onlyfans/page/%d/" to "Onlyfans",
        "$mainUrl/studio/brazzers/page/%d/" to "Brazzers",
        "$mainUrl/studio/nubiles/page/%d/" to "Nubiles",
        "$mainUrl/studio/realitykings/page/%d/" to "Reality Kings",
        "$mainUrl/studio/bangbros/page/%d/" to "Bangbros",
        "$mainUrl/studio/naughtyamerica/page/%d/" to "Naughty America"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(
            request.data.format(page),
            interceptor = cfInterceptor,
            timeout = 100L,
            headers = browserHeaders
        ).document
        val home = document.select("div.row div div.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name, list = home, isHorizontalImages = true
            ), hasNext = true
        )
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("dt a img").attr("src"))
        val title = this.select("dd a").text() ?: return null
        val href = fixUrlNull(this.select("dt a").attr("href")) ?: return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("div.item-thumbnail img").attr("src"))
        val title = this.select("div.item-head a").text() ?: return null
        val href = fixUrlNull(this.select("div.item-head a").attr("href")) ?: return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (query.contains(" ")) {
            "$mainUrl/page/$page/?s=${query.replace(" ", "+")}&orderby=date"
        } else {
            "$mainUrl/tag/$query/page/$page/"
        }
        val results = app.get(url, interceptor = cfInterceptor, headers = browserHeaders).document
            .select("div.row div div.post").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        val hasNext = results.isNotEmpty()
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor, headers = browserHeaders).document
        val poster = document.select("div#featured-img-id img").attr("src")
        val title = document.select("div.title-info h1.light-title.entry-title").text()
        val pTags = document.select("div.item-content p")
        val description = pTags.joinToString("\n") { it.text() }
        val tags = document.select("div.item-tax-list div a").map { it.text() }
        val recommendations =
            document.select("div.related-gallery dl.gallery-item").mapNotNull {
                it.toRecommendationResult()
            }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Force VideoJS
        val fixedUrl = if (data.contains("?link=")) {
            data.replace(Regex("[?&]link=\\d+"), "?link=1")
        } else {
            "$data?link=1"
        }

        val document = app.get(fixedUrl, interceptor = cfInterceptor, headers = browserHeaders).document
        val iframeUrl = document.select("div#player-embed iframe").attr("src")
        if (iframeUrl.isBlank()) return false

        // Try built-in extractors
        if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
            return true
        }

        // Custom extractor
        var linkFound = false
        val wrapperCallback: (ExtractorLink) -> Unit = { link ->
            linkFound = true
            callback(link)
        }
        Xtremestream().getUrl(iframeUrl, fixedUrl, subtitleCallback, wrapperCallback)
        return linkFound
    }
}
