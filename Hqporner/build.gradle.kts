cloudstream {
    description = "HQPorner.com NSFW extension"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("NSFW")
    language = "en"
}

android {
    namespace = "com.hqporner"
}

dependencies {
    // Explicitly add the API so it's on the compile classpath
    implementation("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.15.3")   // jsoup already provided by root, but safe to repeat
}
