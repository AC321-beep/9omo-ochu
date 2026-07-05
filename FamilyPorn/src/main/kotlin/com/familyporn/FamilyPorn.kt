package com.familyporn

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object Network {
        private const val TAG = "FamilyPorn"

        // Cloudflare detection phrases
        private val CF_BLOCKER_PHRASES = listOf(
            "just a moment", "checking your browser", "ddos-guard",
            "attention required", "verify you are human", "cloudflare",
            "cf-challenge", "cf-browser-verification", "turnstile",
            "challenge", "please wait", "_cf_chl_opt",
            "javascript challenge", "security check", "browser check",
            "one more step", "enable javascript"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) {
                Log.d(TAG, "Blocked by status code ${response.code}")
                return true
            }

            val text = response.text
            val lower = text.lowercase()

            if (CF_BLOCKER_PHRASES.any { lower.contains(it) }) {
                Log.d(TAG, "Blocked by phrase match")
                return true
            }

            val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(text)
            if (titleMatch != null) {
                val title = titleMatch.groupValues[1].lowercase()
                if (title.contains("just a moment") || title.contains("checking your browser")) {
                    Log.d(TAG, "Blocked by title: $title")
                    return true
                }
            }

            if (text.contains("cf-challenge") || text.contains("_cf_chl_opt")) {
                Log.d(TAG, "Blocked by cf-challenge elements")
                return true
            }

            if (text.length < 50000 && text.contains("<script") && lower.contains("challenge")) {
                Log.d(TAG, "Blocked: small HTML with challenge script")
                return true
            }

            return false
        }

        private suspend fun showCFDialogIfNeeded(url: String): Boolean {
            Log.d(TAG, "Attempting to show CF dialog for $url")
            val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return false
            return suspendCoroutine { continuation ->
                activity.runOnUiThread {
                    val dialog = CloudflareWebViewDialog(
                        targetUrl = url,
                        onFinished = { success ->
                            if (success) Log.d(TAG, "CF solved") else Log.w(TAG, "CF dismissed")
                            continuation.resume(success)
                        }
                    )
                    if (activity is FragmentActivity) {
                        dialog.show(activity.supportFragmentManager, "familyporn_cf_bypass")
                    } else {
                        Log.e(TAG, "Activity not FragmentActivity")
                        continuation.resume(false)
                    }
                }
            }
        }

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            Log.d(TAG, "getDocument: $url")
            var response = app.get(
                url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    response = app.get(
                        url,
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
                }
            }
            return response.document
        }

        suspend fun getText(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            var response = app.get(
                url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    response = app.get(
                        url,
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
                }
            }
            return response.text
        }

        suspend fun postText(
            url: String,
            data: Map<String, String>? = null,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            var response = app.post(
                url,
                data = data ?: emptyMap(),
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    response = app.post(
                        url,
                        data = data ?: emptyMap(),
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
                }
            }
            return response.text
        }
    }

    // ---------- Main page ----------
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
            list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
            hasNext = true
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "${mainUrl}/?s=${query}" else "${mainUrl}/page/$page/?s=${query}"
        val document = getDocument(url)
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = true)
    }

    // ---------- Load video page – improved poster and title extraction ----------
    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        // Log first 500 chars to see the structure
        Log.d("FamilyPorn", "Video page HTML: ${document.html().take(500)}")

        // Title – try multiple sources
        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst(".entry-title")?.text()
            ?: document.selectFirst("title")?.text()
            ?: "Unknown Title"

        // Description
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        // Tags
        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)

        // Poster – try multiple sources
        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("meta[name=twitter:image]")?.attr("content")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("div.entry-content img")?.attr("src")
        }
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.selectFirst("img.attachment-post-thumbnail")?.attr("src")
        }
        // Fallback: look for any large image
        if (posterUrl.isNullOrBlank()) {
            posterUrl = document.select("img").firstOrNull { it.attr("src").contains("familypornhd.com") }?.attr("src")
        }

        // Recommendations
        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    // ---------- Load links – improved iframe extraction ----------
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("FamilyPorn", "loadLinks: $data")
        val document = getDocument(data)

        // Log HTML to see what we're dealing with
        Log.d("FamilyPorn", "Video page HTML for iframe: ${document.html().take(500)}")

        // Try multiple selectors for iframe
        var iframeSrc = document.selectFirst("div.embed-container iframe")?.attr("src")
        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.selectFirst("div.video-wrapper iframe")?.attr("src")
        }
        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.selectFirst("iframe[src*='watchstream']")?.attr("src")
        }
        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.selectFirst("iframe[src*='videostreamingworld']")?.attr("src")
        }
        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.selectFirst("iframe[src*='bestwish']")?.attr("src")
        }

        // If still no iframe, try to find the embed URL in the page (JavaScript variable or link)
        if (iframeSrc.isNullOrBlank()) {
            val html = document.html()
            // Look for common embed patterns
            val patterns = listOf(
                Regex("""iframe.*?src=["'](https?://[^"']+\.com/[^"']+)["']""", RegexOption.IGNORE_CASE),
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

        if (iframeSrc.isNullOrBlank()) {
            Log.e("FamilyPorn", "No iframe or embed URL found")
            return false
        }

        Log.d("FamilyPorn", "iframe src: $iframeSrc")

        // Load the extractor with the iframe URL
        loadExtractor(
            url = iframeSrc,
            referer = data,
            subtitleCallback = subtitleCallback,
            callback = callback
        )
        return true
    }

    // ---------- Helpers ----------
    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("article a") ?: return null
        val title = anchor.attr("title")?.trim() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val anchor = this.selectFirst("article a") ?: return null
        val title = anchor.attr("title")?.trim() ?: return null
        val href = fixUrl(anchor.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }
}
