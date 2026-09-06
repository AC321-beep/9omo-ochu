package com.familyporn

import android.net.Uri
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
        val cfInterceptor = CFInterceptor()

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
            var response = com.lagradost.cloudstream3.app.get(url, headers = headers, interceptor = cfInterceptor)
            val text = response.text.lowercase()
            
            val isChallenge = response.code in listOf(403, 503) && (text.contains("cloudflare") || text.contains("just a moment"))
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/" 
                
                if (resolveCloudflare(safeHostUrl)) {
                    response = com.lagradost.cloudstream3.app.get(url, headers = headers, interceptor = cfInterceptor)
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun appPost(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = com.lagradost.cloudstream3.app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
            val text = response.text.lowercase()
            
            val isChallenge = response.code in listOf(403, 503) && (text.contains("cloudflare") || text.contains("just a moment"))
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/" 
                
                if (resolveCloudflare(safeHostUrl)) {
                    response = com.lagradost.cloudstream3.app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
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
        return newHomePageResponse(listOf(HomePageList(request.name, home, true)), hasNext = true)
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

        val title = document.selectFirst("h1.entry-title")?.text() 
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" - ")
            ?: "Unknown Title"
            
        val description = document.select("div.entry-content p").text().trim().takeIf { it.isNotBlank() } 
            ?: document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
            
        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }
        
        val posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.entry-content img")?.attr("src")

        val recommendations = document.select("aside.g1-related-entries li.g1-collection-item, aside.g1-more-from li.g1-collection-item")
            .mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)
            
            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
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

            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = getDocument(data)
        
        var iframeSrc = document.selectFirst("div.embed-container iframe, div.video-wrapper iframe, iframe[src*='watchstream'], iframe[src*='videostreamingworld'], iframe[src*='bestwish']")?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.select("iframe").mapNotNull { it.attr("src") }.firstOrNull { it.contains("http") }
        }

        if (iframeSrc.isNullOrBlank()) {
            val html = document.html()
            val patterns = listOf(
                Regex("""<iframe.*?src=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
                Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""file:\s*["']([^"']+\.mp4[^"']*)["']""", RegexOption.IGNORE_CASE),
                Regex("""sources:\s*\[[^\]]*file:\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE),
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
        iframeSrc = fixUrl(iframeSrc)

        if (iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4")) {
            val isM3u8 = iframeSrc.contains(".m3u8")
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
            return true
        }

        if (iframeSrc.contains("watchstreamhd") || iframeSrc.contains("videostreamingworld") || iframeSrc.contains("bestwish")) {
            FamilyPornExtractor().getUrl(iframeSrc, data, subtitleCallback, callback)
        } else {
            loadExtractor(iframeSrc, data, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h3.entry-title a") ?: this.selectFirst("article a") ?: return null
        val title = anchor.text().takeIf { it.isNotBlank() } ?: anchor.attr("title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrl(anchor.attr("href") ?: return null)
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))
        
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
