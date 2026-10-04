package com.roshy

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class RoshyProvider : MainAPI() {
    private val TAG = "RoshyDebug"

    override var mainUrl = "https://roshy.tv"
    override var name = "RoshyTv"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override var lang = "en"

    // Do NOT override `extractors` — CloudStream's built-in VOE / StreamTape /
    // MixDrop / FileMoon / DoodStream extractors live there. Overriding the
    // list wipes them out and only your custom extractor survives.

    override val mainPage = mainPageOf(
        MainPageData("New Subtitles", "$mainUrl"),
        MainPageData("Decensored",    "$mainUrl/category/decensored-5"),
        MainPageData("Big Tits",      "$mainUrl/category/big-tits-2"),
        MainPageData("Creampie",      "$mainUrl/category/creampie")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) "${request.data}/" else "${request.data}/page/$page/"
        Log.e(TAG, "getMainPage url=$url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed for $url", e)
            return newHomePageResponse(request.name, emptyList())
        }

        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "getMainPage parsed items=${home.size}")
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title")
            ?: this.selectFirst(".post-title a")
            ?: return null
        val baseTitle = titleElement.text()
        val href = fixUrl(titleElement.attr("href"))

        val img = this.selectFirst("img.blog-img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src").orEmpty()

        // Skip "data:image/svg+xml,..." placeholders used by perfmatters-lazy
        val posterUrl = if (rawPoster.startsWith("http")) fixUrl(rawPoster) else ""

        val tags = this.select(".tag-label").map { it.text() }.joinToString(" | ")
        val displayTitle = if (tags.isNotBlank()) "$baseTitle [$tags]" else baseTitle

        return newMovieSearchResponse(displayTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.e(TAG, "search query=$query")
        val document = try {
            app.get("$mainUrl/?s=$query").document
        } catch (e: Exception) {
            Log.e(TAG, "search failed", e)
            return emptyList()
        }
        val results = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        Log.e(TAG, "search results=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        Log.e(TAG, "load url=$url")
        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(TAG, "load failed for $url", e)
            throw e
        }

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Roshy Video"

        val rawPoster = document
            .selectFirst(".post-featured-image img, .entry-content img")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            .orEmpty()

        val poster = if (rawPoster.startsWith("http")) fixUrl(rawPoster) else null
        val description = document.selectFirst(".entry-content, .description")?.text()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCensored: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.e(TAG, "==================================================")
        Log.e(TAG, "loadLinks START data=$data")

        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks: app.get failed", e)
            return false
        }

        val seen = linkedSetOf<String>()
        var count = 0

        suspend fun processDoc(doc: Document, referer: String) {
            val playerEls = doc.select(
                "div.beeteam368-player-wrapper iframe, " +
                "div.beeteam368-player-wrapper video, " +
                "div.beeteam368-player-wrapper source"
            )
            Log.e(TAG, "  processDoc referer=$referer playerEls=${playerEls.size}")

            playerEls.forEachIndexed { i, el ->
                val src = el.attr("src")
                    .ifEmpty { el.attr("data-src") }
                    .ifEmpty { el.attr("data-lazy-src") }
                    .ifEmpty { el.attr("data-litespeed-src") }

                val url = fixUrl(src)
                Log.e(TAG, "  player[$i] <${el.tagName()}> src='$src' fixed='$url'")

                if (url.isNotBlank() && url.startsWith("http") && seen.add(url)) {
                    Log.e(TAG, "  -> loadExtractor($url) referer=$referer")
                    try {
                        loadExtractor(url, referer, subtitleCallback, callback)
                        count++
                        Log.e(TAG, "  loadExtractor returned, count=$count")
                    } catch (e: Exception) {
                        Log.e(TAG, "  loadExtractor threw for $url", e)
                    }
                }
            }
        }

        // 1) main page
        processDoc(document, data)

        // 2) Yoast JSON-LD embedUrl
        document.select("script.yoast-schema-graph, script[type=application/ld+json]").forEach { s ->
            Regex(""""embedUrl"\s*:\s*"([^"]+)"""")
                .findAll(s.data())
                .forEach { m ->
                    val url = m.groupValues[1].replace("\\/", "/")
                    Log.e(TAG, "  yoast embedUrl=$url seen=${!seen.contains(url)}")
                    if (seen.add(url)) {
                        Log.e(TAG, "  -> loadExtractor (yoast) $url")
                        try {
                            loadExtractor(url, data, subtitleCallback, callback)
                            count++
                        } catch (e: Exception) {
                            Log.e(TAG, "  loadExtractor (yoast) threw for $url", e)
                        }
                    }
                }
        }

        // 3) mirrors — with diagnostic hash + iframe dump
        val mirrors = document.select("a.btn-p-group-item[href*=ml-url]")
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank)?.let(::fixUrl) }
            .distinct()

        Log.e(TAG, "  mirror count=${mirrors.size}")

        mirrors.forEach { mirrorUrl ->
            try {
                Log.e(TAG, "  Fetching mirror: $mirrorUrl")
                val mirrorDoc = app.get(mirrorUrl, referer = data).document

                val bodyHash = mirrorDoc.html().hashCode()
                val bodySize = mirrorDoc.html().length
                Log.e(TAG, "    response hash=$bodyHash size=$bodySize")

                // Dump every iframe (not just the scoped one) so we see what's really there
                mirrorDoc.select("iframe").forEachIndexed { i, el ->
                    Log.e(TAG, "    iframe[$i] src='${el.attr("src")}' data-src='${el.attr("data-src")}'")
                }

                processDoc(mirrorDoc, mirrorUrl)
            } catch (e: Exception) {
                Log.e(TAG, "  mirror fetch failed: $mirrorUrl", e)
            }
        }

        Log.e(TAG, "loadLinks END count=$count")
        Log.e(TAG, "==================================================")
        return count > 0
    }
}
