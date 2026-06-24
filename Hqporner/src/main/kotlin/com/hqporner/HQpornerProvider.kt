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

    private fun debug(tag: String, msg: String) {
        val maxLen = 2000
        val out = if (msg.length > maxLen) msg.substring(0, maxLen) + "... [TRUNCATED]" else msg
        println("HQP_DEBUG [$tag] $out")
    }

    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/blowjob" to "Blowjob"
    )

    private fun fixUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> mainUrl + url
            else -> url
        }
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
        return newMovieSearchResponse(title, loadData, TvType.NSFW) {
            this.posterUrl = poster
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
        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = tryParseJson<LoadUrl>(data)
        val videoPageUrl = loadData?.href ?: data
        debug("START", videoPageUrl)

        if (loadExtractor(videoPageUrl, subtitleCallback, callback)) {
            return true
        }

        val pageHeaders = baseHeaders.toMutableMap().apply { put("Referer", mainUrl) }
        val response = app.get(videoPageUrl, headers = pageHeaders)
        val document = response.document ?: run {
            debug("FAIL", "No doc")
            return false
        }

        // 1. Try direct extraction from main page
        if (extractVideoFromText(response.text, videoPageUrl, callback)) return true

        // 2. Process all iframes
        val iframes = document.select("iframe[src]")
        for (iframe in iframes) {
            val src = iframe.attr("src")
            if (src.isBlank()) continue
            val url = fixUrl(src)

            // skip ads
            if (url.contains("adtng.com") || url.contains("doubleclick") || url.contains("go.mnaspm.com")) {
                debug("SKIP_AD", url)
                continue
            }

            debug("PROCESS_IFRAME", url)
            val iframeHeaders = baseHeaders.toMutableMap().apply { put("Referer", videoPageUrl) }
            if (loadExtractor(url, subtitleCallback, callback)) return true
            val iframeResp = app.get(url, headers = iframeHeaders)
            debug("IFRAME_HTML", iframeResp.text.take(1000))
            if (extractVideoFromText(iframeResp.text, videoPageUrl, callback)) return true
        }

        // 3. Alternative player link (skip "#")
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

    private fun extractVideoFromText(
        text: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Direct mp4/m3u8 URL in text
        val direct = Regex("""(https?://[^\s"']+?\.(?:mp4|m3u8)[^\s"']*)""", RegexOption.IGNORE_CASE).find(text)
        if (direct != null) {
            emitLink(direct.value, referer, callback, guessQuality(direct.value))
            return true
        }

        // 2. JSON fields: file, video_url, src, source
        val json = Regex("""["'](?:file|video_url|src|source)["']\s*:\s*["']([^"']+\.(?:mp4|m3u8))["']""", RegexOption.IGNORE_CASE).find(text)
        if (json != null) {
            emitLink(fixUrl(json.groupValues[1]), referer, callback, guessQuality(json.groupValues[1]))
            return true
        }

        // 3. HTML5 video tag src
        val videoTag = Regex("""<video[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8))["']""", RegexOption.IGNORE_CASE).find(text)
        if (videoTag != null) {
            emitLink(fixUrl(videoTag.groupValues[1]), referer, callback, guessQuality(videoTag.groupValues[1]))
            return true
        }

        // 4. source tag inside video
        val sourceTag = Regex("""<source[^>]+src\s*=\s*["']([^"']+\.(?:mp4|m3u8))["']""", RegexOption.IGNORE_CASE).find(text)
        if (sourceTag != null) {
            emitLink(fixUrl(sourceTag.groupValues[1]), referer, callback, guessQuality(sourceTag.groupValues[1]))
            return true
        }

        return false
    }

    private fun emitLink(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        quality: Int = guessQuality(url)
    ) {
        val type = if (url.contains(".m3u8", ignoreCase = true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        debug("LINK", "$url (${quality}p)")
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

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080", true) -> 1080
            url.contains("720", true) -> 720
            url.contains("360", true) -> 360
            else -> 0
        }
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String? = null,
        val title: String? = null
    )
}
