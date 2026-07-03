package com.perverzija

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import org.json.JSONObject

class Perverzija : MainAPI() {
    override var name = "Perverzija"
    override var mainUrl = "https://tube.perverzija.com"
    override val supportedTypes = setOf(TvType.NSFW)

    override val hasDownloadSupport = true
    override val hasMainPage = true

    private val cfInterceptor = CloudflareKiller()

    override val mainPage = mainPageOf(
        "$mainUrl/page/%d/" to "Home",
        "$mainUrl/tag/creampie/page/%d/" to "Creampie",
        "$mainUrl/tag/family-taboo/page/%d/" to "Family Taboo",
        "$mainUrl/tag/milf/page/%d/" to "Milf",
        "$mainUrl/tag/wife/page/%d/" to "Wife",
        "$mainUrl/tag/teen/page/%d/" to "Teen",
        "$mainUrl/featured-scenes/page/%d/?orderby=date" to "Featured",
        "$mainUrl/studio/onlyfans/page/%d/" to "Onlyfans",
        "$mainUrl/studio/brazzers/page/%d/" to "Brazzers",
        "$mainUrl/studio/nubiles/page/%d/" to "Nubiles",
        "$mainUrl/studio/realitykings/page/%d/" to "Reality Kings",
        "$mainUrl/studio/bangbros/page/%d/" to "Bangbros",
        "$mainUrl/studio/naughtyamerica/page/%d/" to "Naughty America"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val document = app.get(request.data.format(page), interceptor = cfInterceptor, timeout = 100L).document
        val home = document.select("div.row div div.post").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(
            list = HomePageList(
                name = request.name, list = home, isHorizontalImages = true
            ), hasNext = true
        )
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("dt a img").attr("src"))
        val title = this.select("dd a").text() ?: return null
        val href = fixUrlNull(this.select("dt a").attr("href")) ?: return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val posterUrl = fixUrlNull(this.select("div.item-thumbnail img").attr("src"))
        val title = this.select("div.item-head a").text() ?: return null
        val href = fixUrlNull(this.select("div.item-head a").attr("href")) ?: return null
        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val url = if (query.contains(" ")) {
            "$mainUrl/page/$page/?s=${query.replace(" ", "+")}&orderby=date"
        } else {
            "$mainUrl/tag/$query/page/$page/"
        }
        val results = app.get(url, interceptor = cfInterceptor).document
            .select("div.row div div.post").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        val hasNext = if (results.isEmpty()) false else true
        return newSearchResponseList(results, hasNext)
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfInterceptor).document
        val poster = document.select("div#featured-img-id img").attr("src")
        val title = document.select("div.title-info h1.light-title.entry-title").text()
        val pTags = document.select("div.item-content p")
        val description = StringBuilder().apply {
            pTags.forEach {
                append(it.text())
            }
        }.toString()
        val tags = document.select("div.item-tax-list div a").map { it.text() }
        val recommendations =
            document.select("div.related-gallery dl.gallery-item").mapNotNull {
                it.toRecommendationResult()
            }
        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
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
        // ---- STEP 1: Try to get direct video URL via the download button ----
        val directUrl = fetchVideoFromDownloadButton(data)
        if (directUrl != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE,
                    quality = guessQuality(directUrl),
                    headers = mapOf("Referer" to data)
                )
            )
            return true
        }

        // ---- STEP 2: Fallback – extract iframe and try built‑in extractor ----
        val document = app.get(data, interceptor = cfInterceptor).document
        val iframeUrl = document.select("div#player-embed iframe").attr("src")
        if (iframeUrl.isBlank()) return false

        if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
            return true
        }

        // ---- STEP 3: Use custom extractor on the iframe ----
        var linkFound = false
        val wrapperCallback: (ExtractorLink) -> Unit = { link ->
            linkFound = true
            callback(link)
        }
        Xtremestream().getUrl(iframeUrl, data, subtitleCallback, wrapperCallback)
        return linkFound
    }

    // ---------- Helper: fetch video URL from the download button ----------
    private suspend fun fetchVideoFromDownloadButton(videoPageUrl: String): String? {
        val doc = app.get(videoPageUrl, interceptor = cfInterceptor).document
        val btn = doc.selectFirst("button.download-button") ?: return null

        val folderid = btn.attr("data-folderid")
        val xtremestream = btn.attr("data-xtremestream")
        val token = btn.attr("data-token")
        val mdjtoken = btn.attr("data-mdjtoken")
        val postId = doc.selectFirst("div#video-toolbar")?.attr("data-post-id") ?: return null

        // List of possible AJAX actions (found in common WordPress download plugins)
        val actions = listOf(
            "download_video",
            "get_video_link",
            "get_download_link",
            "video_download",
            "xtremestream_download"
        )

        for (action in actions) {
            try {
                val response = app.post(
                    url = "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf(
                        "action" to action,
                        "folderid" to folderid,
                        "xtremestream" to xtremestream,
                        "token" to token,
                        "mdjtoken" to mdjtoken,
                        "post_id" to postId
                    ),
                    headers = mapOf(
                        "Referer" to videoPageUrl,
                        "User-Agent" to USER_AGENT
                    )
                )
                val body = response.text
                if (body.isNotBlank()) {
                    // Try to parse JSON
                    val json = JSONObject(body)
                    val url = json.optString("url").takeIf { it.isNotBlank() }
                        ?: json.optString("video_url")
                        ?: json.optString("download_link")
                    if (!url.isNullOrBlank()) return url
                }
            } catch (_: Exception) {
                // Continue to next action
            }
        }
        return null
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") || url.contains("1080p") -> 1080
            url.contains("720") || url.contains("720p") -> 720
            url.contains("480") || url.contains("480p") -> 480
            url.contains("360") || url.contains("360p") -> 360
            else -> 0
        }
    }
}
