package com.roshy

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class RoshyProvider : MainAPI() {
    private val TAG = "RoshyDebug"

    override var mainUrl = "https://roshy.tv"
    override var name = "RoshyTv"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.NSFW, TvType.Movie)
    override var lang = "en"

    // Register the custom extractor so it actually gets invoked
    override val extractors = listOf(RoshyExtractor())

    override val mainPage = mainPageOf(
        MainPageData("New Subtitles", "$mainUrl"),
        MainPageData("Decensored", "$mainUrl/category/decensored-5"),
        MainPageData("Big Tits", "$mainUrl/category/big-tits-2"),
        MainPageData("Creampie", "$mainUrl/category/creampie")
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        Log.d(TAG, "getMainPage() page=$page request.name=${request.name} request.data=${request.data}")

        // Automatically handle WordPress pagination routing
        val url = if (page == 1) {
            "${request.data}/"
        } else {
            "${request.data}/page/$page/"
        }

        Log.d(TAG, "getMainPage() fetching url=$url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(TAG, "getMainPage() app.get failed for $url", e)
            return newHomePageResponse(request.name, emptyList())
        }

        val home = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        Log.d(TAG, "getMainPage() parsed items=${home.size}")

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst(".post-listing-title") ?: this.selectFirst(".post-title a")
        val baseTitle = titleElement?.text() ?: return null
        val href = fixUrl(titleElement.attr("href"))

        // Targeted specifically to the blog-img class to bypass SVGs and tracking pixels
        val imgElement = this.selectFirst("img.blog-img")
        val posterUrl = fixUrl(
            imgElement?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: imgElement?.attr("src")
                ?: ""
        )

        // Extract "ENG" and "DC" labels
        val tags = this.select(".tag-label").map { it.text() }.joinToString(" | ")

        // Because `quality` only accepts Enums (HD, SD, CAM), we append custom string tags to the title instead
        val displayTitle = if (tags.isNotBlank()) {
            "$baseTitle [$tags]"
        } else {
            baseTitle
        }

        return newMovieSearchResponse(displayTitle, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        Log.d(TAG, "search() query=$query")
        val document = try {
            app.get("$mainUrl/?s=$query").document
        } catch (e: Exception) {
            Log.e(TAG, "search() app.get failed", e)
            return emptyList()
        }
        val results = document.select("article.post-item").mapNotNull { it.toSearchResult() }
        Log.d(TAG, "search() results=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "load() url=$url")

        val document = try {
            app.get(url).document
        } catch (e: Exception) {
            Log.e(TAG, "load() app.get failed for $url", e)
            throw e
        }

        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Roshy Video"

        val poster = document.selectFirst(".post-featured-image img, .entry-content img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }
        val description = document.selectFirst(".entry-content, .description")?.text()

        Log.d(TAG, "load() parsed title=$title poster=$poster")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster?.let { fixUrl(it) }
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCensored: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "==================================================")
        Log.d(TAG, "loadLinks() START data=$data isCensored=$isCensored")

        val document = try {
            app.get(data).document
        } catch (e: Exception) {
            Log.e(TAG, "loadLinks() app.get() failed for $data", e)
            return false
        }

        Log.d(TAG, "loadLinks() page title=${document.title()}")

        val candidates = document.select("iframe, source, video")
        Log.d(TAG, "loadLinks() candidate elements (iframe, source, video) count=${candidates.size}")

        if (candidates.isEmpty()) {
            Log.w(TAG, "loadLinks() No iframe/source/video found. Logging src-like elements:")

            document.select("[src], [data-src], [data-lazy-src], [data-litespeed-src]").forEach { el ->
                Log.d(
                    TAG,
                    "  src-like: <${el.tagName()}> " +
                        el.attributes().joinToString(" ") { "${it.key}=${it.value}" }
                )
            }

            Log.d(TAG, "loadLinks() script count=${document.select("script").size}")
            document.select("script").forEachIndexed { i, script ->
                val text = script.data()
                if (
                    text.contains("iframe", true) ||
                    text.contains("m3u8", true) ||
                    text.contains(".mp4", true) ||
                    text.contains("player", true)
                ) {
                    Log.d(TAG, "  script[$i] contains player hints: ${text.take(500)}...")
                }
            }

            // Also log inline scripts (the ones that use <script> without src, but with html body)
            document.select("script:not([src])").forEachIndexed { i, script ->
                val html = script.html()
                if (
                    html.contains("iframe", true) ||
                    html.contains("m3u8", true) ||
                    html.contains(".mp4", true) ||
                    html.contains("player", true)
                ) {
                    Log.d(TAG, "  inline-script[$i] hints: ${html.take(500)}...")
                }
            }
        }

        var found = 0

        candidates.forEachIndexed { index, element ->
            Log.d(TAG, "[$index] tag=${element.tagName()} html=${element.outerHtml().take(300)}")

            val src = element.attr("src")
                .ifEmpty { element.attr("data-src") }
                .ifEmpty { element.attr("data-lazy-src") }
                .ifEmpty { element.attr("data-litespeed-src") }

            Log.d(TAG, "[$index] raw src='$src'")

            // fixUrl() automatically converts protocol-relative links (e.g., "//dood.to/...") to "https://dood.to/..."
            val fixedUrl = fixUrl(src)
            Log.d(TAG, "[$index] fixedUrl='$fixedUrl'")

            if (fixedUrl.isNotBlank() && fixedUrl.startsWith("http")) {
                found++
                Log.d(TAG, "[$index] calling loadExtractor for $fixedUrl")

                try {
                    loadExtractor(fixedUrl, data, subtitleCallback, callback)
                    Log.d(TAG, "[$index] loadExtractor returned")
                } catch (e: Exception) {
                    Log.e(TAG, "[$index] loadExtractor threw for $fixedUrl", e)
                }
            } else {
                Log.w(TAG, "[$index] skipping: blank or not http")
            }
        }

        Log.d(TAG, "loadLinks() END found=$found")
        Log.d(TAG, "==================================================")
        return found > 0
    }
}
