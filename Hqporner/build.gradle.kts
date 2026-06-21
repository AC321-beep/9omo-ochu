plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    description = "HQPorner.com NSFW extension"
    authors = listOf("AC321-beep")
    status = 1
    tvTypes = listOf("NSFW")
    language = "en"
}

android {
    compileSdk = 33
    namespace = "com.hqporner"
    defaultConfig {
        minSdk = 21
        targetSdk = 33
    }
}

dependencies {
    implementation("com.lagradost:cloudstream3:pre-release")
    implementation("org.jsoup:jsoup:1.15.3")
}
