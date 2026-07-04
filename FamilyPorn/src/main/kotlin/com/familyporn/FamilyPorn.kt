package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object Network {
        private val cfKiller = CloudflareKiller()

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            return try {
                app.get(url, headers, cookies, referer, cloudflare = cfKiller).document
            } catch (e: Exception) {
                Log.w("FamilyPorn", "CF GET fallback for $url: ${e.message}")
                app.get(url, headers, cookies, referer).document
            }
        }

        suspend fun getText(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            return try {
                app.get(url, headers, cookies, referer, cloudflare = cfKiller).text
            } catch (e: Exception) {
                Log.w("FamilyPorn", "CF GET text fallback for $url: ${e.message}")
                app.get(url, headers, cookies, referer).text
            }
        }

        suspend fun postText(
            url: String,
            data: Map<String, String>? = null,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            return try {
                app.post(url, data, headers, cookies, referer, cloudflare = cfKiller).text
            } catch (e: Exception) {
                Log.w("FamilyPorn", "CF POST fallback for $url: ${e.message}")
                app.post(url, data, headers, cookies, referer).text
            }
        }

        suspend fun getCookies(url: String): Map<String, String> = app.getCookies(url)
    }

    override val mainPage = mainPageOf(
        "${mainUrl}" to "All Porn Videos",
        "${mainUrl}/tag/redhead" to "Red Head",
        "${mainUrl}/tag/cowgirl" to "Cowgirl",
        "${mainUrl}/tag/doggystyle" to "DoggyStyle",
        "${mainUrl}/tag/latina" to "Latina",
        "${mainUrl}/tag/milf" to "Milf",
        "${mainUrl}/tag/natural-tits" to "Natural Tits",
        "${mainUrl}/tag/stepmomporn" to "Stepmom",
        "${mainUrl}/tag/stepsisterporn" to "Step Sister",
        "${mainUrl}/tag/athletic" to "Athletic",
        "${mainUrl}/tag/asian" to "Asian",
        "${mainUrl}/tag/big-natural-tits" to "Big Natural Tits",
        "${mainUrl}/tag/big-tits" to "Big Tits"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}/page/$page"
        val document = getDocument(url)
        val home = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "${mainUrl}/?s=${query}" else "${mainUrl}/page/$page/?s=${query}"
        val document = getDocument(url)
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query).search

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        val title = document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty()
        val description = document.selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content").orEmpty()
        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = posterUrl
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
        Log.d("FamilyPorn", "loadLinks: $data")
        val document = getDocument(data)
        val iframeSrc = document.selectFirst("div.embed-container iframe")?.attr("src").orEmpty()
        if (iframeSrc.isBlank()) {
            Log.e("FamilyPorn", "No iframe found")
            return false
        }
        Log.d("FamilyPorn", "iframe src: $iframeSrc")

        // loadExtractor does NOT accept cookies parameter
        loadExtractor(
            url = iframeSrc,
            referer = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("article a") ?: return null
        val title = anchor.attr("title")?.trim() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? = toSearchResult()
}
