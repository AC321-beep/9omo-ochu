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
        val document = app.get(url).document
        val script = document.selectFirst("script:containsData(do_pl())")?.toString() ?: return
        val jw = script.substringAfter("replaceAll")
            .substringAfter(",")
            .substringBefore(")")
            .trim()
            .removeSurrounding("\"")

        val parts = jw.split("+").map { it.trim().removeSurrounding("\"") }
        if (parts.size < 3) return
        val (one, _, three) = parts

        val first = Regex("""$one\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1)
            ?.removePrefix("//")
            ?.removeSuffix("/")
            ?: return

        val third = Regex("""$three\s*=\s*"(.*?)";""").find(script)?.groupValues?.get(1) ?: return

        val baseUrl = "https://$first/pubs/$third"

        val regex = Regex("""title=\\?"(\d+p|4K)""")
        val matches = regex.findAll(script)
        val qualities = mutableListOf<String>()
        for (match in matches) {
            val quality = match.groupValues[1]
            if (quality == "4K") {
                qualities.add("2160")
            } else {
                qualities.add(quality.dropLast(1))
            }
        }

        if (qualities.isEmpty()) {
            qualities.add("1080")
        }

        for (quality in qualities) {
            val videoUrl = "$baseUrl/$quality.mp4"
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = videoUrl,
                    quality = getQualityFromName(quality)
                ) {
                    this.referer = mainUrl
                }
            )
        }
    }
}
