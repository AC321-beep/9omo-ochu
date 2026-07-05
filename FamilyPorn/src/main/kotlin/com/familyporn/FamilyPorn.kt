package com.familyporn

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

        // ---- CF dialog helper (exactly like AniDb) ----
        private suspend fun showCFDialogIfNeeded(url: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val activity = com.lagradost.cloudstream3.CommonActivity.activity as? AppCompatActivity
                    if (activity == null || activity.isFinishing || activity.isDestroyed) {
                        Log.e(TAG, "No activity available to show CF dialog")
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

        // ---- Network functions with interceptor ----
        suspend fun appGet(
            url: String,
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            var response = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "CF detected, showing dialog")
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    Log.d(TAG, "CF solved, retrying")
                    response = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                } else {
                    Log.w(TAG, "CF not solved, returning blocked response")
                }
            }
            return response
        }

        suspend fun appPost(
            url: String,
            data: Map<String, String> = emptyMap(),
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            var response = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "CF detected in POST, showing dialog")
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    Log.d(TAG, "CF solved, retrying POST")
                    response = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                }
            }
            return response
        }

        // ---- Public wrappers ----
        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null, // unused, interceptor handles cookies
            referer: String? = null
        ): Document {
            Log.d(TAG, "getDocument: $url")
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            if (referer != null) finalHeaders["Referer"] = referer
            val response = appGet(url, finalHeaders)
            return response.document
        }

        suspend fun getText(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            if (referer != null) finalHeaders["Referer"] = referer
            val response = appGet(url, finalHeaders)
            return response.text
        }

        suspend fun postText(
            url: String,
            data: Map<String, String>? = null,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            if (referer != null) finalHeaders["Referer"] = referer
            val response = appPost(url, data ?: emptyMap(), finalHeaders)
            return response.text
        }
    }

    // ---------- MainPage, Search, Load, LoadLinks (use getDocument) ----------
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

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)
        Log.d("FamilyPorn", "Video page HTML snippet: ${document.html().take(500)}")

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text()
            ?: document.selectFirst(".entry-title")?.text()
            ?: document.selectFirst("title")?.text()
            ?: "Unknown Title"

        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst("meta[name=description]")?.attr("content")
            ?: ""

        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("meta[name=twitter:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")
            ?: document.selectFirst("img.attachment-post-thumbnail")?.attr("src")
            ?: document.select("img").firstOrNull { it.attr("src").contains("familypornhd.com") }?.attr("src")

        posterUrl = fixUrlNull(posterUrl)
        Log.d("FamilyPorn", "Poster URL: $posterUrl")

        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf(
                "Referer" to mainUrl,
                "Cookie" to FamilyPornPlugin.cfCookies
            )
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

        if (iframeSrc.isNullOrBlank()) {
            Log.e("FamilyPorn", "No iframe or embed URL found")
            return false
        }

        Log.d("FamilyPorn", "iframe src: $iframeSrc")
        loadExtractor(iframeSrc, referer = data, subtitleCallback = subtitleCallback, callback = callback)
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
