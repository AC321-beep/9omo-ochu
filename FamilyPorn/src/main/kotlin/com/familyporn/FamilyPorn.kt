package com.familyporn

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val cfInterceptor = FamilyPornInterceptor()

    private val cfPhrases = listOf(
        "just a moment", "checking your browser", "ddos-guard",
        "attention required", "verify you are human", "cloudflare",
        "cf-challenge", "cf-browser-verification", "turnstile",
        "challenge", "please wait", "_cf_chl_opt",
        "javascript challenge", "security check", "browser check",
        "one more step", "enable javascript"
    )

    private suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
        var resumed = false
        CommonActivity.activity?.runOnUiThread {
            val dialog = FamilyPornCFDialog(url) { success ->
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

    // Unified wrapper that automatically pauses your scraper, solves CF, and retries the request
    private suspend fun <T> safeApiCall(
        url: String,
        call: suspend () -> com.lagradost.nicehttp.NiceResponse
    ): com.lagradost.nicehttp.NiceResponse {
        var response = call()
        val text = response.text.lowercase()
        val isChallenge = cfPhrases.any { text.contains(it) } || response.code in listOf(403, 503)

        if (isChallenge) {
            val success = resolveCloudflare(url)
            if (success) {
                response = call() // Retry after successful bypass
            } else {
                throw Error("Cloudflare bypass failed or was cancelled.")
            }
        }
        return response
    }

    suspend fun getDocument(url: String, headers: Map<String, String>? = null, referer: String? = null): Document {
        val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
        referer?.let { finalHeaders["Referer"] = it }
        val response = safeApiCall(url) { app.get(url, headers = finalHeaders, interceptor = cfInterceptor) }
        return response.document
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
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
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
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst(".entry-title")?.text()
            ?: "Unknown Title"

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content") ?: ""

        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")
            ?: document.select("img").firstOrNull { it.attr("src").contains("familypornhd.com") }?.attr("src")

        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            
            val posterCookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to posterCookies,
                "User-Agent" to FamilyPornPlugin.cfUserAgent,
                "sec-ch-ua" to "\"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\", \"Not_A Brand\";v=\"8\"",
                "sec-ch-ua-mobile" to if (FamilyPornPlugin.cfUserAgent.contains("Android")) "?1" else "?0",
                "sec-ch-ua-platform" to if (FamilyPornPlugin.cfUserAgent.contains("Android")) "\"Android\"" else "\"Windows\""
            ).filterValues { it.isNotBlank() }

            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = getDocument(data)
        
        var iframeSrc = document.selectFirst("div.embed-container iframe")?.attr("src")
            ?: document.selectFirst("div.video-wrapper iframe")?.attr("src")
            ?: document.selectFirst("iframe[src*='watchstream']")?.attr("src")
            ?: document.selectFirst("iframe[src*='videostreamingworld']")?.attr("src")
            ?: document.selectFirst("iframe[src*='bestwish']")?.attr("src")

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
            loadExtractor(url = iframeSrc, referer = data, subtitleCallback = subtitleCallback, callback = callback)
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

    private fun Element.toRecommendationResult(): SearchResponse? {
        return toSearchResult()
    }
}
