android {
    namespace = "com.hqporner"
}

cloudstream {
    description = "Hqporner - NSFW Videos"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("NSFW")
    language = "en"
    version = 2
    iconUrl = "https://hqporner.com/favicon.ico"
}

dependencies {
    // The root build already provides common dependencies (jsoup, NiceHttp, etc.)
    // No extra dependencies are needed for Hqporner – the extractor works without them.
    // If you need coroutines, uncomment the line below, but it's not required.
    // implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
