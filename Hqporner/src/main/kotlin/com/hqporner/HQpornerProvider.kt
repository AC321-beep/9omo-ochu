override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document

    // Try direct <video> source
    val videoSource = document.selectFirst("video source")
    val videoUrl = videoSource?.attr("src")

    if (!videoUrl.isNullOrEmpty()) {
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                this.referer = mainUrl
                this.quality = guessQuality(videoUrl)
                // Do NOT set isM3u8 or headers – they are immutable in this version.
            }
        )
        return true
    }

    // Fallback: iframe embed
    val iframe = document.selectFirst("iframe[src*=/embed/]")
    if (iframe != null) {
        return loadLinks(iframe.attr("src"), isCasting, subtitleCallback, callback)
    }

    return false
}
