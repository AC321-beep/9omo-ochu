package com.familyporn

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class FamilyPorn : MainAPI() {
    // ... your existing properties (mainUrl, name, etc.) ...

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

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            var response = app.get(
                url = url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.get(
                    url = url,
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
                url = url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.get(
                    url = url,
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
                url = url,
                data = data ?: emptyMap(),
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                showCFDialogIfNeeded(url)
                response = app.post(
                    url = url,
                    data = data ?: emptyMap(),
                    headers = headers ?: emptyMap(),
                    cookies = cookies ?: emptyMap(),
                    referer = referer,
                    interceptor = CFBypassInterceptor
                )
            }
            return response.text
        }

        // getCookies removed – not needed; cookies are handled by the interceptor
    }

    // ... the rest of your class (mainPage, getMainPage, search, load, loadLinks, helpers) remains exactly as before ...
}
