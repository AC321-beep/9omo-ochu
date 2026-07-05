package com.familyporn

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

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
            "just a moment", "checking your browser", "ddos-guard",
            "attention required", "verify you are human", "cloudflare",
            "cf-challenge", "cf-browser-verification", "turnstile",
            "challenge", "please wait", "_cf_chl_opt",
            "javascript challenge", "security check"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            // 1. Status code check
            if (response.code == 403 || response.code == 503) {
                Log.d(TAG, "Blocked by status code ${response.code}")
                return true
            }

            val text = response.text
            val lower = text.lowercase()

            // 2. Check for common phrases
            val phraseMatch = CF_BLOCKER_PHRASES.any { lower.contains(it) }
            if (phraseMatch) {
                Log.d(TAG, "Blocked by phrase match: ${lower.take(200)}")
                return true
            }

            // 3. Check if response is HTML and contains challenge indicators
            val isHtml = response.headers["Content-Type"]?.contains("text/html") == true
            if (isHtml) {
                // Check for <title> containing challenge text
                val titleMatch = Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(text)
                if (titleMatch != null) {
                    val title = titleMatch.groupValues[1].lowercase()
                    if (title.contains("just a moment") || title.contains("checking your browser")) {
                        Log.d(TAG, "Blocked by <title>: $title")
                        return true
                    }
                }

                // Check for cf-challenge div or script
                if (text.contains("cf-challenge") || text.contains("_cf_chl_opt")) {
                    Log.d(TAG, "Blocked by cf-challenge elements")
                    return true
                }

                // If page is small and contains a script with 'challenge', likely a challenge
                if (text.length < 50000 && text.contains("<script") && lower.contains("challenge")) {
                    Log.d(TAG, "Blocked: small HTML with challenge script")
                    return true
                }
            }

            // 4. Debug logging – show first 500 chars to identify unknown challenge
            if (text.isNotEmpty() && !text.contains("familypornhd.com")) {
                Log.d(TAG, "Response (first 500 chars): ${text.take(500)}")
            }

            return false
        }

        private suspend fun showCFDialogIfNeeded(url: String): Boolean {
            Log.d(TAG, "Showing CF dialog for $url")
            val activity = com.lagradost.cloudstream3.CommonActivity.activity ?: return false
            return suspendCoroutine { continuation ->
                activity.runOnUiThread {
                    val dialog = CloudflareWebViewDialog(
                        targetUrl = url,
                        onFinished = { success ->
                            if (success) {
                                Log.d(TAG, "Dialog finished with cookie saved")
                            } else {
                                Log.w(TAG, "Dialog dismissed without solving")
                            }
                            continuation.resume(success)
                        }
                    )
                    if (activity is FragmentActivity) {
                        dialog.show(activity.supportFragmentManager, "familyporn_cf_bypass")
                    } else {
                        Log.e(TAG, "Activity is not FragmentActivity")
                        continuation.resume(false)
                    }
                }
            }
        }

        suspend fun getDocument(
            url: String,
            headers: Map<String, String>? = null,
            cookies: Map<String, String>? = null,
            referer: String? = null
        ): Document {
            Log.d(TAG, "getDocument: $url")
            var response = app.get(
                url,
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                Log.d(TAG, "Blocked, attempting to solve")
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    Log.d(TAG, "CF solved, retrying...")
                    response = app.get(
                        url,
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
                    if (isCloudflareBlocked(response)) {
                        Log.e(TAG, "Still blocked after solving")
                    }
                } else {
                    Log.w(TAG, "CF dialog failed or dismissed, continuing without retry")
                }
            } else {
                Log.d(TAG, "Not blocked")
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
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    response = app.get(
                        url,
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
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
            var response = app.post(
                url,
                data = data ?: emptyMap(),
                headers = headers ?: emptyMap(),
                cookies = cookies ?: emptyMap(),
                referer = referer,
                interceptor = CFBypassInterceptor
            )
            if (isCloudflareBlocked(response)) {
                val solved = showCFDialogIfNeeded(url)
                if (solved) {
                    response = app.post(
                        url,
                        data = data ?: emptyMap(),
                        headers = headers ?: emptyMap(),
                        cookies = cookies ?: emptyMap(),
                        referer = referer,
                        interceptor = CFBypassInterceptor
                    )
                }
            }
            return response.text
        }
    }

    // ---------- Main page, search, load, loadLinks, helpers ----------
    // (keep the same as the previous working version – no changes)
}
