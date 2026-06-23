package com.hqporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class HQPornerProvider : MainAPI() {
    override var mainUrl = "https://hqporner.com"
    override var name = "HQPorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.NSFW)
    override val vpnStatus = VPNStatus.MightBeNeeded

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to mainUrl
    )

    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data
        val url = if (page == 1) baseUrl else {
            if (baseUrl == mainUrl) "$mainUrl/hdporn/$page" else "$baseUrl/$page"
        }

        val document = app.get(url, headers = headers).document
        val items = document.select("section.box.features div.6u section.box.feature")
            .mapNotNull { it.toSearchResult() }

        val hasNext = document.select("ul.actions.pagination a[href*='/hdporn/']").size > 0 ||
                document.select("ul.actions.pagination a[href*='/category/']").size > 0 ||
                document.select("a.next").size > 0 ||
                (items.size > 0 && page < 10)

        return newHomePageResponse(
            list = HomePageList(name = request.name, list = items, isHorizontalImages = true),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='/hdporn/']") ?: return null
        val href = link.attr("href")
        if (href.isBlank()) return null

        var title = this.selectFirst("h3.meta-data-title a")?.text()?.trim()
        if (title.isNullOrBlank()) title = link.attr("title").trim()
        if (title.isNullOrBlank()) title = link.text().trim()
        if (title.isNullOrBlank()) title = "No Title"

        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        var poster: String? = link.selectFirst("img")?.attr("src")
        if (poster.isNullOrBlank()) poster = link.selectFirst("img")?.attr("data-src")
        if (poster.isNullOrBlank()) {
            poster = this.selectFirst("img")?.attr("src")
        }
        if (!poster.isNullOrBlank() && poster.startsWith("//")) {
            poster = "https:$poster"
        }
        poster = fixUrlNull(poster)

        return newMovieSearchResponse(
            formattedTitle,
            LoadUrl(fixUrl(href), poster).toJson(),
            TvType.NSFW
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val document = app.get("$mainUrl/?q=$query&p=$page", headers = headers).document
            val items = document.select("section.box.features div.6u section.box.feature")
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href, headers = headers).document

        var title = document.selectFirst("header > h1")?.text()?.trim()
        if (title.isNullOrBlank()) title = document.selectFirst("h1.title")?.text()?.trim()
        if (title.isNullOrBlank()) title = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
        if (title.isNullOrBlank()) title = "Unknown"

        val formattedTitle = title.split(" ")
            .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }

        var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrBlank()) poster = loadData.posterUrl
        poster = fixUrlNull(poster)

        val plot = document.select("div.description p").text()
            .ifEmpty { document.select("meta[name='description']")?.attr("content").orEmpty() }

        return newMovieLoadResponse(formattedTitle, url, TvType.NSFW, loadData.href) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers).document

        // 1. Try the worker API
        val iframe = document.selectFirst("iframe[src*='mydaddy.cc']")
            ?: document.selectFirst("iframe[src*='/video/']")
            ?: document.selectFirst("iframe[src*='embed']")

        if (iframe != null) {
            val iframeSrc = iframe.attr("src")
            val videoId = Regex("""/video/([^/]+)/""").find(iframeSrc)?.groupValues?.get(1)
            if (!videoId.isNullOrBlank()) {
                try {
                    val configUrl = "https://vidfast.yogeshkumarjamre1.workers.dev/route-config"
                    val configResponse = app.get(configUrl, headers = headers)
                    val configJson = tryParseJson<Map<String, Any>>(configResponse.text)
                    val csrfToken = configJson?.get("csrf_token")?.toString() ?: ""

                    val workerHeaders = mapOf(
                        "User-Agent" to headers["User-Agent"].orEmpty(),
                        "Referer" to "https://vidfast.pro/",
                        "X-CSRF-Token" to csrfToken,
                        "X-Requested-With" to "XMLHttpRequest",
                        "Content-Type" to "application/json"
                    )

                    val generatePayload = mapOf("siteData" to videoId)
                    val generateResponse = app.post(
                        "https://vidfast.yogeshkumarjamre1.workers.dev/generate",
                        data = generatePayload,
                        headers = workerHeaders
                    )
                    val generateText = generateResponse.text
                    val generateJson = tryParseJson<Map<String, Any>>(generateText)
                    val payload = generateJson?.get("data")?.toString()

                    if (!payload.isNullOrBlank()) {
                        val decryptPayload = mapOf("response" to payload)
                        val decryptResponse = app.post(
                            "https://vidfast.yogeshkumarjamre1.workers.dev/decrypt",
                            data = decryptPayload,
                            headers = workerHeaders
                        )
                        val decryptText = decryptResponse.text
                        val decryptJson = tryParseJson<Map<String, Any>>(decryptText)
                        val videoUrl = decryptJson?.get("data")?.toString()

                        if (!videoUrl.isNullOrBlank() && (videoUrl.contains(".mp4") || videoUrl.contains(".m3u8"))) {
                            return loadExtractor(videoUrl, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    // fall through
                }
            }
        }

        // 2. Fallback to altplayer regex
        val docHtml = document.toString()
        val rawUrl = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""").find(docHtml)?.groupValues?.get(1)
        if (!rawUrl.isNullOrBlank()) {
            val href = "https://$rawUrl"
            return loadExtractor(href, subtitleCallback, callback)
        }

        // 3. Direct mp4/m3u8
        val directUrl = Regex("""(https?://[^\s"']+\.(mp4|m3u8))""").find(docHtml)?.groupValues?.get(1)
        if (!directUrl.isNullOrBlank()) {
            return loadExtractor(directUrl, subtitleCallback, callback)
        }

        return false
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 0
        }
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
