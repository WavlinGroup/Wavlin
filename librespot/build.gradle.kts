import com.google.protobuf.gradle.proto

plugins {
    id("com.android.library")
    alias(libs.plugins.protobuf.gradle)
}

android {
    namespace = "xyz.gianlu.librespot"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            proto {
                srcDir("src/main/proto")
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.2"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                create("java") {
                    option("lite")
                }
            }
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // Protobuf lite (Android-friendly)
    api("com.google.protobuf:protobuf-javalite:3.25.2")
    // JsonFormat for ProtobufToJson (works with lite runtime)
    api("com.google.protobuf:protobuf-java-util:3.25.2") {
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }

    // HTTP client used by Session
    api("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON parsing
    api("com.google.code.gson:gson:2.11.0")

    // Logging facade — route to Android logcat at runtime
    api("org.slf4j:slf4j-api:2.0.16")
    implementation("com.github.tony19:logback-android:3.0.0")

    // OGG Vorbis decoder
    implementation("org.jcraft:jorbis:0.0.17")

    // MP3 decoder
    implementation("com.badlogicgames.jlayer:jlayer:1.0.2-gdx")

    // Network utilities (used by Session for commons-net classes)
    implementation("commons-net:commons-net:3.11.1")

    // Annotations
    implementation("org.jetbrains:annotations:26.0.1")
}
