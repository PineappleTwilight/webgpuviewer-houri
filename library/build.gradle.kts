plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose.compiler)
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "ca.mpreg"
version = "0.0.0"

val tag: String = runCatching {
    if (System.getenv("GITHUB_REF_TYPE") == "tag") {
        System.getenv("GITHUB_REF_NAME")?.takeIf { it.isNotBlank() } ?: "0.0.0"
    } else {
        val baseVersion = providers.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
        }.standardOutput.asText.map { it.trim().takeIf { s -> s.matches(Regex("[0-9a-f]{4,40}")) } ?: "unknown" }.getOrElse("unknown")
        "$baseVersion-SNAPSHOT"
    }
}.getOrElse { "0.0.0-unknown" }

android {
    namespace = "ca.mpreg.webgpuviewer"
    compileSdk = 37

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.txt")

        externalNativeBuild {
            cmake {
                cppFlags("-O3 -flto")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.window)
    implementation(libs.androidx.compose.foundation)

    // Exposed as api so consumers compile against the same classes the library links against —
    // the equivalent of the published POM's Maven "compile" scope. Mihon uses the same coordinate
    // from google(), with no custom repository; the app must not declare it a second time.
    api(libs.androidx.webgpu)
    // androidx.webgpu dependencies
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.kotlinx.coroutines.core)
}

afterEvaluate {
    mavenPublishing {
        coordinates("ca.mpreg", "webgpuviewer", tag)

        pom {
            name.set("webgpuviewer")
            description.set("webgpuviewer")
            inceptionYear.set("2026")
            url.set("https://github.com/mpreg-ca/webgpuviewer")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("wwww-wwww")
                    name.set("w")
                    url.set("https://github.com/wwww-wwww/")
                }
            }
            scm {
                url.set("https://github.com/mpreg-ca/webgpuviewer/")
                connection.set("scm:git:git://github.com/mpreg-ca/webgpuviewer.git")
                developerConnection.set("scm:git:ssh://git@github.com/mpreg-ca/webgpuviewer.git")
            }
        }

        publishToMavenCentral(automaticRelease = true)
        signAllPublications()
    }
}
