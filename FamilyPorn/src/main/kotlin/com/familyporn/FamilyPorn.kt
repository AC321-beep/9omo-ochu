package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class FamilyPorn : MainAPI() {
    override var mainUrl = "https://familypornhd.com"
    override var name = "FamilyPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    companion object Network {
        private const val TAG = "FamilyPorn"

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

        private suspend fun showCFDialogIfNeeded(url: String) {
            if (FamilyPornPlugin.cfCookies.contains("cf_clearance")) return
            Log.d(TAG, "Showing CF WebView dialog for $url")
            val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return
            withContext(Dispatchers.Main) {
                val dialog = CloudflareWebViewDialog(
                    targetUrl = url,
                    onFinished = { success ->
                        if (success) Log.d(TAG, "CF solved, cookies saved")
                        else Log.w(TAG, "CF dialog dismissed without solving")
                    }
                )
                dialog.show(activity.supportFragmentManager, "familyporn_cf_bypass")
            }
        }

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            var response = app.get(
                url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.get(
                    url,
                    headers = headers ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    referer = referer,
                    interceptor = CFBypassInterceptor
                )
            }
            return response.document
        }

        suspend fun getText(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): String {
            var response = app.get(
                url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.get(
                    url,
                    headers = headers ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    referer = referer,
                    interceptor = CFBypassInterceptor
                )
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
            var response = app.post(
                url,
                data = data ?: emptyMap(),
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.post(
                    url,
                    data = data ?: emptyMap(),
                    headers = headers ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    referer = referer,
                    interceptor = CFBypassInterceptor
                )
            }
            return response.text
        }
    }

    // ---------- MainPage, Search, Load, LoadLinks, helpers ----------
    // (unchanged from your working version – they call the above network functions)
    // ... (paste your existing code for these methods)
}
