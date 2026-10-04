package com.roshy

import android.util.Base64
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

    override val mainPage = mainPageOf(
    MainPageData("New", "$mainUrl"),
    MainPageData("English Subs",  "$mainUrl/category/english-sub-7"),
    MainPageData("Decensored",    "$mainUrl/category/decensored-5"),
    MainPageData("Breast Milk",   "$mainUrl/category/breast-milk"),
    MainPageData("Creampie",      "$mainUrl/category/creampie"),
    MainPageData("Dead Drunk",    "$mainUrl/category/dead-drunk"),
    MainPageData("Molester",      "$mainUrl/category/molester"),
    MainPageData("Rape",          "$mainUrl/category/rape")
)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) "${request.data}/" else "${request.data}/page/$page/"
        val document = try { app.get(url).document } catch (e: Exception) {
            Log.e(TAG, "getMainPage failed for $url", e)
            return newHomePageResponse(request.name, emptyList())
        }
        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title") ?: this.selectFirst(".post-title a") ?: return null
        val baseTitle = titleElement.text()
        val href = fixUrl(titleElement.attr("href"))

        val img = this.selectFirst("img.blog-img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src").orEmpty()
        val posterUrl = if (rawPoster.startsWith("http")) fixUrl(rawPoster) else ""

        val tags = this.select(".tag-label").map { it.text() }.joinToString(" | ")
        val displayTitle = if (tags.isNotBlank()) "$baseTitle [$tags]" else baseTitle

        return newMovieSearchResponse(displayTitle, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = try { app.get("$mainUrl/?s=$query").document } catch (e: Exception) {
            Log.e(TAG, "search failed", e); return emptyList()
        }
        return document.select("article.post-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = try { app.get(url).document } catch (e: Exception) {
            Log.e(TAG, "load failed for $url", e); throw e
        }
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Roshy Video"

        val rawPoster = document.selectFirst(".post-featured-image img, .entry-content img")
            ?.let { it.attr("data-src").ifEmpty { it.attr("src") } }.orEmpty()
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

        val document = try { app.get(data).document } catch (e: Exception) {
            Log.e(TAG, "loadLinks: app.get failed", e); return false
        }

        val seen = linkedSetOf<String>()
        var count = 0

        suspend fun emit(url: String, referer: String, source: String) {
            val fixed = fixUrl(url)
            if (fixed.isBlank() || !fixed.startsWith("http")) return
            if (!seen.add(fixed)) {
                Log.e(TAG, "  [skip dup $source] $fixed")
                return
            }
            Log.e(TAG, "  -> loadExtractor via=$source $fixed  referer=$referer")
            try {
                loadExtractor(fixed, referer, subtitleCallback, callback)
                count++
            } catch (e: Exception) {
                Log.e(TAG, "  loadExtractor threw for $fixed", e)
            }
        }

        // Collect from a document using four strategies
        suspend fun processDoc(doc: Document, referer: String, tagPrefix: String) {
            // 1) literal iframes (some pages might still have them)
            val statics = doc.select(
                "div.beeteam368-player-wrapper iframe, " +
                "div.beeteam368-player-wrapper video, " +
                "div.beeteam368-player-wrapper source"
            )
            Log.e(TAG, "  [$tagPrefix] static playerEls=${statics.size}")
            statics.forEach { el ->
                val src = el.attr("src")
                    .ifEmpty { el.attr("data-src") }
                    .ifEmpty { el.attr("data-lazy-src") }
                    .ifEmpty { el.attr("data-litespeed-src") }
                if (src.isNotBlank()) emit(src, referer, "$tagPrefix-static")
            }

            // 2) Yoast / JSON-LD embedUrl
            doc.select("script.yoast-schema-graph, script[type=application/ld+json]").forEach { s ->
                Regex(""""embedUrl"\s*:\s*"([^"]+)"""").findAll(s.data()).forEach { m ->
                    emit(m.groupValues[1].replace("\\/", "/"), referer, "$tagPrefix-yoast")
                }
            }

            // 3) base64 data: scripts contain the actual player iframe HTML
            val b64Scripts = doc.select("script[src^=data:text/javascript;base64,]")
            Log.e(TAG, "  [$tagPrefix] base64 scripts=${b64Scripts.size}")
            b64Scripts.forEach { script ->
                val src = script.attr("src")
                val b64 = src.substringAfter("base64,", "")
                if (b64.isEmpty()) return@forEach

                val decoded = try {
                    String(Base64.decode(b64, Base64.DEFAULT))
                } catch (e: Exception) {
                    Log.e(TAG, "    base64 decode failed", e); return@forEach
                }

                // 3a) "video_url": "<iframe src=\"...\">"
                Regex(""""video_url"\s*:\s*"((?:\\.|[^"\\])*)"""")
                    .findAll(decoded)
                    .forEach { m ->
                        val raw = m.groupValues[1]
                            .replace("\\/", "/")
                            .replace("\\\"", "\"")
                        Regex("""src=["']([^"']+)["']""")
                            .find(raw)
                            ?.groupValues?.get(1)
                            ?.let { emit(it, referer, "$tagPrefix-b64-videourl") }
                    }

                // 3b) any bare m3u8/mp4 in the decoded script
                Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)[^\s"'<>\\]*""")
                    .findAll(decoded)
                    .forEach { m -> emit(m.value, referer, "$tagPrefix-b64-raw") }
            }

            // 4) Any iframe at all (safety net) — but only external hosts
            doc.select("iframe").forEach { el ->
                val src = el.attr("src").ifEmpty { el.attr("data-src") }
                if (src.startsWith("http") && !src.contains("roshy.tv")) {
                    // Skip ad iframes by requiring a plausible video host
                    // (this is belt-and-suspenders; will only add if the above missed)
                    Log.e(TAG, "  [$tagPrefix] loose iframe: $src")
                }
            }
        }

        // Main page
        processDoc(document, data, "main")

        // Mirrors
        val mirrors = document.select("a.btn-p-group-item[href*=ml-url]")
            .mapNotNull { it.attr("href").takeIf(String::isNotBlank)?.let(::fixUrl) }
            .distinct()
        Log.e(TAG, "  mirror count=${mirrors.size}")

        mirrors.forEach { mirrorUrl ->
            try {
                Log.e(TAG, "  fetching mirror: $mirrorUrl")
                val mirrorDoc = app.get(mirrorUrl, referer = data).document
                Log.e(TAG, "    fetched: hash=${mirrorDoc.html().hashCode()} size=${mirrorDoc.html().length}")
                processDoc(mirrorDoc, mirrorUrl, "mirror")
            } catch (e: Exception) {
                Log.e(TAG, "  mirror fetch failed: $mirrorUrl", e)
            }
        }

        Log.e(TAG, "loadLinks END count=$count uniqueUrls=${seen.size}")
        Log.e(TAG, "==================================================")
        return count > 0
    }
}
