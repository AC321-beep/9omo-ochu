override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val document = app.get(data).document
    val videoSource = document.selectFirst("video source")
    val videoUrl = videoSource?.attr("src")

    if (!videoUrl.isNullOrEmpty()) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "HQPorner",
                url = videoUrl,
                referer = mainUrl,
                quality = guessQuality(videoUrl),
                isM3u8 = videoUrl.contains(".m3u8"),
                headers = mapOf("Referer" to mainUrl)
            )
        )
        return true
    }

    val iframe = document.selectFirst("iframe[src*=/embed/]")
    if (iframe != null) {
        return loadLinks(iframe.attr("src"), isCasting, subtitleCallback, callback)
    }
    return false
}
