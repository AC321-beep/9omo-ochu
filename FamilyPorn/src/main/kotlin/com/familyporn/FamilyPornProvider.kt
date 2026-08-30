package com.familyporn

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class FamilyPornProvider : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        val cfInterceptor = CFInterceptor()

        // The exact coroutine suspension logic matching the Animexin pattern
        suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
            var resumed = false
            CommonActivity.activity?.runOnUiThread {
                val dialog = CFDialog(url) { success ->
                    if (!resumed) {
                        resumed = true
                        cont.resume(success)
                    }
                }
                dialog.show()
            } ?: run {
                if (!resumed) {
                    resumed = true
                    cont.resume(false)
                }
            }
        }

        suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.get(url, headers = headers, interceptor = cfInterceptor)
            val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare").any { response.text.lowercase().contains(it) } || response.code in listOf(403, 503)
            if (isChallenge) {
                if (resolveCloudflare(url)) {
                    response = app.get(url, headers = headers, interceptor = cfInterceptor)
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun appPost(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
            val isChallenge = listOf("just a moment", "security verification", "attention required", "cloudflare").any { response.text.lowercase().contains(it) } || response.code in listOf(403, 503)
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/" // Solve CF on the main host, not the POST endpoint
                if (resolveCloudflare(safeHostUrl)) {
                    response = app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun getDocument(url: String, headers: Map<String, String>? = null, referer: String? = null): Document {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).document
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "All Porn Videos",
        "$mainUrl/tag/milf/" to "Milf",
        "$mainUrl/tag/creampie/" to "Creampie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val document = getDocument(url)
        val home = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = getDocument("$mainUrl/?s=$query")
        return document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        val document = getDocument(url)
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "Unknown Title"
        val description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")

        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            
            val posterCookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to posterCookies,
                "User-Agent" to CFState.userAgent
            ).filterValues { it.isNotBlank() }

            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = getDocument(data)
        
        var iframeSrc = document.selectFirst("div.embed-container iframe, div.video-wrapper iframe, iframe[src*='watchstream'], iframe[src*='videostreamingworld'], iframe[src*='bestwish']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            val html = document.html()
            val patterns = listOf(
                Regex("""<iframe.*?src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""file:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources:\s*\[[^\]]*file:\s*["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""data-stream-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            )
            for (pattern in patterns) {
                val match = pattern.find(html)
                if (match != null) {
                    iframeSrc = match.groupValues[1]
                    break
                }
            }
        }

        if (iframeSrc.isNullOrBlank()) return false

        if (iframeSrc.contains("watchstreamhd") || iframeSrc.contains("videostreamingworld") || iframeSrc.contains("bestwish")) {
            FamilyPornExtractor().getUrl(iframeSrc, data, subtitleCallback, callback)
        } else {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("article a") ?: return null
        val title = anchor.attr("title")?.trim() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    private fun Element.toRecommendationResult(): SearchResponse? = toSearchResult()
}
