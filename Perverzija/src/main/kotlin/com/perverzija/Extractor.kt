package com.perverzija

import android.util.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.USER_AGENT
import com.lagradost.cloudstream3.utils.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup

open class Xtremestream : ExtractorApi() {
    override var name = "Xtremestream"
    override var mainUrl = "https://pervl4.xtremestream.xyz"
    override val requiresReferer = true
    private val client = OkHttpClient()

    // Tag for logcat filtering
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

        // First try with the given URL
        var found = tryExtract(url, referer, callback)
        if (found) {
            Log.d(TAG, "✅ Extraction succeeded with original URL")
            return
        }

        Log.d(TAG, "⚠️ Extraction failed with original URL, trying VideoJS fallback...")

        // If the current URL doesn't already use player=1, try forcing VideoJS
        if (!url.contains("player=1")) {
            val fixedUrl = if (url.contains("player=")) {
                url.replace(Regex("[?&]player=\\d+"), "player=1")
            } else {
                if (url.contains("?")) "$url&player=1" else "$url?player=1"
            }
            Log.d(TAG, "🔄 Retrying with VideoJS URL: $fixedUrl")
            if (fixedUrl != url) {
                val retryFound = tryExtract(fixedUrl, referer, callback)
                if (retryFound) {
                    Log.d(TAG, "✅ VideoJS fallback succeeded")
                } else {
                    Log.d(TAG, "❌ VideoJS fallback also failed")
                }
            }
        } else {
            Log.d(TAG, "ℹ️ URL already uses player=1, but extraction failed anyway")
        }
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

        // ----- Method 1: VideoJS specific pattern (var video_id, var m3u8_loader_url) -----
        val playerScript =
            Jsoup.parse(html).selectXpath("//script[contains(text(),'var video_id')]")
                .html()
        if (playerScript.isNotBlank()) {
            Log.d(TAG, "🔍 Found player script with var video_id")
            val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
            val m3u8LoaderUrl =
                playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")
            Log.d(TAG, "videoId: $videoId")
            Log.d(TAG, "m3u8LoaderUrl: $m3u8LoaderUrl")

            if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                val resolutions = listOf(1080, 720, 480)
                resolutions.forEach { resolution ->
                    val manifestUrl = "${m3u8LoaderUrl}/${videoId}&q=${resolution}"
                    Log.d(TAG, "📦 Adding quality $resolution: $manifestUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            manifestUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = resolution
                            this.referer = url
                            this.headers = mapOf(
                                "Accept" to "*/*",
                                "Referer" to url,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                }
                return true
            } else {
                Log.w(TAG, "⚠️ videoId or m3u8LoaderUrl is blank")
            }
        } else {
            Log.d(TAG, "ℹ️ No 'var video_id' script found")
        }

        // ----- Method 2: Generic – search for direct video URLs in the HTML -----
        val doc = Jsoup.parse(html)
        val videoUrls = mutableListOf<String>()

        val videoSources = doc.select("video source")
        videoSources.forEach { source ->
            val src = source.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        val videoTag = doc.selectFirst("video")
        videoTag?.let {
            val src = it.attr("src")
            if (src.isNotBlank()) videoUrls.add(src)
        }
        Log.d(TAG, "🎬 Found ${videoUrls.size} video URLs from <video>/<source> tags")

        val regex = Regex("""(https?://[^\s"']+\.(mp4|m3u8))""")
        regex.findAll(html).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.isNotBlank() && !videoUrls.contains(videoUrl)) {
                videoUrls.add(videoUrl)
                Log.d(TAG, "🔗 Found additional URL via regex: $videoUrl")
            }
        }

        if (videoUrls.isNotEmpty()) {
            videoUrls.forEach { videoUrl ->
                val isM3u8 = videoUrl.contains(".m3u8")
                Log.d(TAG, "📦 Returning video URL: $videoUrl (m3u8: $isM3u8)")
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

        // ----- Method 3: JSON config inside scripts -----
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""video"\s*:\s*"([^"]+\.(mp4|m3u8))""")
        )
        jsonPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank()) {
                    Log.d(TAG, "🔍 Found video URL in JSON: $videoUrl")
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            videoUrl,
                            type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
                        ) {
                            this.referer = url
                            this.quality = guessQuality(videoUrl)
                            this.headers = mapOf(
                                "Referer" to url,
                                "User-Agent" to USER_AGENT
                            )
                        }
                    )
                    return true
                }
            }
        }

        // ----- Method 4: Guess manifest from data parameter -----
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
