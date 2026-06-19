import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.7.3")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.0")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()
fun Project.android(configuration: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        namespace = "com.example"
        defaultConfig {
            minSdk = 21
            compileSdkVersion(35)
            targetSdk = 35
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
        tasks.withType<KotlinJvmCompile> {
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_1_8)
                freeCompilerArgs.addAll(
                    "-Xno-call-assertions",
                    "-Xno-param-assertions",
                    "-Xno-receiver-assertions"
                )
            }
        }
    }

    dependencies {
        val cloudstream by configurations
        val implementation by configurations

        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.11")
        implementation("org.jsoup:jsoup:1.18.3")
        implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.13.1")
    }
}

task<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}

// ========== Auto-generate repo.json after build ==========
tasks.register("generateRepoJson") {
    doLast {
        // Determine the builds directory – same as used by the workflow
        val buildsDir = System.getenv("GITHUB_WORKSPACE")?.let { File(it, "builds") }
            ?: File(rootDir, "builds")   // fallback for local builds
        buildsDir.mkdirs()

        val repoJson = File(buildsDir, "repo.json")
        val repoName = "9omo-ochu"
        val repoUrl = System.getenv("GITHUB_REPOSITORY") ?: "AC321-beep/9omo-ochu"

        repoJson.writeText(
            """
            {
                "name": "$repoName",
                "description": "CloudStream plugins by AC321-beep",
                "manifestVersion": 1,
                "pluginLists": [
                    "https://raw.githubusercontent.com/$repoUrl/refs/heads/builds/plugins.json"
                ]
            }
            """.trimIndent()
        )
        println("Generated $repoJson")
    }
}

// Make it run right after makePluginsJson
tasks.named("makePluginsJson") {
    finalizedBy("generateRepoJson")
}
