package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object Network {
        private const val TAG = "FamilyPorn"

        // ---------- Cloudflare detection ----------
        private val CF_BLOCKER_PHRASES = listOf(
            "just a moment", "checking your browser",
            "ddos-guard", "attention required",
            "verify you are human", "cloudflare"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            if (response.code == 403 || response.code == 503) return true
            val text = response.text.lowercase()
            return CF_BLOCKER_PHRASES.any { text.contains(it) }
        }

        // ---------- Show the WebView dialog ----------
        private suspend fun showCFDialogIfNeeded(url: String) {
            if (FamilyPornPlugin.cfCookies.contains("cf_clearance")) return
            Log.d(TAG, "Showing CF WebView dialog for $url")
            val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            with(activity) {
                runOnUiThread {
                    val dialog = CloudflareWebViewDialog(
                        context = this,
                        targetUrl = url,
                        onFinished = { success ->
                            if (success) Log.d(TAG, "CF solved, cookies saved")
                            else Log.w(TAG, "CF dialog dismissed without solving")
                        }
                    )
                    dialog.show()
                }
            }
        }

        // ---------- Build headers with saved cookie ----------
        private fun buildHeaders(headers: Map<String, String>? = null): Map<String, String> {
            val base = headers?.toMutableMap() ?: mutableMapOf()
            // If we have a saved cf_clearance, add it to the Cookie header
            if (FamilyPornPlugin.cfCookies.isNotEmpty()) {
                val existingCookie = base["Cookie"] ?: ""
                val fresh = FamilyPornPlugin.cfCookies
                base["Cookie"] = if (existingCookie.isNotEmpty()) {
                    // Merge, avoid duplicates
                    val parts = existingCookie.split(";").map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("cf_clearance=") }
                    (parts + fresh).distinct().joinToString("; ")
                } else {
                    fresh
                }
            }
            // Add a realistic User-Agent if not set
            if (!base.containsKey("User-Agent")) {
                base["User-Agent"] = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"
            }
            return base
        }

        // ---------- Public network functions ----------
        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,   // not used but kept for compatibility
            referer: String? = null
        ): Document {
            val finalHeaders = buildHeaders(headers)
            var response = app.get(url, headers = finalHeaders, cookies = cookies ?: emptyMap(), referer = referer)
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "CF detected on GET $url – showing dialog")
                showCFDialogIfNeeded(url)
                // Retry with saved cookie (now included via buildHeaders)
                val retryHeaders = buildHeaders(headers)
                response = app.get(url, headers = retryHeaders, cookies = cookies ?: emptyMap(), referer = referer)
                // If still blocked, return empty document to avoid crash
                if (isCloudflareBlocked(response)) {
                    Log.e(TAG, "Still blocked after retry for $url")
                    return Document("")
                }
            }
            return response.document
        }

        suspend fun getText(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            val finalHeaders = buildHeaders(headers)
            var response = app.get(url, headers = finalHeaders, cookies = cookies ?: emptyMap(), referer = referer)
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "CF detected on GET text $url – showing dialog")
                showCFDialogIfNeeded(url)
                val retryHeaders = buildHeaders(headers)
                response = app.get(url, headers = retryHeaders, cookies = cookies ?: emptyMap(), referer = referer)
                if (isCloudflareBlocked(response)) {
                    Log.e(TAG, "Still blocked after retry for $url")
                    return ""
                }
            }
            return response.text
        }

        suspend fun postText(
            url: String,
            data: Map<String, String>? = null,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            val finalHeaders = buildHeaders(headers)
            var response = app.post(
                url,
                data = data ?: emptyMap(),
                headers = finalHeaders,
                cookies = cookies ?: emptyMap(),
                referer = referer
            )
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "CF detected on POST $url – showing dialog")
                showCFDialogIfNeeded(url)
                val retryHeaders = buildHeaders(headers)
                response = app.post(
                    url,
                    data = data ?: emptyMap(),
                    headers = retryHeaders,
                    cookies = cookies ?: emptyMap(),
                    referer = referer
                )
                if (isCloudflareBlocked(response)) {
                    Log.e(TAG, "Still blocked after retry for $url")
                    return ""
                }
            }
            return response.text
        }
    }

    // ---------- MainPage, Search, Load, LoadLinks ----------
    // (These stay exactly as they were – they call the Network functions above.)
    // ... your existing code for mainPage, getMainPage, search, load, loadLinks, helpers ...
}
