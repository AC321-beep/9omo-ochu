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
        const val TAG = "FAMILYPORN_DEBUG"
        val cfInterceptor = CFInterceptor()

        suspend fun resolveCloudflare(url: String): Boolean = suspendCancellableCoroutine { cont ->
            Log.e(TAG, "resolveCloudflare() called for [$url]")
            var resumed = false
            val activity = CommonActivity.activity
            Log.e(TAG, "resolveCloudflare: CommonActivity.activity = $activity")
            activity?.runOnUiThread {
                try {
                    Log.e(TAG, "resolveCloudflare: showing CFDialog for [$url]")
                    val dialog = CFDialog(url) { success ->
                        Log.e(TAG, "resolveCloudflare: CFDialog callback success=$success")
                        if (!resumed) {
                            resumed = true
                            cont.resume(success)
                        }
                    }
                    dialog.show()
                } catch (e: Exception) {
                    Log.e(TAG, "resolveCloudflare: exception showing dialog", e)
                    if (!resumed) {
                        resumed = true
                        cont.resume(false)
                    }
                }
            } ?: run {
                Log.e(TAG, "resolveCloudflare: activity is null, aborting")
                if (!resumed) {
                    resumed = true
                    cont.resume(false)
                }
            }
        }

        suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            Log.e(TAG, "appGet() url=[$url]")
            Log.e(TAG, "appGet() headers=$headers")
            var response = try {
                com.lagradost.cloudstream3.app.get(url, headers = headers, interceptor = cfInterceptor)
            } catch (e: Exception) {
                Log.e(TAG, "appGet() request threw exception for [$url]", e)
                throw e
            }
            Log.e(TAG, "appGet() response code=${response.code} url=${response.url}")
            Log.e(TAG, "appGet() response body length=${response.text.length}")
            Log.e(TAG, "appGet() response preview=${response.text.take(300).replace("\n", " ")}")

            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) && (text.contains("cloudflare") || text.contains("just a moment"))
            Log.e(TAG, "appGet() isChallenge=$isChallenge (code=${response.code})")
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"
                Log.e(TAG, "appGet() CF challenge detected, requesting resolve for [$safeHostUrl]")

                if (resolveCloudflare(safeHostUrl)) {
                    Log.e(TAG, "appGet() CF resolved, retrying [$url]")
                    response = com.lagradost.cloudstream3.app.get(url, headers = headers, interceptor = cfInterceptor)
                    Log.e(TAG, "appGet() retry response code=${response.code} url=${response.url}")
                    Log.e(TAG, "appGet() retry body length=${response.text.length}")
                } else {
                    Log.e(TAG, "appGet() CF bypass failed or cancelled for [$url]")
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun appPost(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            Log.e(TAG, "appPost() url=[$url] data=$data")
            var response = try {
                com.lagradost.cloudstream3.app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
            } catch (e: Exception) {
                Log.e(TAG, "appPost() request threw exception for [$url]", e)
                throw e
            }
            Log.e(TAG, "appPost() response code=${response.code} url=${response.url}")

            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) && (text.contains("cloudflare") || text.contains("just a moment"))
            Log.e(TAG, "appPost() isChallenge=$isChallenge")
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"

                if (resolveCloudflare(safeHostUrl)) {
                    Log.e(TAG, "appPost() CF resolved, retrying")
                    response = com.lagradost.cloudstream3.app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
                    Log.e(TAG, "appPost() retry response code=${response.code}")
                } else {
                    Log.e(TAG, "appPost() CF bypass failed or cancelled")
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun getDocument(url: String, headers: Map<String, String>? = null, referer: String? = null): Document {
            Log.e(TAG, "getDocument() url=[$url] referer=[$referer] headers=$headers")
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
        Log.e(TAG, "getMainPage() page=$page request.name=${request.name} -> url=[$url]")
        val document = getDocument(url)
        val home = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "getMainPage() found ${home.size} items")
        return newHomePageResponse(listOf(HomePageList(request.name, home, true)), hasNext = true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.e(TAG, "search() query=[$query]")
        val document = getDocument("$mainUrl/?s=$query")
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "search() found ${results.size} results")
        return results
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = if (page == 1) "$mainUrl/?s=$query" else "$mainUrl/page/$page/?s=$query"
        Log.e(TAG, "search(page) query=[$query] page=$page url=[$url]")
        val document = getDocument(url)
        val results = document.select("li.g1-collection-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "search(page) found ${results.size} results")
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
        Log.e(TAG, "load() tags=$tags")

        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")
        Log.e(TAG, "load() posterUrl=[$posterUrl]")

        val recommendations = document.select("aside.g1-related-entries li.g1-collection-item, aside.g1-more-from li.g1-collection-item")
            .mapNotNull { it.toSearchResult() }
        Log.e(TAG, "load() recommendations=${recommendations.size}")

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)

            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }
            Log.e(TAG, "load() cookies length=${cookies.length} ua=[$ua]")

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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.e(TAG, "============ loadLinks() START ============")
        Log.e(TAG, "loadLinks() data=[$data] isCasting=$isCasting")

        val document = try {
            getDocument(data)
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks() getDocument failed for [$data]", e)
            return false
        }
        Log.e(TAG, "loadLinks() document fetched, html length=${document.html().length}")

        var iframeSrc = document.selectFirst("div.embed-container iframe, div.video-wrapper iframe, iframe[src*='watchstream'], iframe[src*='videostreamingworld'], iframe[src*='bestwish']")?.attr("src")
        Log.e(TAG, "loadLinks() step1 iframeSrc=[$iframeSrc]")

        if (iframeSrc.isNullOrBlank()) {
            val allIframes = document.select("iframe").mapNotNull { it.attr("src") }
            Log.e(TAG, "loadLinks() all iframe srcs=$allIframes")
            iframeSrc = allIframes.firstOrNull { it.contains("http") }
            Log.e(TAG, "loadLinks() step2 iframeSrc=[$iframeSrc]")
        }

        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks() no iframe found, falling back to regex scan")
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
                Log.e(TAG, "loadLinks() regex [$label] match=${match?.groupValues?.getOrNull(1)}")
                if (match != null) {
                    iframeSrc = match.groupValues[1]
                    break
                }
            }
        }

        if (iframeSrc.isNullOrBlank()) {
            Log.e(TAG, "loadLinks() ABORT: iframeSrc is null/blank -> NO LINK FOUND")
            Log.e(TAG, "loadLinks() page html preview=${document.html().take(1000).replace("\n", " ")}")
            return false
        }

        iframeSrc = fixUrl(iframeSrc)
        Log.e(TAG, "loadLinks() resolved iframeSrc=[$iframeSrc]")

        if (iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4")) {
            val isM3u8 = iframeSrc.contains(".m3u8")
            Log.e(TAG, "loadLinks() direct media link detected. isM3u8=$isM3u8 url=[$iframeSrc]")
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = iframeSrc,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = data
                }
            )
            Log.e(TAG, "loadLinks() callback emitted for direct link")
            Log.e(TAG, "============ loadLinks() END (direct) ============")
            return true
        }

        if (iframeSrc.contains("watchstreamhd") || iframeSrc.contains("videostreamingworld") || iframeSrc.contains("bestwish")) {
            Log.e(TAG, "loadLinks() routing to FamilyPornExtractor for [$iframeSrc]")
            try {
                FamilyPornExtractor().getUrl(iframeSrc, data, subtitleCallback, callback)
                Log.e(TAG, "loadLinks() FamilyPornExtractor returned")
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() FamilyPornExtractor threw exception", e)
            }
        } else {
            Log.e(TAG, "loadLinks() routing to loadExtractor for [$iframeSrc]")
            try {
                loadExtractor(iframeSrc, data, subtitleCallback, callback)
                Log.e(TAG, "loadLinks() loadExtractor returned")
            } catch (e: Exception) {
                Log.e(TAG, "loadLinks() loadExtractor threw exception", e)
            }
        }

        Log.e(TAG, "============ loadLinks() END ============")
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h3.entry-title a") ?: this.selectFirst("article a") ?: run {
            Log.e(TAG, "toSearchResult() no anchor found in element")
            return null
        }
        val title = anchor.text().takeIf { it.isNotBlank() } ?: anchor.attr("title").takeIf { it.isNotBlank() } ?: run {
            Log.e(TAG, "toSearchResult() no title found for href=${anchor.attr("href")}")
            return null
        }
        val rawHref = anchor.attr("href") ?: run {
            Log.e(TAG, "toSearchResult() null href for title=[$title]")
            return null
        }
        val href = fixUrl(rawHref)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        Log.e(TAG, "toSearchResult() title=[$title] href=[$href] poster=[$posterUrl]")

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) } catch(e: Exception) { "Mozilla/5.0" }

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
