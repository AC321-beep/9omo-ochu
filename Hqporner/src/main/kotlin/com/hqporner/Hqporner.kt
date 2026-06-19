package com.hqporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class Hqporner : MainAPI() {
    override var mainUrl              = "https://hqporner.com"
    override var name                 = "Hqporner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasDownloadSupport   = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    // Order: 1. Recent Videos (homepage), 2. Creampie, then the rest
    override val mainPage = mainPageOf(
        "" to "Recent Videos",                     // #1
        "category/creampie" to "Creampie",         // #2
        "category/milf" to "Milf",
        "category/asian" to "Asian",
        "category/japanese-girls-porn" to "Japanese",
        "studio/free-brazzers-videos" to "Brazzers",
        "category/big-tits" to "Big Tits",
        "category/1080p-porn" to "1080p Porn",
        "category/4k-porn" to "4K Porn",
        "top/week" to "Week TOP",
        "top/month" to "Month TOP",
        "top" to "All Time Best"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // For Recent Videos (empty data), use mainUrl directly
        val url = if (request.data.isBlank()) {
            mainUrl
        } else {
            "$mainUrl/${request.data}"
        }

        val document = app.get(url).document

        // Multiple selectors for robustness
        val videoElements = document.select("div.box.page-content div.row section")
            .ifEmpty { document.select("div.box div.row section") }
            .ifEmpty { document.select("section.video-item") }
            .ifEmpty { document.select("div[class*='video']") }

        val home = videoElements.mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.select("h3 a").text()
            .ifEmpty { this.select("a[title]").attr("title") }
            .ifEmpty { this.select("h2 a").text() }
            .trim()
            .ifEmpty { "No Title" }

        val href = this.select("h3 a").attr("href")
            .ifEmpty { this.select("a[href*='/video/']").attr("href") }
            .ifEmpty { this.select("a[href^='/video/']").attr("href") }

        var posterUrl = this.select("img").attr("src")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.select("img[data-src]").attr("data-src")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = this.select("img[data-original]").attr("data-original")
        }

        return newMovieSearchResponse(
            fixTitle(title),
            fixUrl(href),
            TvType.NSFW
        ) {
            this.posterUrl = fixUrlNull(posterUrl)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (i in 1..2) {
            val document = app.get("${mainUrl}/?q=$query&p=$i").document
            val pageResults = document.select("div.box.page-content div.row section")
                .ifEmpty { document.select("div.box div.row section") }
                .mapNotNull { it.toSearchResult() }
            results.addAll(pageResults)
            if (pageResults.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href).document

        val title = document.select("header > h1").text()
            .ifEmpty { document.select("h1.title").text() }
            .trim()
            .ifEmpty { "No Title" }

        val poster = loadData.posterUrl
        val plot = document.select("meta[property=og:description]").attr("content")
            .ifEmpty { "Hqporner" }

        return newMovieLoadResponse(
            fixTitle(title),
            url,
            TvType.NSFW,
            loadData.href
        ) {
            this.posterUrl = fixUrlNull(poster)
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val docString = document.toString()

        val rawUrl = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""")
            .find(docString)?.groupValues?.get(1)
            ?: Regex("""url:\s*'//(.*?)'""").find(docString)?.groupValues?.get(1)
            ?: return false

        val finalUrl = "https://$rawUrl"
        loadExtractor(finalUrl, subtitleCallback, callback)
        return true
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
