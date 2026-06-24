package com.hqporner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

@Suppress("DEPRECATION")
class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://m.hqporner.com"
    override var name = "HQPorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val baseHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    )

    // Reordered main page: Recent, Creampie, Milf, then the rest
    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV",
        "$mainUrl/category/blowjob" to "Blowjob"
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> url
        }
    }

    private fun debug(tag: String, msg: String) {
        println("HQP_DEBUG [$tag] ${msg.take(1500)}")
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }
        val document = app.get(url, headers = baseHeaders).document
        val items = document?.select("div.img-container")?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasNext = document?.let { doc ->
            doc.select("div.pagi a[href*='/hdporn/']").isNotEmpty() ||
                    doc.select("div.pagi a[href*='/category/']").isNotEmpty()
        } ?: false
        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null
        val titleDiv = this.nextElementSibling()
        val titleElement = titleDiv?.selectFirst("h2")
        val title = titleElement?.text()?.trim() ?: return null
        if (title.isBlank()) return null
        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) } ?: return null
        val loadData = LoadUrl(fixUrl(href), poster).toJson()

        // Add Referer header so posters load correctly
        val posterHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }

        return newMovieSearchResponse(title, loadData, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = posterHeaders
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..3) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = baseHeaders).document ?: break
            val items = document.select("div.img-container").mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = baseHeaders).document ?: return null
        val title = document.selectFirst("h1")?.text()?.trim() ?: loadData.title ?: "Unknown"
        val poster = loadData.posterUrl
        val description = document.selectFirst("meta[name='description']")?.attr("content") ?: ""
        // Also fix poster loading on the detail page if possible (though it may use the same posterHeaders)
        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.posterHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
            this.plot = description
        }
    }

    // ... (rest of the provider, including loadLinks, extractVideoFromText, emitLink, etc., unchanged) ...

    // Keep the working extraction code from the previous solution
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadUrl>(data)
        val videoPageUrl = loadData?.href ?: data
        debug("START", videoPageUrl)

        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) return true

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val response = app.get(videoPageUrl, headers = pageHeaders)
        val document = response.document ?: return false

        // Direct extraction from main page
        if (extractVideoFromText(response.text, videoPageUrl, callback)) return true

        // Process iframes (skip ads)
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isBlank()) continue
            val url = fixUrl(src)
            if (url.contains("adtng.com") || url.contains("doubleclick") || url.contains("go.mnaspm.com")) continue

            debug("PROCESS_IFRAME", url)
            val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            if (loadExtractor(url, subtitleCallback, callback)) return true
            val iframeResp = app.get(url, headers = iframeHeaders)
            if (extractVideoFromText(iframeResp.text, videoPageUrl, callback)) return true
        }

        // Alt player link (skip "#")
        val alt = document.selectFirst("a[href*='alt_player'], a:contains(Alternative)")?.attr("href")
        if (!alt.isNullOrBlank() && alt != "#") {
            val altUrl = fixUrl(alt)
            debug("ALT_PLAYER", altUrl)
            val altHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            val altResp = app.get(altUrl, headers = altHeaders)
            if (extractVideoFromText(altResp.text, videoPageUrl, callback)) return true
        }

        debug("FAIL", "No source found")
        return false
    }

   private suspend fun extractVideoFromText(
    text: String,
    referer: String,
    callback: (ExtractorLink) -> Unit
): Boolean {
    // Find all potential mp4/m3u8 URLs, clean them up
    val urlPattern = Regex("""((?:https?:)?//[^\s"'\\]+?\.(?:mp4|m3u8))[^\s"']*""", RegexOption.IGNORE_CASE)
    val rawLinks = urlPattern.findAll(text)
        .map { it.groupValues[1] }
        .map { it.trimEnd('\\', '"', '\'', ',', ';') }
        .filter { it.startsWith("http") || it.startsWith("//") }
        .map { if (it.startsWith("//")) "https:$it" else it }
        .distinct()
        .toList()

    if (rawLinks.isEmpty()) return false

    // Group by detected quality – keep one URL per quality
    val bestPerQuality = mutableMapOf<Int, String>()
    for (url in rawLinks) {
        val q = extractQuality(url)
        // If we haven't seen this quality, or the new URL looks simpler (shorter), keep it
        if (bestPerQuality[q] == null || url.length < bestPerQuality[q]!!.length) {
            bestPerQuality[q] = url
        }
    }

    if (bestPerQuality.isEmpty()) return false

    // Emit links sorted from highest to lowest quality
    bestPerQuality.entries
        .sortedByDescending { it.key }
        .forEach { (quality, url) ->
            debug("FOUND", "$url (${quality}p)")
            emitLink(url, referer, callback, quality)
        }

    return true
}

private fun extractQuality(url: String): Int {
    // Matches /1080p.mp4 or /1080.mp4
    val match = Regex("""/(\d{3,4})p?\.mp4""").find(url)
    return match?.groupValues?.get(1)?.toIntOrNull() ?: when {
        url.contains("1080") -> 1080
        url.contains("720") -> 720
        url.contains("360") -> 360
        else -> 0
    }
}

private suspend fun emitLink(
    url: String,
    referer: String,
    callback: (ExtractorLink) -> Unit,
    quality: Int = 0
) {
    val type = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
    callback.invoke(
        newExtractorLink(
            source = "HQPorner",
            name = "HQPorner ${if (quality > 0) "${quality}p" else "Stream"}",
            url = url,
            type = type
        ) {
            this.referer = referer
            this.quality = quality
            this.headers = baseHeaders.toMutableMap().apply { remove("Referer") }
        }
    )
}

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
