override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val response = app.get(data, interceptor = cfInterceptor)
    val document = response.document

    val iframeUrl = document.select("div#player-embed iframe").attr("src")
    if (iframeUrl.isBlank()) {
        return false
    }

    // 1. First try the built‑in extractor (handles many hosts)
    if (loadExtractor(iframeUrl, subtitleCallback, callback)) {
        return true
    }

    // 2. If that fails, try the custom Xtremestream extractor
    Xtremestream().getUrl(iframeUrl, data, subtitleCallback, callback)
    // The custom extractor doesn't return a boolean, but we assume it may have added links.
    // We return true because we attempted to extract links (even if none found, the user will see an error).
    return true
}
