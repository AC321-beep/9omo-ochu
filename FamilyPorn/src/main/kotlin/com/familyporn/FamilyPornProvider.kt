package com.familyporn

import android.annotation.SuppressLint
import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object CFState {
    var userAgent: String = ""
}

class CFInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()
        val builder = original.newBuilder()

        val defaultUa = try {
            WebSettings.getDefaultUserAgent(CommonActivity.activity)
        } catch (e: Exception) { "Mozilla/5.0" }
        val ua = CFState.userAgent.takeIf { it.isNotBlank() } ?: defaultUa
        builder.header("User-Agent", ua)

        // Preserve X-Requested-With if the caller set it (FirePlayer
        // do=getVideo requires it to return JSON, not HTML).
        if (original.header("X-Requested-With") == null) {
            builder.removeHeader("X-Requested-With")
        }

        val cookies = CookieManager.getInstance().getCookie(original.url.toString())
        if (!cookies.isNullOrEmpty()) builder.header("Cookie", cookies)

        // Only fill defaults; never clobber caller-supplied values.
        if (original.header("Accept") == null)
            builder.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8")
        if (original.header("Accept-Language") == null)
            builder.header("Accept-Language", "en-US,en;q=0.5")
        if (original.header("Connection") == null)
            builder.header("Connection", "keep-alive")
        if (original.header("Upgrade-Insecure-Requests") == null)
            builder.header("Upgrade-Insecure-Requests", "1")
        if (original.header("Sec-Fetch-Dest") == null)
            builder.header("Sec-Fetch-Dest", "document")
        if (original.header("Sec-Fetch-Mode") == null)
            builder.header("Sec-Fetch-Mode", "navigate")
        if (original.header("Sec-Fetch-Site") == null)
            builder.header("Sec-Fetch-Site", "same-origin")

        return chain.proceed(builder.build())
    }
}

