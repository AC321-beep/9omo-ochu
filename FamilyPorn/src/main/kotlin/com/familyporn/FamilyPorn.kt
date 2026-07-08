package com.familyporn

import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object Network {
        private const val TAG = "FamilyPorn"
        
        // Prevents multiple network calls from opening the CF dialog simultaneously
        private val cfMutex = Mutex()

        private val CF_BLOCKER_PHRASES = listOf(
            "just a moment", "checking your browser", "ddos-guard",
            "attention required", "verify you are human", "cloudflare",
            "cf-challenge", "cf-browser-verification", "turnstile",
            "challenge", "please wait", "_cf_chl_opt",
            "javascript challenge", "security check", "browser check"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val text = response.text.lowercase()
            return CF_BLOCKER_PHRASES.any { text.contains(it) }
        }

        private suspend fun showCFDialogIfNeeded(url: String): Boolean =
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { continuation ->
                    val activity = com.lagradost.cloudstream3.CommonActivity.activity as? AppCompatActivity
                    if (activity == null || activity.isFinishing || activity.isDestroyed) {
                        continuation.resume(false)
                        return@suspendCancellableCoroutine
                    }
                    var resumed = false
                    fun safeResume(success: Boolean) {
                        if (!resumed) {
                            resumed = true
                            continuation.resume(success)
                        }
                    }
                    val dialog = CloudflareWebViewDialog(
                        targetUrl = url,
                        onFinished = { success -> safeResume(success) }
                    )
                    continuation.invokeOnCancellation {
                        activity.runOnUiThread { runCatching { dialog.dismissAllowingStateLoss() } }
                    }
                    dialog.show(activity.supportFragmentManager, "familyporn_cf_bypass_auto")
                }
            }

        suspend fun appGet(url: String, headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
            
            if (isCloudflareBlocked(response)) {
                // Lock thread so only ONE network request handles the bypass
                cfMutex.withLock {
                    // Pre-flight check: Did another coroutine just solve it while we were waiting?
                    val retryCheck = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) {
                        return retryCheck
                    }

                    val solved = showCFDialogIfNeeded(url)
                    if (solved) {
                        delay(1500)
                        return app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                    }
                }
            }
            return response
        }

        suspend fun appPost(url: String, data: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap()): com.lagradost.nicehttp.NiceResponse {
            var response = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
            if (isCloudflareBlocked(response)) {
                cfMutex.withLock {
                    val retryCheck = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) return retryCheck

                    val solved = showCFDialogIfNeeded(url)
                    if (solved) {
                        delay(1500)
                        return app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    }
                }
            }
            return response
        }

        // ... getDocument, getText, postText wrappers remain exactly the same
    }

    // ... mainPage and search remain exactly the same

    override suspend fun load(url: String): LoadResponse {
        val document = getDocument(url)

        val title = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: "Unknown Title"
        val description = document.selectFirst("meta[property=og:description]")?.attr("content") ?: ""
        val tags = document.select("p.entry-tags a").map { it.text().lowercase() }.take(5)

        var posterUrl = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.select("img").firstOrNull { it.attr("src").contains("familypornhd.com") }?.attr("src")

        val recommendations = document.select("aside.g1-related-entries div.g1-collection li")
            .mapNotNull { it.toRecommendationResult() }

        return newMovieLoadResponse(title, url, type = TvType.NSFW, data = url) {
            this.posterUrl = fixUrlNull(posterUrl)
            
            // 🔥 CRITICAL: Cloudstream's image downloader needs BOTH the cookie and the exact User-Agent
            this.posterHeaders = mapOf(
                "Referer" to mainUrl,
                "Cookie" to FamilyPornPlugin.cfCookies,
                "User-Agent" to FamilyPornPlugin.cfUserAgent
            ).filterValues { it.isNotBlank() }
            
            this.plot = description
            this.tags = tags
            this.recommendations = recommendations
        }
    }
    
    // ... loadLinks remains exactly the same
}
