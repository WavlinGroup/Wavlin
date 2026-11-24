@file:Suppress("UnstableApiUsage")

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        google()
        mavenCentral()
        maven { setUrl("https://jitpack.io") }
        maven { setUrl("https://maven.aliyun.com/repository/public") }

        // Flutter add-to-app: the AAR repo is produced by `flutter build aar` in
        // flutter/wavlin_visuals. When it is absent the app builds without Flutter.
        val flutterVisualsRepo = rootDir.resolve("flutter/wavlin_visuals/build/host/outputs/repo")
        if (flutterVisualsRepo.isDirectory) {
            maven { setUrl(flutterVisualsRepo.toURI()) }
            maven {
                setUrl("https://storage.googleapis.com/download.flutter.io")
                content { includeGroup("io.flutter") }
            }
        }
    }
}

// F-Droid doesn't support foojay-resolver plugin
// plugins {
//     id("org.gradle.toolchains.foojay-resolver-convention") version("1.0.0")
// }

rootProject.name = "Wavlin"
include(":app")
include(":librespot")
include(":innertube")
include(":kugou")
include(":lrclib")
include(":deezer")

include(":lastfm")
include(":betterlyrics")
include(":shazamkit")
include(":paxsenix")

// Use a local copy of NewPipe Extractor by uncommenting the lines below.
// We assume, that Wavlin and NewPipe Extractor have the same parent directory.
// If this is not the case, please change the path in includeBuild().
//
// For this to work you also need to change the implementation in innertube/build.gradle.kts
// to one which does not specify a version.
// From:
//      implementation(libs.newpipe.extractor)
// To:
//      implementation("com.github.teamnewpipe:NewPipeExtractor")
//includeBuild("../NewPipeExtractor") {
//    dependencySubstitution {
//        substitute(module("com.github.teamnewpipe:NewPipeExtractor")).using(project(":extractor"))
//    }
//}



