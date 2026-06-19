package com.hqporner

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink

class Extractor : ExtractorApi() {
    override val name = "Mydaddy"
    override val mainUrl = "https://www.mydaddy.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document
        val html = document.toString()

        // ---------- Method 1: Extract from do_pl() script ----------
        val script = document.selectFirst("script:containsData(do_pl())")?.toString()
        var linksFound = false
        if (script != null) {
            val jw = script.substringAfter("replaceAll").substringAfter(",").substringBefore(")").trim().removeSurrounding("\"")
            val parts = jw.split("+").map { it.trim().removeSurrounding("\"") }
            if (parts.size >= 3) {
                val (one, _, three) = parts
                val first = Regex("""$one\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1)
                        ?.removePrefix("//")?.removeSuffix("/") ?: ""
                val third = Regex("""$three\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1) ?: ""
                if (first.isNotEmpty() && third.isNotEmpty()) {
                    val baseUrl = "https://$first/pubs/$third"

                    // Find qualities
                    val regex = Regex("""title=\\"(\d+p|4K)""")
                    val matches = regex.findAll(script)
                    val qualities = mutableListOf<String>()
                    for (match in matches) {
                        val quality = match.groupValues[1]
                        qualities.add(if (quality == "4K") "2160" else quality.dropLast(1))
                    }
                    // If no qualities found, add default
                    if (qualities.isEmpty()) {
                        qualities.add("1080")
                    }

                    for (quality in qualities) {
                        val videoUrl = "$baseUrl/$quality.mp4"
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl
                            ) {
                                this.referer = ""
                                this.quality = getQualityFromName(quality)
                            }
                        )
                        linksFound = true
                    }
                }
            }
        }

        // ---------- Method 2: Fallback to <source> tags (from altplayer page) ----------
        if (!linksFound) {
            val sources = document.select("source")
            if (sources.isNotEmpty()) {
                sources.forEach { source ->
                    val src = source.attr("src")
                    if (src.isNotBlank()) {
                        // Try to get quality from filename or default
                        val quality = Regex("""/(\d+)\.mp4""").find(src)?.groupValues?.get(1)?.toIntOrNull()
                            ?: Qualities.Unknown.value
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = src
                            ) {
                                this.referer = ""
                                this.quality = quality
                            }
                        )
                        linksFound = true
                    }
                }
            }
        }

        // ---------- Method 3: Last resort – find any .mp4 URL ----------
        if (!linksFound) {
            val mp4Regex = Regex("""(https?://[^"'\s]+\.mp4)""")
            val matches = mp4Regex.findAll(html)
            matches.forEach { match ->
                val url = match.groupValues[1]
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = url
                    ) {
                        this.referer = ""
                        this.quality = getQualityFromName("unknown")
                    }
                )
                linksFound = true
            }
        }

        // If still no links, we've done our best.
    }
}
