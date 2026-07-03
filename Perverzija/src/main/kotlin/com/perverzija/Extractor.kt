package com.perverzija

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.util.Base64

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl5.xtremestream.xyz"  // dynamic, updated from page if needed
    override val requiresReferer = true
    private val client = OkHttpClient()

    private val TAG = "PerverzijaExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Log.d(TAG, "===== Starting extraction =====")
        Log.d(TAG, "URL: $url")
        Log.d(TAG, "Referer: $referer")

        // Try extraction with the given URL
        if (tryExtract(url, referer, callback)) {
            Log.d(TAG, "✅ Extraction succeeded with original URL")
            return
        }

        Log.d(TAG, "⚠️ Extraction failed with original URL, trying VideoJS fallback...")

        // Force VideoJS player if not already
        if (!url.contains("player=1")) {
            val fixedUrl = if (url.contains("player=")) {
                url.replace(Regex("[?&]player=\\d+"), "player=1")
            } else {
                if (url.contains("?")) "$url&player=1" else "$url?player=1"
            }
            Log.d(TAG, "🔄 Retrying with VideoJS URL: $fixedUrl")
            if (fixedUrl != url && tryExtract(fixedUrl, referer, callback)) {
                Log.d(TAG, "✅ VideoJS fallback succeeded")
                return
            }
        }
        Log.d(TAG, "❌ All extraction methods failed")
    }

    private suspend fun tryExtract(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "--- tryExtract for: $url ---")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string()
        if (html == null) {
            Log.e(TAG, "❌ No HTML response from $url")
            return false
        }

        Log.d(TAG, "📄 HTML length: ${html.length} chars")
        val doc = Jsoup.parse(html)

        // ------------------------------------------------------------------
        // 1) Decode all base64-encoded scripts and search for video patterns
        // ------------------------------------------------------------------
        val decodedScripts = mutableListOf<String>()
        val base64Scripts = doc.select("script[src^=data:text/javascript;base64,]")
        base64Scripts.forEach { script ->
            val src = script.attr("src")
            val base64Data = src.substringAfter("base64,")
            try {
                val decoded = String(Base64.getDecoder().decode(base64Data))
                decodedScripts.add(decoded)
                Log.d(TAG, "🔓 Decoded base64 script (${decoded.length} chars)")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode base64 script: ${e.message}")
            }
        }

        // Search in decoded scripts for video URLs / tokens
        val combinedScripts = decodedScripts.joinToString("\n")
        val videoUrls = mutableListOf<String>()

        // Patterns to find m3u8 or mp4 URLs
        val urlPatterns = listOf(
            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)"""),
            Regex("""(https?://[^\s"']+\.mp4[^\s"']*)"""),
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex(""""url"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex(""""src"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex(""""video"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex("""video_id\s*=\s*["']([^"']+)""""),
            Regex("""m3u8_loader_url\s*=\s*["']([^"']+)""""),
        )

        urlPatterns.forEach { pattern ->
            pattern.findAll(combinedScripts).forEach { match ->
                val foundUrl = match.groupValues[1]
                if (foundUrl.isNotBlank() && foundUrl !in videoUrls) {
                    videoUrls.add(foundUrl)
                    Log.d(TAG, "🔍 Found video URL in decoded script: $foundUrl")
                }
            }
        }

        // Also search in the plain HTML for additional URLs
        val htmlPatterns = listOf(
            Regex("""(https?://[^\s"']+\.(m3u8|mp4))"""),
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
        )
        htmlPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val foundUrl = match.groupValues[1]
                if (foundUrl.isNotBlank() && foundUrl !in videoUrls) {
                    videoUrls.add(foundUrl)
                    Log.d(TAG, "🔍 Found video URL in HTML: $foundUrl")
                }
            }
        }

        // If we found any video URLs, return them
        if (videoUrls.isNotEmpty()) {
            videoUrls.forEach { videoUrl ->
                val isM3u8 = videoUrl.contains(".m3u8")
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = guessQuality(videoUrl)
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return true
        }

        // ------------------------------------------------------------------
        // 2) Try to use tokens from the download button to construct API URLs
        // ------------------------------------------------------------------
        val downloadButton = doc.selectFirst("button.download-button")
        if (downloadButton != null) {
            val folderId = downloadButton.attr("data-folderid")
            val token = downloadButton.attr("data-token")
            val mdjToken = downloadButton.attr("data-mdjtoken")
            val xtreme = downloadButton.attr("data-xtremestream")
            Log.d(TAG, "📥 Found download button: folderId=$folderId, token=$token, mdjToken=$mdjToken, xtreme=$xtreme")

            if (folderId.isNotBlank() && (token.isNotBlank() || mdjToken.isNotBlank())) {
                // Use the domain from the button or from the URL
                val baseDomain = if (xtreme.isNotBlank()) "https://$xtreme.xtremestream.xyz" else url.substringBefore("/player/")
                val possibleEndpoints = mutableListOf<String>()

                // Common API patterns
                val endpoints = listOf(
                    "/api/video/%s?token=%s",
                    "/api/video/%s?mdjtoken=%s",
                    "/api/video/%s?token=%s&mdjtoken=%s",
                    "/api/video/%s/master.m3u8?token=%s",
                    "/api/video/%s/master.m3u8?mdjtoken=%s",
                    "/api/video/%s/master.m3u8?token=%s&mdjtoken=%s",
                    "/api/video/%s/manifest.m3u8?token=%s",
                    "/api/manifest/%s?token=%s",
                    "/manifest/%s.m3u8?token=%s",
                )

                endpoints.forEach { endpoint ->
                    if (token.isNotBlank() && endpoint.contains("token")) {
                        possibleEndpoints.add(baseDomain + endpoint.format(folderId, token))
                    }
                    if (mdjToken.isNotBlank() && endpoint.contains("mdjtoken")) {
                        possibleEndpoints.add(baseDomain + endpoint.format(folderId, mdjToken))
                    }
                    if (token.isNotBlank() && mdjToken.isNotBlank() && endpoint.contains("token") && endpoint.contains("mdjtoken")) {
                        possibleEndpoints.add(baseDomain + endpoint.format(folderId, token, mdjToken))
                    }
                }

                // Also try with just folderId (no token) as fallback
                possibleEndpoints.add("$baseDomain/api/video/$folderId/master.m3u8")
                possibleEndpoints.add("$baseDomain/api/manifest/$folderId")
                possibleEndpoints.add("$baseDomain/manifest/$folderId.m3u8")

                // Remove duplicates and add as links
                possibleEndpoints.distinct().forEach { manifestUrl ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            manifestUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = 0
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
                return true
            }
        }

        // ------------------------------------------------------------------
        // 3) Fallback: try to guess from the URL parameters
        // ------------------------------------------------------------------
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isNotBlank()) {
            val baseUrl = url.substringBefore("/player/")
            val possibleUrls = listOf(
                "$baseUrl/api/video/$dataParam/master.m3u8",
                "$baseUrl/api/manifest/$dataParam",
                "$baseUrl/manifest/$dataParam.m3u8"
            )
            Log.d(TAG, "🧪 Trying guessed API URLs: $possibleUrls")
            possibleUrls.forEach { manifestUrl ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        manifestUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = 0
                        this.headers = mapOf(
                            "Referer" to url,
                            "User-Agent" to USER_AGENT
                        )
                    }
                )
            }
            return true
        }

        Log.w(TAG, "❌ All extraction methods failed for $url")
        return false
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("1080") -> 1080
            url.contains("720") -> 720
            url.contains("480") -> 480
            url.contains("360") -> 360
            else -> 0
        }
    }
}
