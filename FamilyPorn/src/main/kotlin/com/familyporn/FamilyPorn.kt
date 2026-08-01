package com.familyporn

import android.net.Uri
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
        private val cfMutex = Mutex()
        private var lastBypassTime = 0L // Cooldown timer

        private val CF_BLOCKER_PHRASES = listOf(
            "just a moment", "checking your browser", "ddos-guard",
            "attention required", "verify you are human", "cloudflare",
            "cf-challenge", "cf-browser-verification", "turnstile",
            "challenge", "please wait", "_cf_chl_opt",
            "javascript challenge", "security check", "browser check",
            "one more step", "enable javascript"
        )

        private fun isCloudflareBlocked(response: com.lagradost.nicehttp.NiceResponse): Boolean {
            // FIX: Ensure it's ACTUALLY Cloudflare and not just a dead link returning 403
            val isErrorCode = response.code == 403 || response.code == 503
            val isCfServer = response.headers.values("server").any { it.contains("cloudflare", true) } 
                             || response.headers.values("cf-ray").isNotEmpty()
            
            val text = response.text.lowercase()
            val hasText = CF_BLOCKER_PHRASES.any { text.contains(it) }
            
            return (isErrorCode && isCfServer) || hasText
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
                            lastBypassTime = System.currentTimeMillis() // Reset cooldown
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
                cfMutex.withLock {
                    // Prevent spamming the dialog. If bypassed in the last 15 seconds, just return.
                    if (System.currentTimeMillis() - lastBypassTime < 15000) return response 

                    val retryCheck = app.get(url, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) return retryCheck

                    val solved = showCFDialogIfNeeded(url)
                    if (solved) {
                        delay(2500)
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
                    if (System.currentTimeMillis() - lastBypassTime < 15000) return response

                    val retryCheck = app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    if (!isCloudflareBlocked(retryCheck)) return retryCheck

                    val uri = Uri.parse(url)
                    val safeGetUrl = "${uri.scheme}://${uri.host}/"
                    val solved = showCFDialogIfNeeded(safeGetUrl)
                    
                    if (solved) {
                        delay(2500)
                        return app.post(url, data = data, headers = headers, interceptor = CFBypassInterceptor)
                    }
                }
            }
            return response
        }

        suspend fun getDocument(url: String, headers: Map<String, String>? = null, referer: String? = null): Document {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).document
        }

        suspend fun getText(url: String, headers: Map<String, String>? = null, referer: String? = null): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appGet(url, finalHeaders).text
        }

        suspend fun postText(url: String, data: Map<String, String>? = null, headers: Map<String, String>? = null, referer: String? = null): String {
            val finalHeaders = headers?.toMutableMap() ?: mutableMapOf()
            referer?.let { finalHeaders["Referer"] = it }
            return appPost(url, data ?: emptyMap(), finalHeaders).text
        }
    }
    
    // ... KEEP THE REST OF YOUR `FamilyPorn` CLASS EXACTLY THE SAME FROM HERE DOWN ...
    // (mainPage, getMainPage, search, load, loadLinks)
}
