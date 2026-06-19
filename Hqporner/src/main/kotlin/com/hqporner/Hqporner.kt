package com.hqporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class Hqporner : MainAPI() {
    override var mainUrl = "https://hqporner.com"
    override var name = "Hqporner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "" to "Recent Videos",
        "category/creampie" to "Creampie",
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
        val url = if (request.data.isBlank()) mainUrl else "$mainUrl/${request.data}"
        val document = app.get(url).document

        // New selectors based on actual HTML
        val videoElements = document.select("div.img-container")
            .ifEmpty { document.select("div.box div.row section") }
            .ifEmpty { document.select("section.video-item") }

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
        // The link is inside the img-container, and the title is in the following div
        val titleElement = if (this.tagName() == "div" && this.hasClass("img-container")) {
            // Find the next sibling div with padding
            val next = this.nextElementSibling()
            if (next?.tagName() == "div" && next.hasAttr("style") && next.text().isNotBlank()) next else null
        } else {
            this
        }

        val title = titleElement?.select("h2")?.text()
            ?: this.select("h3 a").text()
            ?: this.select("a[title]").attr("title")
            ?: "No Title"

        val href = this.select("a[href^='/hdporn/']").attr("href")
            .ifEmpty { this.select("h3 a").attr("href") }
            .ifEmpty { this.select("a[href*='/video/']").attr("href") }

        var posterUrl = this.select("img").attr("src")
        if (posterUrl.isNullOrBlank()) posterUrl = this.select("img[data-src]").attr("data-src")
        if (posterUrl.isNullOrBlank()) posterUrl = this.select("img[data-original]").attr("data-original")
        if (posterUrl.isNullOrBlank()) posterUrl = this.select("img[data-lazy-src]").attr("data-lazy-src")
        if (posterUrl.isNullOrBlank()) posterUrl = this.select("img.lazy").attr("data-src")

        return newMovieSearchResponse(
            fixTitle(title.trim()),
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
            val pageResults = document.select("div.img-container")
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

        var poster = loadData.posterUrl
        if (poster.isNullOrBlank()) {
            poster = document.select("meta[property='og:image']").attr("content")
        }
        if (poster.isNullOrBlank()) {
            poster = document.select("video[poster]").attr("poster")
        }
        if (poster.isNullOrBlank()) {
            poster = document.select("img[src*='thumbs']").attr("src")
        }

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

        // Extract the iframe src – the player URL we need
        var rawUrl = Regex("""<iframe[^>]*src=["']//(.*?)["']""")
            .find(docString)?.groupValues?.get(1)
        if (rawUrl.isNullOrBlank()) {
            rawUrl = Regex("""url:\s*['"]//(.*?)['"]""").find(docString)?.groupValues?.get(1)
        }
        if (rawUrl.isNullOrBlank()) {
            // Try to find the altplayer call
            rawUrl = Regex("""altplayer\.php\?i=//(.*?)'""").find(docString)?.groupValues?.get(1)
        }

        if (rawUrl.isNullOrBlank()) {
            return false
        }

        // Ensure it's a full URL
        val finalUrl = if (rawUrl.startsWith("http")) rawUrl else "https://$rawUrl"
        // Append a trailing slash if missing (mydaddy.cc expects it)
        val playerUrl = if (!finalUrl.endsWith("/")) "$finalUrl/" else finalUrl

        // Now call the extractor on that URL
        loadExtractor(playerUrl, subtitleCallback, callback)
        return true
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