class FamilyPornProvider : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        val cfInterceptor = CFInterceptor()

        /**
         * Stage 1: Attempt silent off-screen resolution.
         */
        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun attemptSilentResolution(activity: android.app.Activity, targetUrl: String): Boolean = withContext(Dispatchers.Main) {
            val decor = activity.window?.decorView as? ViewGroup ?: return@withContext false

            suspendCancellableCoroutine { cont ->
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())
                var checkRunnable: Runnable? = null
                var timeoutRunnable: Runnable? = null

                val webView = WebView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        activity.resources.displayMetrics.widthPixels,
                        activity.resources.displayMetrics.heightPixels
                    )
                    translationX = 20000f // Off-screen rendering
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    if (CFState.userAgent.isBlank()) {
                        CFState.userAgent = settings.userAgentString
                    } else {
                        settings.userAgentString = CFState.userAgent
                    }

                    webViewClient = object : WebViewClient() {
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                            h?.proceed()
                        }
                    }
                }

                fun cleanup(success: Boolean) {
                    if (!done.compareAndSet(false, true)) return
                    checkRunnable?.let { handler.removeCallbacks(it) }
                    timeoutRunnable?.let { handler.removeCallbacks(it) }
                    runCatching {
                        decor.removeView(webView)
                        webView.stopLoading()
                        webView.loadUrl("about:blank")
                        webView.destroy()
                    }
                    if (success) CookieManager.getInstance().flush()
                    if (cont.isActive) cont.resume(success)
                }

                cont.invokeOnCancellation { cleanup(false) }

                checkRunnable = object : Runnable {
                    override fun run() {
                        if (done.get()) return
                        val cookies = CookieManager.getInstance().getCookie(targetUrl) ?: ""
                        val title = webView.title?.lowercase() ?: ""
                        val isChallenge = listOf(
                            "just a moment", "attention required",
                            "security verification", "cloudflare"
                        ).any { title.contains(it) }

                        if (!isChallenge && cookies.contains("cf_clearance")) {
                            cleanup(true)
                            return
                        }
                        handler.postDelayed(this, 500L)
                    }
                }

                timeoutRunnable = Runnable { cleanup(false) }

                decor.addView(webView)
                webView.loadUrl(targetUrl)
                handler.postDelayed(checkRunnable!!, 800L)
                handler.postDelayed(timeoutRunnable!!, 4500L) // Allow 4.5s for silent background pass
            }
        }

        /**
         * Stage 2: Fallback interactive fullscreen dialog.
         */
        @SuppressLint("SetJavaScriptEnabled")
        private suspend fun attemptInteractiveResolution(activity: android.app.Activity, targetUrl: String): Boolean = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
                    setCancelable(false)
                    setCanceledOnTouchOutside(false)
                }
                val done = AtomicBoolean(false)
                val handler = Handler(Looper.getMainLooper())

                val layout = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.parseColor("#1A1A1A"))
                }

                val header = TextView(activity).apply {
                    text = "Solving Cloudflare Anti-Bot... Please Wait"
                    setTextColor(Color.WHITE)
                    textSize = 16f
                    setPadding(32, 32, 32, 32)
                }
                layout.addView(header)

                val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 10)
                }
                layout.addView(progressBar)

                fun finish(success: Boolean) {
                    if (!done.compareAndSet(false, true)) return
                    CookieManager.getInstance().flush()
                    runCatching { dialog.dismiss() }
                    if (cont.isActive) cont.resume(success)
                }

                val webView = WebView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        databaseEnabled = true
                        useWideViewPort = true
                        loadWithOverviewMode = true
                        cacheMode = WebSettings.LOAD_DEFAULT
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                    if (CFState.userAgent.isBlank()) {
                        CFState.userAgent = settings.userAgentString
                    } else {
                        settings.userAgentString = CFState.userAgent
                    }

                    fun checkSuccess(view: WebView?) {
                        if (done.get()) return
                        val title = view?.title?.lowercase() ?: ""
                        val cookies = CookieManager.getInstance().getCookie(targetUrl) ?: ""

                        val isChallenge = listOf(
                            "just a moment", "attention required",
                            "security verification", "cloudflare"
                        ).any { title.contains(it) }

                        if (!isChallenge && cookies.contains("cf_clearance")) {
                            header.text = "Success! Resuming..."
                            header.setTextColor(Color.GREEN)
                            handler.postDelayed({ finish(true) }, 1000)
                        }
                    }

                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progressBar.progress = newProgress
                            progressBar.visibility = if (newProgress == 100) View.GONE else View.VISIBLE
                            if (newProgress == 100) checkSuccess(view)
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        @SuppressLint("WebViewClientOnReceivedSslError")
                        override fun onReceivedSslError(view: WebView?, h: SslErrorHandler?, error: SslError?) {
                            h?.proceed()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            checkSuccess(view)
                        }
                    }
                }

                layout.addView(webView)
                dialog.setContentView(layout)

                dialog.setOnDismissListener {
                    if (!done.get()) finish(false)
                }

                dialog.show()
                webView.loadUrl(targetUrl)

                handler.postDelayed({
                    if (!done.get()) finish(false)
                }, 30_000L) // 30s max for interactive pass
            }
        }

        suspend fun resolveCloudflare(url: String): Boolean {
            val activity = CommonActivity.activity ?: return false
            if (activity.isFinishing || activity.isDestroyed) return false

            // 1. Attempt Silent Bypass
            if (attemptSilentResolution(activity, url)) return true

            // 2. Fallback to Fullscreen Checkbox UI
            return attemptInteractiveResolution(activity, url)
        }

        suspend fun appGet(
            url: String,
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            var response = app.get(url, headers = headers, interceptor = cfInterceptor)
            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) &&
                    (text.contains("cloudflare") || text.contains("just a moment"))
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"
                if (resolveCloudflare(safeHostUrl)) {
                    response = app.get(url, headers = headers, interceptor = cfInterceptor)
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun appPost(
            url: String,
            data: Map<String, String> = emptyMap(),
            headers: Map<String, String> = emptyMap()
        ): com.lagradost.nicehttp.NiceResponse {
            var response = app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
            val text = response.text.lowercase()
            val isChallenge = response.code in listOf(403, 503) &&
                    (text.contains("cloudflare") || text.contains("just a moment"))
            if (isChallenge) {
                val uri = Uri.parse(url)
                val safeHostUrl = "${uri.scheme}://${uri.host}/"
                if (resolveCloudflare(safeHostUrl)) {
                    response = app.post(url, data = data, headers = headers, interceptor = cfInterceptor)
                } else {
                    throw Error("Cloudflare bypass failed or cancelled.")
                }
            }
            return response
        }

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).document
        }
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "All Porn Videos",
        "$mainUrl/tag/milf/" to "Milf",
        "$mainUrl/tag/creampie/" to "Creampie",
        "$mainUrl/tag/ebony/" to "Ebony",
        "$mainUrl/tag/athletic/" to "Athletic"
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

        val recommendations = document.select(
            "aside.g1-related-entries li.g1-collection-item, aside.g1-more-from li.g1-collection-item"
        ).mapNotNull { it.toSearchResult() }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = fixUrlNull(posterUrl)

            val cookies = CookieManager.getInstance().getCookie(url) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() }
                ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) }
                catch (e: Exception) { "Mozilla/5.0" }

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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = try { getDocument(data) } catch (e: Exception) { return false }

        var iframeSrc = document.selectFirst(
            "div.embed-container iframe, div.video-wrapper iframe, " +
                    "iframe[src*='watchstream'], iframe[src*='videostreamingworld'], " +
                    "iframe[src*='bestwish']"
        )?.attr("src")

        if (iframeSrc.isNullOrBlank()) {
            iframeSrc = document.select("iframe")
                .mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("http") }
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
                if (match != null) { iframeSrc = match.groupValues[1]; break }
            }
        }

        if (iframeSrc.isNullOrBlank()) return false
        iframeSrc = fixUrl(iframeSrc)

        val iframeHost = try { Uri.parse(iframeSrc).host ?: "" } catch (e: Exception) { "" }
        val isOwnDomain = iframeHost == "familypornhd.com" || iframeHost.endsWith(".familypornhd.com")

        var emitted = false
        val trackingCallback: (ExtractorLink) -> Unit = { link ->
            emitted = true
            callback(link)
        }

        when {
            iframeSrc.contains(".m3u8") || iframeSrc.contains(".mp4") -> {
                val isM3u8 = iframeSrc.contains(".m3u8")
                trackingCallback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = iframeSrc,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) { this.referer = data }
                )
            }

            isOwnDomain ||
            iframeSrc.contains("watchstreamhd") ||
            iframeSrc.contains("videostreamingworld") ||
            iframeSrc.contains("bestwish") -> {
                try {
                    FamilyPornExtractor().getUrl(iframeSrc, data, subtitleCallback, trackingCallback)
                } catch (e: Exception) { /* nothing emitted */ }
            }

            else -> {
                try {
                    loadExtractor(iframeSrc, data, subtitleCallback, trackingCallback)
                } catch (e: Exception) { /* nothing emitted */ }
            }
        }

        return emitted
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("h3.entry-title a")
            ?: this.selectFirst("article a")
            ?: return null
        val title = anchor.text().takeIf { it.isNotBlank() }
            ?: anchor.attr("title").takeIf { it.isNotBlank() }
            ?: return null
        val rawHref = anchor.attr("href")
        if (rawHref.isNullOrBlank()) return null
        val href = fixUrl(rawHref)
        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            val cookies = CookieManager.getInstance().getCookie(mainUrl) ?: ""
            val ua = CFState.userAgent.takeIf { it.isNotBlank() }
                ?: try { WebSettings.getDefaultUserAgent(CommonActivity.activity) }
                catch (e: Exception) { "Mozilla/5.0" }

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
