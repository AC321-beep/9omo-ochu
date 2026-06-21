package com.hqporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
import org.jsoup.nodes.Element

class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://hqporner.com"
    override var name = "HQPorner"
    override val lang = "en"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    // ---------- MAIN PAGE ----------
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "$mainUrl/new-videos" else "$mainUrl/new-videos?page=$page"
        val document = app.get(url).document

        val items = document.select("div.video-item").mapNotNull { it.toSearchResult() }
        val hasNext = document.select("a.next").isNotEmpty()

        return newHomePageResponse(name, items, hasNext)
    }

    // ---------- SEARCH ----------
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search/${query.replace(" ", "-")}"
        val document = app.get(url).document
        return document.select("div.video-item").mapNotNull { it.toSearchResult() }
    }

    // ---------- LOAD (DETAILS) ----------
    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.title")?.text() ?: "Unknown"
        val poster = document.selectFirst("video")?.attr("poster") ?: ""
        val description = document.select("div.description p").text()

        val episode = newEpisode("Full Video") {
            this.posterUrl = poster
        }

        return newTvSeriesLoadResponse(title, url, TvType.NSFW, listOf(episode)) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    // ---------- LOAD LINKS (inline extraction) ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        // Attempt direct video source
        val videoSource = document.selectFirst("video source")
        val videoUrl = videoSource?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            callback(
                ExtractorLink(
                    source = name,
                    name = "HQPorner",
                    url = videoUrl,
                    quality = guessQuality(videoUrl),
                    isM3u8 = videoUrl.contains(".m3u8"),
                    headers = mapOf("Referer" to mainUrl)
                )
            )
            return true
        }

        // Fallback: look for iframe embed
        val iframe = document.selectFirst("iframe[src*=/embed/]")
        if (iframe != null) {
            return loadLinks(iframe.attr("src"), isCasting, subtitleCallback, callback)
        }

        return false
    }

    // ---------- HELPERS ----------
    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 0
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a") ?: return null
        val href = link.attr("href")
        val title = link.attr("title").ifEmpty { link.text() }
        val img = this.selectFirst("img")
        val poster = img?.attr("src") ?: img?.attr("data-src")

        return newMovieSearchResponse(title, href) {
            this.posterUrl = poster
        }
    }
}
