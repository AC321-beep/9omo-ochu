package com.familyporn

import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    companion object Network {
        private const val TAG = "FamilyPorn"
        private val cfMutex = Mutex()

        private val CF_BLOCKER_PHRASES = listOf(
            "just a moment", "checking your browser", "ddos-guard",
            "attention required", "verify you are human", "cloudflare",
            "cf-challenge", "cf-browser-verification", "turnstile",
            "challenge", "please wait", "_cf_chl_opt",
            "javascript challenge", "security check", "browser check",
            "one more step", "enable javascript"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val text = response.text.lowercase()
            return CF_BLOCKER_PHRASES.any { text.contains(it) }
        }

        private suspend fun showCFDialogIfNeeded(url: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val activity = com.lagradost.cloudstream3.CommonActivity.activity as? AppCompatActivity
                    if (activity == null || activity.isFinishing || activity.isDestroyed) {
                        continuation.resume(false)
                        return@suspendCancellableCoroutine
                    }
                    var resumed = false
                    fun safeResume(success: Boolean) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(success)
                        }
                    }
                    val dialog = CloudflareWebViewDialog(
                        targetUrl = url,
                        onFinished = { success -> safeResume(success) }
                    )
                    continuation.invokeOnCancellation {
                        activity.runOnUiThread { runCatching { dialog.dismissAllowingStateLoss() } }
                    }
                    dialog.show(activity.supportFragmentManager, "familyporn_cf_bypass_auto")
                }
            }

        suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
            if (isCloudflareBlocked(response)) {
                cfMutex.withLock {
                    val retryCheck = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) return retryCheck

                    val solved = showCFDialogIfNeeded(url)
                    if (solved) {
                        delay(2500)
                        return app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                    }
                }
            }
            return response
        }

        suspend fun appPost(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
            if (isCloudflareBlocked(response)) {
                cfMutex.withLock {
                    val retryCheck = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) return retryCheck

                    // 🔥 Deep Dive Fix: If a POST request is blocked, send the root domain to WebView, not the API endpoint.
                    val uri = Uri.parse(url)
                    val safeGetUrl = "${uri.scheme}://${uri.host}/"
                    val solved = showCFDialogIfNeeded(safeGetUrl)
                    
                    if (solved) {
                        delay(2500)
                        return app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    }
                }
            }
            return response
        }

        suspend fun getDocument(url: String, headers: Map<String, String>? = null, referer: String? = null): Document {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).document
        }

        suspend fun getText(url: String, headers: Map<String, String>? = null, referer: String? = null): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).text
        }

        suspend fun postText(url: String, data: Map<String, String>? = null, headers: Map<String, String>? = null, referer: String? = null): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appPost(url, data ?: emptyMap(), finalHeaders).text
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
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
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

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")
            ?: document.select("img").firstOrNull { it.attr("src").contains("familypornhd.com") }?.attr("src")

        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            
            // 🔥 Deep Dive Fix: Added missing `sec-ch-ua` to fix Image 403s
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
        Log.d("FamilyPorn Scraper", "🔍 Scraping links for: $data")
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

        Log.d("FamilyPorn Scraper", "✅ Found iframe source: $iframeSrc")

        if (iframeSrc.isNullOrBlank()) {
            Log.e("FamilyPorn Scraper", "❌ Failed to find any iframe or video source in HTML")
            return false
        }

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
        val anchor = this.selectFirst("article a") ?: return null
        val title = anchor.attr("title")?.trim() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }
}
