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
    override var mainUrl = "https://pervl4.xtremestream.xyz" // fallback
    override val requiresReferer = true
    private val client = OkHttpClient()
    private val TAG = "PerverzijaExtractor"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Set mainUrl dynamically from the input URL
        try {
            val parsed = URL(url)
            mainUrl = "${parsed.protocol}://${parsed.host}"
        } catch (_: Exception) { }

        Log.d(TAG, "Extracting from: $url (mainUrl: $mainUrl)")

        val request = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8")
            .header("Referer", referer.toString())
            .header("User-Agent", USER_AGENT)
            .build()

        val response = client.newCall(request).execute()
        val html = response.body?.string()
        if (html == null) {
            Log.e(TAG, "No HTML response")
            return
        }

        val doc = Jsoup.parse(html)

        // ----- METHOD 1: var video_id script (primary) -----
        val playerScript = doc.selectXpath("//script[contains(text(),'var video_id')]").html()
        if (playerScript.isNotBlank()) {
            val videoId = playerScript.substringAfter("var video_id = `").substringBefore("`;")
            val m3u8LoaderUrl = playerScript.substringAfter("var m3u8_loader_url = `").substringBefore("`;")
            if (videoId.isNotBlank() && m3u8LoaderUrl.isNotBlank()) {
                listOf(1080, 720, 480).forEach { resolution ->
                    callback.invoke(
                        newExtractorLink(
                            name,
                            name,
                            "${m3u8LoaderUrl}/${videoId}&q=${resolution}",
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = resolution
                            this.referer = url
                            this.headers = mapOf("Accept" to "*/*", "Referer" to url, "User-Agent" to USER_AGENT)
                        }
                    )
                }
                Log.d(TAG, "✅ Method 1 succeeded")
                return
            }
        }

        // ----- METHOD 2: <video> / <source> tags & regex -----
        val videoUrls = mutableSetOf<String>()
        doc.select("video source").forEach { source ->
            source.attr("src").takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
        }
        doc.selectFirst("video")?.let {
            it.attr("src").takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
        }
        Regex("""(https?://[^\s"']+\.(mp4|m3u8))""").findAll(html).forEach { match ->
            match.groupValues[1].takeIf { it.isNotBlank() }?.let { videoUrls.add(it) }
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
            Log.d(TAG, "✅ Method 2 succeeded")
            return
        }

        // ----- METHOD 3: JSON config in scripts -----
        val jsonPatterns = listOf(
            Regex(""""file"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""src"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""url"\s*:\s*"([^"]+\.(mp4|m3u8))"""),
            Regex(""""source"\s*:\s*"([^"]+\.(mp4|m3u8))""")
        )
        jsonPatterns.forEach { pattern ->
            pattern.findAll(html).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotBlank()) {
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
                    Log.d(TAG, "✅ Method 3 succeeded")
                    return
                }
            }
        }

        // ----- METHOD 5: Decode base64 scripts (more reliable than guessed endpoints) -----
        Log.d(TAG, "Methods 1-3 failed, trying base64 script decoding...")
        val decodedScripts = mutableListOf<String>()
        doc.select("script[src^=data:text/javascript;base64,]").forEach { script ->
            val base64Data = script.attr("src").substringAfter("base64,")
            try {
                decodedScripts.add(String(Base64.getDecoder().decode(base64Data)))
            } catch (_: Exception) { }
        }
        val combined = decodedScripts.joinToString("\n")
        Regex("""(https?://[^\s"']+\.(m3u8|mp4)[^\s"']*)""").findAll(combined).forEach { match ->
            val videoUrl = match.groupValues[1]
            if (videoUrl.isNotBlank()) {
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
                Log.d(TAG, "✅ Method 5 succeeded")
                return
            }
        }

        // ----- METHOD 6: Official download API (most reliable for problematic videos) -----
        Log.d(TAG, "Base64 decoding failed, trying download API...")
        val downloadButton = doc.selectFirst("button.download-button")
        if (downloadButton != null) {
            val folderId = downloadButton.attr("data-folderid")
            val token = downloadButton.attr("data-token")
            val xtreme = downloadButton.attr("data-xtremestream")
            if (folderId.isNotBlank() && token.isNotBlank() && xtreme.isNotBlank()) {
                try {
                    val encodedToken = URLEncoder.encode(token, "UTF-8")
                    val apiUrl = "https://download.xtremestream.xyz/generateLinkForPlayer" +
                            "?folder=$folderId&xtremestream=$xtreme&token=$encodedToken"
                    val json = """{"folder":"$folderId","xtremestream":"$xtreme"}"""
                    val body = json.toRequestBody("application/json".toMediaType())
                    val apiRequest = Request.Builder()
                        .url(apiUrl)
                        .post(body)
                        .header("Referer", url)
                        .header("User-Agent", USER_AGENT)
                        .header("Origin", "https://$xtreme.xtremestream.xyz")
                        .header("Accept", "application/json")
                        .build()
                    val apiResponse = client.newCall(apiRequest).execute()
                    val responseBody = apiResponse.body?.string()
                    if (apiResponse.isSuccessful && responseBody != null) {
                        val jsonResponse = JSONObject(responseBody)
                        val link = jsonResponse.optString("link")
                        if (link.isNotBlank()) {
                            val directUrl = if (link.startsWith("http")) link else "https://download.xtremestream.xyz$link"
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    name,
                                    directUrl,
                                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE
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
                            Log.d(TAG, "✅ Method 6 (download API) succeeded: $directUrl")
                            return
                        }
                    } else {
                        Log.w(TAG, "Download API returned ${apiResponse.code}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Download API exception: ${e.message}")
                }
            }
        }

        // ----- METHOD 4: Guess from data parameter (last resort) -----
        // We do NOT return from this method – the player will try these links as a fallback.
        Log.d(TAG, "All other methods failed, trying guessed endpoints as a fallback...")
        val dataParam = url.substringAfter("data=").substringBefore("&")
        if (dataParam.isNotBlank()) {
            listOf(
                "$mainUrl/api/video/$dataParam/master.m3u8",
                "$mainUrl/api/manifest/$dataParam",
                "$mainUrl/manifest/$dataParam.m3u8"
            ).forEach { manifestUrl ->
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
            Log.d(TAG, "✅ Method 4 (guessed) added links as fallback")
        }

        Log.w(TAG, "❌ No video link found")
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
