package com.roshy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RoshyProvider : MainAPI() {
    override var mainUrl = "https://roshy.tv"
    override var name = "Roshy.tv"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override var lang = "en"

    override val mainPage = mainPageOf(
        MainPageData("New Subtitles", "$mainUrl"),
        MainPageData("Decensored", "$mainUrl/category/decensored-5"),
        MainPageData("Big Tits", "$mainUrl/category/big-tits-2"),
        MainPageData("Creampie", "$mainUrl/category/creampie")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // Automatically handle WordPress pagination routing
        val url = if (page == 1) {
            "${request.data}/"
        } else {
            "${request.data}/page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title") ?: this.selectFirst(".post-title a")
        val baseTitle = titleElement?.text() ?: return null
        val href = fixUrl(titleElement.attr("href"))
        
        // Targeted specifically to the blog-img class to bypass SVGs and tracking pixels
        val imgElement = this.selectFirst("img.blog-img") 
        val posterUrl = fixUrl(
            imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: imgElement?.attr("src")
                ?: ""
        )

        // Extract "ENG" and "DC" labels
        val tags = this.select(".tag-label").map { it.text() }.joinToString(" | ")
        
        // Because `quality` only accepts Enums (HD, SD, CAM), we append custom string tags to the title instead
        val displayTitle = if (tags.isNotBlank()) {
            "$baseTitle [$tags]" 
        } else {
            baseTitle
        }

        return newMovieSearchResponse(displayTitle, href, TvType.Movie) {
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
        
        // Target iframes, sources, and also look for raw video tags
        document.select("iframe, source, video").forEach { element ->
            // Catch standard src, and common lazy-loading attributes used by WordPress themes
            val src = element.attr("src")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("data-lazy-src") }
                .ifEmpty { element.attr("data-litespeed-src") }
            
            // fixUrl() automatically converts protocol-relative links (e.g., "//dood.to/...") to "https://dood.to/..."
            val fixedUrl = fixUrl(src)
            
            if (fixedUrl.isNotBlank() && fixedUrl.startsWith("http")) {
                loadExtractor(fixedUrl, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
