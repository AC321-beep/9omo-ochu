package com.fullporner

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.mvvm.logError

class FullPorner : MainAPI() {
    override var mainUrl              = "https://fullporner.com"
    override var name                 = "FullPorner"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val hasDownloadSupport   = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
        "${mainUrl}/home/"                to "Featured",
        "${mainUrl}/category/creampie/"   to "Creampie",
        "${mainUrl}/category/pov/"        to "POV",
        "${mainUrl}/category/milf/"       to "Milf",
        "${mainUrl}/category/amateur/"    to "Amateur",
        "${mainUrl}/category/teen/"       to "Teen",
        "${mainUrl}/category/orgasm/"     to "Orgasm",
        "${mainUrl}/category/threesome/"  to "ThreeSome",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home = document.select("div.video-block div.video-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("div.video-card div.video-card-body div.video-title a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("div.video-card div.video-card-body div.video-title a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.select("div.video-card div.video-card-image a img").attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..15) {
            val document = app.get("${mainUrl}/search?q=${query.replace(" ", "+")}&p=$i").document

            val results = document.select("div.video-block div.video-card").mapNotNull { it.toSearchResult() }

            searchResponse.addAll(results)

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("div.video-block div.single-video-left div.single-video-title h2")?.text()?.trim().toString()

        val iframeUrl = fixUrlNull(document.selectFirst("div.video-block div.single-video-left div.single-video iframe")?.attr("src")) ?: ""

        val iframeDocument = app.get(iframeUrl).document

        val videoID = Regex("""var id = \"(.+?)\"""").find(iframeDocument.html())?.groupValues?.get(1)
        val pornTrexDocument = app.get("https://www.porntrex.com/embed/${videoID}").document
        val matchResult = Regex("""preview_url:\s*'([^']+)'""").find(pornTrexDocument.html())
        val poster = matchResult?.groupValues?.get(1)
        val posterUrl = fixUrlNull("https:$poster")

        val tags = document.select("div.video-block div.single-video-left div.single-video-title p.tag-link span a").map { it.text() }
        val description = document.selectFirst("div.video-block div.single-video-left div.single-video-title h2")?.text()?.trim().toString()
        val actors = document.select("div.video-block div.single-video-left div.single-video-info-content p a").map { it.text() }
        val recommendations = document.select("div.video-block div.video-recommendation div.video-card").mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = posterUrl
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

   override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    val rawIframeSrc = document.selectFirst("div.video-block div.single-video-left div.single-video iframe")
        ?.attr("src") ?: return false
    val iframeUrl = fixUrl(rawIframeSrc)

    val iframeDoc = app.get(iframeUrl, referer = data).document
    val html = iframeDoc.html()

    val reversedId = Regex("""var id\s*=\s*"([^"]+)"""").find(html)?.groupValues?.getOrNull(1)
        ?: run { logError(Exception("FullPorner: Could not find var id")); return false }
    val realId = reversedId.reversed()

    val qualityMaskStr = Regex("""var quality\s*=\s*parseInt\("(\d+)"\)""").find(html)?.groupValues?.getOrNull(1)
    val qualityMask = qualityMaskStr?.toIntOrNull() ?: 4

    val qualities = mutableListOf<Int>()
    if (qualityMask and 1 == 1) qualities.add(360)
    if (qualityMask and 2 == 2) qualities.add(480)
    if (qualityMask and 4 == 4) qualities.add(720)
    if (qualityMask and 8 == 8) qualities.add(1080)

    if (qualities.isEmpty()) {
        logError(Exception("FullPorner: No qualities available (mask=$qualityMask)"))
        return false
    }

    val host = iframeUrl.substringAfter("://").substringBefore("/")
    if (host.isBlank()) return false

    val videoUrls = mutableListOf<Pair<String, String>>()  // url to referer
    for (q in qualities) {
        videoUrls.add("https://$host/vid/$realId/$q" to iframeUrl)
        videoUrls.add("https://$host/vid/$realId/$q/b" to iframeUrl)
    }

    videoUrls.forEach { (videoUrl, referer) ->
        try {
            callback.invoke(
                newExtractorLink(name, name, videoUrl, referer = referer).apply {
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    return true
}
    // Helper to fetch a page and extract video URLs using multiple patterns
    private suspend fun tryExtractFromPage(url: String, referer: String): List<String> {
        return try {
            val doc = app.get(url, referer = referer).document
            val html = doc.html()

            val patterns = listOf(
                """file\s*:\s*["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""",
                """"file"\s*:\s*"(https?://[^"]+\.(?:mp4|m3u8))"""",
                """video_url\s*:\s*['"]([^'"]+)['"]""",
                """src\s*:\s*['"](https?://[^"']+\.(?:mp4|m3u8))['"]""",
                """<source\s+src=["']([^"']+\.(?:mp4|m3u8))["']""",
                """(https?://[^"'\s]+\.(?:mp4|m3u8))""",
            )

            for (pattern in patterns) {
                val regex = Regex(pattern, setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                val matches = regex.findAll(html).map { it.groupValues[1] }.filter { it.isNotBlank() }.toList()
                if (matches.isNotEmpty()) return matches
            }

            emptyList()
        } catch (e: Exception) {
            logError(e)
            emptyList()
        }
    }
}
