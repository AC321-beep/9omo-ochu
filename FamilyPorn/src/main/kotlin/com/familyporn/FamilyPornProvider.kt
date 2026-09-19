package com.familyporn

import android.net.Uri
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebSettings
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
        const val TAG = "FamilyPorn"
        val cfInterceptor = CFInterceptor()

        suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
            Log.e(TAG, "resolveCloudflare() for [$url]")
            var resumed = false
            val activity = CommonActivity.activity
            Log.e(TAG, "resolveCloudflare: activity=$activity")
            activity?.runOnUiThread {
                try {
                    val dialog = CFDialog(url) { success ->
                        Log.e(TAG, "resolveCloudflare: CFDialog result=$success")
                        if (!resumed) {
                            resumed = true
                            cont.resume(success)
                        }
                    }
                    dialog.show()
                } catch (e: Exception) {
                    Log.e(TAG, "resolveCloudflare: dialog failed", e)
                    if (!resumed) { resumed = true; cont.resume(false) }
                }
            } ?: run {
                Log.e(TAG, "resolveCloudflare: activity null")
                if (!resumed) { resumed = true; cont.resume(false) }
            }
        }

        suspend fun appGet(
            url: String,
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            Log.e(TAG, "appGet() url=[$url] headers=$headers")
            var response = try {
                app.get(url, headers = headers, interceptor = cfInterceptor)
            } catch (e: Exception) {
                Log.e(TAG, "appGet() threw for [$url]", e)
                throw e
            }
            Log.e(TAG, "appGet() code=${response.code} url=${response.url}")
            Log.e(TAG, "appGet() body length=${response.text.length}")
            Log.e(TAG, "appGet() preview=${response.text.take(300).replace("\n", " ")}")

            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) &&
                    (text.contains("cloudflare") || text.contains("just a moment"))
            Log.e(TAG, "appGet() isChallenge=$isChallenge")

            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"
                if (resolveCloudflare(safeHostUrl)) {
                    Log.e(TAG, "appGet() CF resolved, retrying")
                    response = app.get(url, headers = headers, interceptor = cfInterceptor)
                    Log.e(TAG, "appGet() retry code=${response.code} len=${response.text.length}")
                } else {
                    Log.e(TAG, "appGet() CF failed/cancelled")
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun appPost(
            url: String,
            data: Map<String, String> = emptyMap(),
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            Log.e(TAG, "appPost() url=[$url] data=$data headers=$headers")
            var response = try {
                app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
            } catch (e: Exception) {
                Log.e(TAG, "appPost() threw for [$url]", e)
                throw e
            }
            Log.e(TAG, "appPost() code=${response.code} url=${response.url}")
            Log.e(TAG, "appPost() body length=${response.text.length}")
            Log.e(TAG, "appPost() preview=${response.text.take(500).replace("\n", " ")}")

            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) &&
                    (text.contains("cloudflare") || text.contains("just a moment"))
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"
                if (resolveCloudflare(safeHostUrl)) {
                    response = app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
                    Log.e(TAG, "appPost() retry code=${response.code}")
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            Log.e(TAG, "getDocument() url=[$url] referer=[$referer]")
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
        Log.e(TAG, "getMainPage() page=$page name=${request.name} url=[$url]")
        val document = getDocument(url)
        val home = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "getMainPage() items=${home.size}")
        return newHomePageResponse(listOf(HomePageList(request.name, home, true)), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.e(TAG, "search() query=[$query]")
        val document = getDocument("$mainUrl/?s=$query")
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "search() results=${results.size}")
        return results
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        Log.e(TAG, "search(page) query=[$query] page=$page url=[$url]")
        val document = getDocument(url)
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, hasNext = true)
    }

    override suspend fun load(url: String): LoadResponse {
        Log.e(TAG, "load() url=[$url]")
        val document = getDocument(url)

        val title = document.selectFirst("h1.entry-title")?.text()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: "Unknown Title"
        Log.e(TAG, "load() title=[$title]")

        val description = document.select("div.entry-content p").text().trim().takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""

        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")
        Log.e(TAG, "load() poster=[$posterUrl]")

        val recommendations = document.select(
            "aside.g1-related-entries li.g1-collection-item, aside.g1-more-from li.g1-collection-item"
        ).mapNotNull { it.toSearchResult() }
        Log.e(TAG, "load() recommendations=${recommendations.size}")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)

            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() }
                ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) }
                catch (e: Exception) { "Mozilla/5.0" }

            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to cookies,
                "User-Agent" to ua,
                "Sec-Fetch-Dest" to "image",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-origin"
            ).filterValues { it.isNotBlank() }

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
        Log.e(TAG, "========== loadLinks() START ==========")
        Log.e(TAG, "loadLinks() data=[$data] isCasting=$isCasting")

        val document = try {
            getDocument(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks() getDocument failed", e)
            return false
        }
        Log.e(TAG, "loadLinks() html length=${document.html().length}")

        var iframeSrc = document.selectFirst(
            "div.embed-container iframe, div.video-wrapper iframe, " +
                    "iframe[src*='watchstream'], iframe[src*='videostreamingworld'], " +
                    "iframe[src*='bestwish']"
        )?.attr("src")
        Log.e(TAG, "loadLinks() step1 iframeSrc=[$iframeSrc]")

        if (iframeSrc.isNullOrBlank()) {
            val allIframes = document.select("iframe").mapNotNull { it.attr("src") }
            Log.e(TAG, "loadLinks() all iframes=$allIframes")
            iframeSrc = allIframes.firstOrNull { it.contains("http") }
            Log.e(TAG, "loadLinks() step2 iframeSrc=[$iframeSrc]")
        }

        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks() no iframe, regex fallback")
            val html = document.html()
            val patterns = listOf(
                Regex("""<iframe.*?src=["']([^"']+)["']""", RegexOption.IGNORE_CASE) to "iframe",
                Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE) to "m3u8",
                Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE) to "mp4",
                Regex("""sources:\s*\[[^\]]*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE) to "sources",
                Regex("""data-stream-url=["']([^"']+)["']""", RegexOption.IGNORE_CASE) to "data-stream-url"
            )
            for ((pattern, label) in patterns) {
                val match = pattern.find(html)
                Log.e(TAG, "loadLinks() regex [$label] -> ${match?.groupValues?.getOrNull(1)}")
                if (match != null) { iframeSrc = match.groupValues[1]; break }
            }
        }

        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks() ABORT: no iframe found")
            Log.e(TAG, "loadLinks() html preview=${document.html().take(1500).replace("\n", " ")}")
            return false
        }

        iframeSrc = fixUrl(iframeSrc)
        Log.e(TAG, "loadLinks() resolved iframe=[$iframeSrc]")

        var emitted = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            Log.e(TAG, "loadLinks() EMITTED name=${link.name} url=${link.url} type=${link.type}")
            callback(link)
        }

        if (iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4")) {
            val isM3u8 = iframeSrc.contains(".m3u8")
            Log.e(TAG, "loadLinks() direct media, isM3u8=$isM3u8")
            trackingCallback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = iframeSrc,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) { this.referer = data }
            )
        } else if (iframeSrc.contains("watchstreamhd") ||
                   iframeSrc.contains("videostreamingworld") ||
                   iframeSrc.contains("bestwish")) {
            Log.e(TAG, "loadLinks() -> FamilyPornExtractor")
            try {
                FamilyPornExtractor().getUrl(iframeSrc, data, subtitleCallback, trackingCallback)
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() extractor threw", e)
            }
        } else {
            Log.e(TAG, "loadLinks() -> loadExtractor")
            try {
                loadExtractor(iframeSrc, data, subtitleCallback, trackingCallback)
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() loadExtractor threw", e)
            }
        }

        if (!emitted) {
            Log.e(TAG, "loadLinks() FAILURE: 0 links emitted for [$iframeSrc]")
            return false
        }
        Log.e(TAG, "loadLinks() SUCCESS")
        Log.e(TAG, "========== loadLinks() END ==========")
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h3.entry-title a")
            ?: this.selectFirst("article a")
            ?: return null
        val title = anchor.text().takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val rawHref = anchor.attr("href")
        if (rawHref.isNullOrBlank()) return null
        val href = fixUrl(rawHref)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() }
                ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) }
                catch (e: Exception) { "Mozilla/5.0" }

            this.posterHeaders = mapOf(
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Referer" to "$mainUrl/",
                "Cookie" to cookies,
                "User-Agent" to ua,
                "Sec-Fetch-Dest" to "image",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "same-origin"
            ).filterValues { it.isNotBlank() }
        }
    }
}
