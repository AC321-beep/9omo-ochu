plugins {
    id("com.android.library")
    id("kotlin-android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    description = "Contains all the videos from Perverzija"
    authors = listOf("AC321-beep")   // only this changed
    status = 1
    tvTypes = listOf("NSFW")
    iconUrl = "https://www.google.com/s2/favicons?domain=tube.perverzija.com&sz=%size%"
    language = "en"
    version = 1
}
