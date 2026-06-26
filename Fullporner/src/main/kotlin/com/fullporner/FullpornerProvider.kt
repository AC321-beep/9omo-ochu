override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    val iframeUrl = fixUrlNull(document.selectFirst("div.video-block div.single-video-left div.single-video iframe")?.attr("src")) ?: return false
    val iframeDocument = app.get(iframeUrl).document
    val videoID = Regex("""var id = \"(.+?)\"""", RegexOption.IGNORE_CASE)
        .find(iframeDocument.html())?.groupValues?.getOrNull(1)

    if (videoID.isNullOrBlank()) {
        logError(Exception("FullPorner: Could not extract porntrex video ID from $iframeUrl"))
        return false
    }

    val embedUrl = "https://www.porntrex.com/embed/$videoID"
    val embedDoc = app.get(embedUrl).document

    // Extract video URLs
    val videoUrls = mutableListOf<String>()

    // Pattern 1: sources array
    val sourcesRegex = Regex("""sources\s*:\s*\[.*?"file"\s*:\s*"([^"]+)""", RegexOption.IGNORE_CASE)
    videoUrls.addAll(sourcesRegex.findAll(embedDoc.html()).map { it.groupValues[1] }.filter { it.isNotBlank() })

    // Pattern 2: direct variables
    if (videoUrls.isEmpty()) {
        val varRegex = Regex("""(?:video_url|video_alt_url2|video_alt_url3)\s*:\s*'([^']+)'""", RegexOption.IGNORE_CASE)
        videoUrls.addAll(varRegex.findAll(embedDoc.html()).map { it.groupValues[1] }.filter { it.isNotBlank() })
    }

    // Pattern 3: standalone file
    if (videoUrls.isEmpty()) {
        val fileRegex = Regex("""file\s*:\s*'([^']+)'""", RegexOption.IGNORE_CASE)
        videoUrls.addAll(fileRegex.findAll(embedDoc.html()).map { it.groupValues[1] }.filter { it.isNotBlank() })
    }

    if (videoUrls.isEmpty()) {
        logError(Exception("FullPorner: No video URLs found in $embedUrl"))
        return false
    }

    // Quality detection
    val qualityRegex = Regex("""_(\d{3,4})p""")

    videoUrls.forEach { videoUrl ->
        try {
            val quality = qualityRegex.find(videoUrl)?.groupValues?.get(1)?.toIntOrNull() ?: Qualities.Unknown.value
            callback.invoke(
                newExtractorLink(name, name, videoUrl).apply {
                    this.quality = quality
                }
            )
        } catch (e: Exception) {
            logError(e)
        }
    }

    return true
}
