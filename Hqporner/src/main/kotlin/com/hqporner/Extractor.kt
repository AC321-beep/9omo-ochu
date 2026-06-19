package com.hqporner

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class Extractor : ExtractorApi() {
    override val name = "HqpornerExtractor"
    override val mainUrl = "https://www.mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Load the iframe page (mydaddy.cc video page)
        val document = app.get(url).document
        val html = document.toString()

        // ---------- Method 1: Extract from do_pl() script ----------
        val script = document.selectFirst("script:containsData(do_pl())")?.toString()
        if (script != null) {
            val videoUrls = extractFromScript(script)
            if (videoUrls.isNotEmpty()) {
                videoUrls.forEach { (quality, videoUrl) ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            quality = quality
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
                return
            }
        }

        // ---------- Method 2: Try to find video sources directly ----------
        val videoSources = document.select("video source")
        if (videoSources.isNotEmpty()) {
            videoSources.forEach { source ->
                val src = source.attr("src")
                if (src.isNotBlank()) {
                    val label = source.attr("label")
                    val quality = getQualityFromName(label)
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = src,
                            quality = quality
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
            }
            return
        }

        // ---------- Method 3: Search for JSON with sources ----------
        val jsonScript = document.selectFirst("script:containsData('sources')")?.toString()
        if (jsonScript != null) {
            val sources = extractFromJson(jsonScript)
            if (sources.isNotEmpty()) {
                sources.forEach { (quality, videoUrl) ->
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            quality = quality
                        ) {
                            this.referer = mainUrl
                        }
                    )
                }
                return
            }
        }

        // ---------- Method 4: Fallback – try to find any .mp4 or .m3u8 links ----------
        val mp4Links = Regex("""(https?://[^"'\s]+\.mp4)""").findAll(html)
        if (mp4Links.any()) {
            mp4Links.forEach { match ->
                val link = match.groupValues[1]
                // Try to guess quality from filename (e.g., 1080.mp4)
                val quality = Regex("""/(\d+)\.mp4""").find(link)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Qualities.Unknown.value
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        quality = quality
                    ) {
                        this.referer = mainUrl
                    }
                )
            }
            return
        }

        // If all fail, we can't get links
    }

    private fun extractFromScript(script: String): Map<Int, String> {
        try {
            // Find the obfuscated part – looks like: replaceAll("...", "...") with parts
            val jw = script.substringAfter("replaceAll")
                .substringAfter(",")
                .substringBefore(")")
                .trim()
                .removeSurrounding("\"")

            val parts = jw.split("+").map { it.trim().removeSurrounding("\"") }
            if (parts.size < 3) return emptyMap()

            val (one, _, three) = parts
            val first = Regex("""$one\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1)
                ?.removePrefix("//")
                ?.removeSuffix("/")
                ?: return emptyMap()

            val third = Regex("""$three\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1) ?: return emptyMap()
            val baseUrl = "https://$first/pubs/$third"

            // Find qualities from title attributes
            val qualityRegex = Regex("""title=\\?"(\d+p|4K)""")
            val matches = qualityRegex.findAll(script)
            val qualities = mutableListOf<String>()
            for (match in matches) {
                val q = match.groupValues[1]
                qualities.add(if (q == "4K") "2160" else q.dropLast(1))
            }
            if (qualities.isEmpty()) qualities.add("1080")

            return qualities.associateWith { quality ->
                "$baseUrl/$quality.mp4"
            }
        } catch (e: Exception) {
            return emptyMap()
        }
    }

    private fun extractFromJson(script: String): Map<Int, String> {
        // Look for "sources": [{"file": "https://...", "label": "1080p"}]
        val pattern = Regex("""sources:\s*\[\s*\{.*?"file"\s*:\s*"(.*?)".*?"label"\s*:\s*"(.*?)".*?}""", RegexOption.DOTALL)
        val matches = pattern.findAll(script)
        val result = mutableMapOf<Int, String>()
        matches.forEach { match ->
            val url = match.groupValues[1]
            val label = match.groupValues[2]
            val quality = Regex("""(\d+)p""").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Qualities.Unknown.value
            result[quality] = url
        }
        return result
    }
}
