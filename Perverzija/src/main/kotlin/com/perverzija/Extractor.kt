package com.perverzija

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.util.Base64

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz" // will be overridden
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

        // Dynamically set mainUrl from the input URL's base
        try {
            val parsed = URL(url)
            mainUrl = "${parsed.protocol}://${parsed.host}"
        } catch (e: Exception) {
            // fallback
        }
        Log.d(TAG, "Using mainUrl: $mainUrl")

        if (tryExtract(url, referer, callback)) {
            Log.d(TAG, "✅ Extraction succeeded")
            return
        }

        // Fallback: VideoJS mode
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
            Log.e(TAG, "❌ No HTML response")
            return false
        }

        Log.d(TAG, "📄 HTML length: ${html.length} chars")
        val doc = Jsoup.parse(html)

        // -------------------------------------------------------------
        // 1) Official Download API – most reliable
        // -------------------------------------------------------------
        val downloadButton = doc.selectFirst("button.download-button")
        if (downloadButton != null) {
            val folderId = downloadButton.attr("data-folderid")
            val token = downloadButton.attr("data-token")
            val xtreme = downloadButton.attr("data-xtremestream")

            if (folderId.isNotBlank() && token.isNotBlank() && xtreme.isNotBlank()) {
                Log.d(TAG, "📥 Download button: folder=$folderId, token=$token, xtreme=$xtreme")
                val directUrl = fetchDownloadLink(folderId, token, xtreme, url)
                if (directUrl != null) {
                    Log.d(TAG, "📦 Direct link: $directUrl")
                    val type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            directUrl,
                            type = type
                        ) {
                            this.referer = url
                            this.quality = guessQuality(directUrl)
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to USER_AGENT,
                                "Origin" to "https://$xtreme.xtremestream.xyz"
                            )
                        }
                    )
                    return true
                }
            }
        }

        // -------------------------------------------------------------
        // 2) Decode base64 scripts and search for video URLs
        // -------------------------------------------------------------
        val decodedScripts = mutableListOf<String>()
        doc.select("script[src^=data:text/javascript;base64,]").forEach { script ->
            val base64Data = script.attr("src").substringAfter("base64,")
            try {
                decodedScripts.add(String(Base64.getDecoder().decode(base64Data)))
            } catch (e: Exception) { /* ignore */ }
        }

        val combined = decodedScripts.joinToString("\n")
        val videoUrls = mutableSetOf<String>()

        listOf(
            Regex("""(https?://[^\s"']+\.m3u8[^\s"']*)"""),
            Regex("""(https?://[^\s"']+\.mp4[^\s"']*)"""),
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex(""""url"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex(""""src"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
            Regex("""video_id\s*=\s*["']([^"']+)""""),
            Regex("""m3u8_loader_url\s*=\s*["']([^"']+)""""),
        ).forEach { pattern ->
            pattern.findAll(combined).forEach { match ->
                match.groupValues[1].takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
            }
        }

        // Also plain HTML
        listOf(
            Regex("""(https?://[^\s"']+\.(m3u8|mp4))"""),
            Regex(""""file"\s*:\s*"([^"]+\.(m3u8|mp4))""""),
        ).forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                match.groupValues[1].takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
            }
        }

        if (videoUrls.isNotEmpty()) {
            videoUrls.forEach { videoUrl ->
                callback.invoke(
                    newExtractorLink(
                        name,
                        name,
                        videoUrl,
                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) {
                        this.referer = url
                        this.quality = guessQuality(videoUrl)
                        this.headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
                    }
                )
            }
            return true
        }

        // -------------------------------------------------------------
        // 3) Fallback: guess from URL parameters – use dynamic mainUrl
        // -------------------------------------------------------------
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isNotBlank()) {
            val possibleUrls = listOf(
                "$mainUrl/api/video/$dataParam/master.m3u8",
                "$mainUrl/api/manifest/$dataParam",
                "$mainUrl/manifest/$dataParam.m3u8"
            )
            Log.d(TAG, "🧪 Guessing from mainUrl: $possibleUrls")
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
                        this.headers = mapOf("Referer" to url, "User-Agent" to USER_AGENT)
                    }
                )
            }
            return true
        }

        Log.w(TAG, "❌ No link found")
        return false
    }

    /**
     * Calls the official download API to obtain a direct video URL.
     * Uses the subdomain from `xtreme` (or falls back to mainUrl's host).
     */
    private suspend fun fetchDownloadLink(
        folderId: String,
        token: String,
        xtreme: String,
        referer: String
    ): String? {
        try {
            val encodedToken = URLEncoder.encode(token, "UTF-8")
            val encodedFolder = URLEncoder.encode(folderId, "UTF-8")

            val apiUrl = "https://download.xtremestream.xyz/generateLinkForPlayer" +
                    "?folder=$encodedFolder&xtremestream=$xtreme&token=$encodedToken"

            val json = """{"folder":"$folderId","xtremestream":"$xtreme"}"""
            val body = json.toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(apiUrl)
                .post(body)
                .header("Referer", referer)
                .header("User-Agent", USER_AGENT)
                .header("Origin", "https://$xtreme.xtremestream.xyz")
                .header("Accept", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()
            if (response.isSuccessful && responseBody != null) {
                Log.d(TAG, "API response: $responseBody")
                val jsonResponse = JSONObject(responseBody)
                val link = jsonResponse.optString("link")
                if (link.isNotBlank()) {
                    return if (link.startsWith("http")) link else "https://download.xtremestream.xyz$link"
                }
            } else {
                Log.w(TAG, "API error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download API exception: ${e.message}")
        }
        return null
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
