package com.roshy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RoshyProvider : MainAPI() {
    override var mainUrl = "https://roshy.tv"
    override var name = "Roshy.tv"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Adult, TvType.Movie)
    override var lang = "en"

    // Main page categories mapped to their WordPress archive paths
    override val mainPage = mainPageOf(
        MainPageData("New Subtitles", "$mainUrl/"),
        MainPageData("Subtitles", "$mainUrl/category/english-sub-7/"),
        MainPageData("Decensored", "$mainUrl/category/decensored-5/"),
        MainPageData("Big Tits", "$mainUrl/category/big-tits-2/"),
        MainPageData("Creampie", "$mainUrl/category/creampie/"),
        MainPageData("Mature Woman", "$mainUrl/category/mature-woman-2/"),
        MainPageData("Married Woman", "$mainUrl/category/married-woman-3/"),
        MainPageData("Solo Work", "$mainUrl/category/solowork/")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title") ?: this.selectFirst(".post-title a")
        val title = titleElement?.text() ?: return null
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("img")
        val posterUrl = fixUrl(
            imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: imgElement?.attr("src")
                ?: ""
        )

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.post-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Roshy Video"
        val poster = document.selectFirst(".post-featured-image img, .entry-content img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val description = document.selectFirst(".entry-content, .description")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCensored: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        document.select("iframe, source").forEach { element ->
            val src = element.attr("src").ifEmpty { element.attr("data-src") }
            if (src.isNotBlank()) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
