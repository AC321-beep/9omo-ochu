package com.familyporn

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class FamilyPornExtractor : ExtractorApi() {
    override var name = "FamilyPornExtractor"
    override var mainUrl = "https://familypornhd.com"
    override val requiresReferer = true

    private companion object {
        const val FALLBACK_UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    private fun ensureUa(): String {
        if (CFState.userAgent.isBlank()) {
            val seeded = try {
                WebSettings.getDefaultUserAgent(CommonActivity.activity)
            } catch (e: Exception) { FALLBACK_UA }
            CFState.userAgent = seeded.ifBlank { FALLBACK_UA }
        }
        return CFState.userAgent
    }

    // kt_player.js appends "rnd=<Date.now()>" to every /get_file/ fetch.
    // Without it KVS serves the anti-hotlink decoy GIF instead of video.
    private fun appendRnd(rawUrl: String): String {
        val sep = if (rawUrl.contains("?")) "&" else "?"
        return "$rawUrl${sep}rnd=${System.currentTimeMillis()}"
    }

    // Header set for the video fetch. Mirrors the browser exactly:
    // User-Agent, Referer (embed page), Accept, Cookie. No Origin,
    // no Sec-Fetch-* — the browser doesn't send those on a same-origin
    // media load, and KVS rejects the request if they're present.
    private fun streamHeaders(streamUrl: String, embedUrl: String): Map<String, String> {
        val ua = ensureUa()
        val cookie = try {
            CookieManager.getInstance().getCookie(streamUrl)
                ?: CookieManager.getInstance().getCookie(embedUrl)
                ?: ""
        } catch (e: Exception) { "" }

        val headers = mutableMapOf(
            "User-Agent" to ua,
            "Referer" to embedUrl,
            "Accept" to "*/*"
        )
        if (cookie.isNotBlank()) headers["Cookie"] = cookie
        return headers
    }

    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink> {
        val links = mutableListOf<ExtractorLink>()
        getUrl(url, referer, subtitleCallback = {}, callback = { links.add(it) })
        return links
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.isBlank()) return
        ensureUa()

        val host = try { Uri.parse(url).host ?: "" } catch (e: Exception) { "" }
        val isOwnDomain = host == "familypornhd.com" || host.endsWith(".familypornhd.com")

        val isFirePlayerHost =
            isOwnDomain ||
            url.contains("watchstreamhd.com") ||
            url.contains("videostreamingworld.com") ||
            url.contains("bestwish.lol")

        if (isFirePlayerHost) {
            try { fetchFirePlayerContent(url, referer, callback) }
            catch (e: Exception) { /* nothing to emit */ }
        } else {
            try { loadExtractor(url, referer, subtitleCallback, callback) }
            catch (e: Exception) { /* nothing to emit */ }
        }
    }

    private suspend fun fetchFirePlayerContent(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val videoid = url.trimEnd('/').substringAfterLast("/").substringBefore("?")

        try {
            // 1. Fetch embed page (also primes cookies on the host).
            val playerHeaders = mapOf(
                "User-Agent" to ensureUa(),
                "Referer" to (referer ?: "https://familypornhd.com/"),
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
            )
            val iframeResponse = FamilyPornProvider.appGet(url, headers = playerHeaders)
            val iframeHtml = if (iframeResponse.isSuccessful) iframeResponse.text else ""

            // 2. FirePlayer do=getVideo (works on watchstream / vsw / bestwish;
            //    returns 404 on KVS-hosted own-domain embeds).
            val postUrl = "https://$host/player/index.php?data=$videoid&do=getVideo"
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest",
                "Referer" to url,
                "Origin" to "https://$host",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "User-Agent" to ensureUa()
            )
            val apiResponse = FamilyPornProvider.appPost(
                url = postUrl,
                data = mapOf("hash" to videoid, "r" to (referer ?: "")),
                headers = postHeaders
            )
            val responseText = if (apiResponse.isSuccessful) apiResponse.text else ""

            if (apiResponse.code == 200 &&
                responseText.isNotBlank() &&
                !responseText.trim().startsWith("<")
            ) {
                try {
                    val json = AppUtils.parseJson<MasterResponse>(responseText)
                    val streamUrl = json.securedlink
                        ?: json.videosource
                        ?: json.file
                        ?: json.videoSource
                        ?: json.streamingUrl
                    if (!streamUrl.isNullOrBlank()) {
                        val finalUrl = appendRnd(streamUrl)
                        val isM3u8 = finalUrl.contains(".m3u8")
                        callback(
                            newExtractorLink(
                                source = "FirePlayer",
                                name = "FirePlayer",
                                url = finalUrl,
                                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = url
                                this.headers = streamHeaders(finalUrl, url)
                            }
                        )
                        return
                    }
                } catch (e: Exception) { /* fall through to KVS scrape */ }
            }

            // 3. KVS scrape.
            //
            // From DevTools (embed /embed/42):
            //   /get_file/0/E2ZW...mp4/?v-acctoken=...            -> 200 GIF (thumbnail)
            //   /get_file/0/_f6W...mp4/?v-acctoken=...&embed=true -> 302 -> srv1 -> 206 video
            //
            // The discriminator is "embed=true". Non-embed /get_file/ URLs
            // are the thumbnail sprite and must never be emitted as video.
            //
            // KVS lists one URL per stored resolution, smallest first, so
            // numbering them #1..#N preserves ascending quality order.
            val getFileRegex = Regex(
                """["'](https?://[^"']+/get_file/[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )
            val embedVariants = getFileRegex.findAll(iframeHtml)
                .map { it.groupValues[1] }
                .filter { it.contains("&embed=true") }
                .distinct()
                .toList()

            val otherMedia = Regex(
                """["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            ).findAll(iframeHtml)
                .map { it.groupValues[1] }
                .filterNot { it.contains("/get_file/") }
                .filterNot { it.contains("preview", true) }
                .filterNot { it.contains("screenshot", true) }
                .filterNot { it.endsWith(".jpg", true) }
                .filterNot { it.endsWith(".jpeg", true) }
                .filterNot { it.endsWith(".webp", true) }
                .distinct()
                .toList()

            val toEmit = embedVariants + otherMedia
            val multi = toEmit.size > 1
            toEmit.forEachIndexed { index, raw ->
                val finalUrl = appendRnd(raw)
                val isM3u8 = raw.contains(".m3u8")
                // KVS HTML lists smallest first; label with rank so the user
                // can pick. Single-source embeds keep the plain name.
                val label = if (multi) "FirePlayer #${index + 1}" else "FirePlayer"
                callback(
                    newExtractorLink(
                        source = "FirePlayer",
                        name = label,
                        url = finalUrl,
                        type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.headers = streamHeaders(finalUrl, url)
                    }
                )
            }
        } catch (e: Exception) { /* nothing to emit */ }
    }

    data class MasterResponse(
        @JsonProperty("securedLink") val securedlink: String? = null,
        @JsonProperty("videoSource") val videosource: String? = null,
        @JsonProperty("video_source") val videoSource: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("streaming_url") val streamingUrl: String? = null
    )
}
