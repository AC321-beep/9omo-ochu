package com.roshy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RoshyProvider : MainAPI() {
    override var mainUrl = "https://roshy.tv"[cite: 1, 4]
    override var name = "Roshy.tv"[cite: 1, 4]
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Adult, TvType.Movie)
    override val lang = "en"[cite: 1, 4]

    // Main page categories mapped to their WordPress archive paths
    override val mainPage = mainPageOf(
        "$mainUrl/" to "New Subtitles",
        "$mainUrl/category/english-sub-7/" to "Subtitles",[cite: 1, 4]
        "$mainUrl/category/decensored-5/" to "Decensored",[cite: 1, 4]
        "$mainUrl/category/big-tits-2/" to "Big Tits",[cite: 1, 4]
        "$mainUrl/category/creampie/" to "Creampie",[cite: 1, 4]
        "$mainUrl/category/mature-woman-2/" to "Mature Woman",[cite: 1, 4]
        "$mainUrl/category/married-woman-3/" to "Married Woman",[cite: 1, 4]
        "$mainUrl/category/solowork/" to "Solo Work"[cite: 1, 4]
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data).document
        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }[cite: 1, 4]
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title") ?: this.selectFirst(".post-title a")[cite: 1, 4]
        val title = titleElement?.text() ?: return null
        val href = fixUrl(titleElement.attr("href"))
        
        val imgElement = this.selectFirst("img")[cite: 1, 4]
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
        val document = app.get("$mainUrl/?s=$query").document[cite: 1, 4]
        return document.select("article.post-item").mapNotNull { it.toSearchResult() }[cite: 1, 4]
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
