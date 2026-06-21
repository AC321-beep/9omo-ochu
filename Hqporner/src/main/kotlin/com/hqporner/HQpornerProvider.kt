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

    // The exact same format as your working sample
    override val mainPage = mainPageOf(
        mainUrl to "Recent",
        "$mainUrl/category/creampie" to "Creampie",
        "$mainUrl/category/milf" to "Milf",
        "$mainUrl/category/teen-porn" to "Teen",
        "$mainUrl/category/ebony" to "Ebony",
        "$mainUrl/category/pov" to "POV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Exactly like the sample: just fetch the URL and parse
        val document = app.get(request.data).document
        val home = document.select("div.box.page-content div.row section").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = true
            ),
            hasNext = true   // as in your sample
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("h3 a") ?: return null
        val titleRaw = link.text().trim()
        val title = if (titleRaw.isNotEmpty()) {
            titleRaw.split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        } else {
            "No Title"
        }

        val href = fixUrl(link.attr("href"))
        val poster = fixUrlNull(this.select("img").attr("src"))

        return newMovieSearchResponse(title, LoadUrl(href, poster).toJson(), TvType.NSFW) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        for (page in 1..2) {
            val document = app.get("$mainUrl/?q=$query&p=$page").document
            val items = document.select("div.box.page-content div.row section")
                .mapNotNull { it.toSearchResult() }
            results.addAll(items)
            if (items.isEmpty()) break
        }
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val loadData = tryParseJson<LoadUrl>(url) ?: return null
        val document = app.get(loadData.href).document
        val titleRaw = document.selectFirst("header > h1")?.text()?.trim() ?: ""
        val title = if (titleRaw.isNotEmpty()) {
            titleRaw.split(" ")
                .joinToString(" ") { it.replaceFirstChar { char -> char.uppercase() } }
        } else {
            "Unknown"
        }
        val poster = loadData.posterUrl
        val plot = "HQPorner"
        return newMovieLoadResponse(title, url, TvType.NSFW, loadData.href) {
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
        val document = app.get(data).document
        val doc = document.toString()
        val rawUrl = Regex("""url: '/blocks/altplayer\.php\?i=//(.*?)',""").find(doc)?.groupValues?.get(1) ?: ""
        if (rawUrl.isBlank()) return false
        val href = "https://$rawUrl"
        loadExtractor(href, subtitleCallback, callback)
        return true
    }

    data class LoadUrl(
        val href: String,
        val posterUrl: String?
    )
}
