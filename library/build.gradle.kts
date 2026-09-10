import com.android.build.api.artifact.SingleArtifact
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

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

val embed: Configuration = configurations.create("embed")
configurations.named("compileOnly") { extendsFrom(embed) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.androidx.compose.foundation)

    embed(libs.androidx.webgpu)
}

androidComponents {
    onVariants { variant ->
        val mergeTask =
            project.tasks.register<MergeEmbeddedAarsTask>(
                "merge${variant.name.replaceFirstChar { it.uppercase() }}EmbeddedAars"
            ) {
                embedAars.from(embed)
            }
        variant.artifacts
            .use(mergeTask)
            .wiredWithFiles(
                MergeEmbeddedAarsTask::inputAar,
                MergeEmbeddedAarsTask::outputAar
            )
            .toTransform(SingleArtifact.AAR)
    }
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

/**
 * Merges the classes.jar, native libs (jni/), and assets from [embedAars] into [inputAar],
 * producing [outputAar]. Used to embed androidx.webgpu directly into this library's own AAR
 * so downstream consumers don't need to resolve it (or its custom repo) separately.
 */
abstract class MergeEmbeddedAarsTask : DefaultTask() {
    @get:InputFile
    abstract val inputAar: RegularFileProperty

    @get:InputFiles
    abstract val embedAars: ConfigurableFileCollection

    @get:OutputFile
    abstract val outputAar: RegularFileProperty

    @TaskAction
    fun merge() {
        val outFile = outputAar.get().asFile
        outFile.parentFile.mkdirs()
        if (outFile.exists() && !outFile.delete()) throw IllegalStateException("Failed to delete $outFile")

        val ownAarFile = inputAar.get().asFile
        require(ownAarFile.exists()) { "inputAar not found: $ownAarFile" }
        val embedAarFiles = embedAars.files.filter { it.exists() }

        val mergedClassesJarBytes = mergeClassesJars(ownAarFile, embedAarFiles.toSet())

        ZipFile(ownAarFile).use { ownZip ->
            ZipOutputStream(outFile.outputStream().buffered()).use { zos ->
                val writtenPaths = mutableSetOf<String>()

                fun writeEntry(name: String, bytes: ByteArray) {
                    if (!writtenPaths.add(name)) {
                        if (name.startsWith("META-INF/") && name.endsWith(".SF")) return
                        if (name.startsWith("META-INF/") && name.endsWith(".RSA")) return
                        return
                    }
                    val entry = ZipEntry(name).apply { time = 0L }
                    zos.putNextEntry(entry)
                    zos.write(bytes)
                    zos.closeEntry()
                }

                for (entry in ownZip.entries()) {
                    if (entry.isDirectory) continue
                    if (entry.name == "classes.jar") {
                        writeEntry("classes.jar", mergedClassesJarBytes)
                    } else {
                        try {
                            writeEntry(entry.name, ownZip.getInputStream(entry).readBytes())
                        } catch (e: Exception) {
                            logger.warn("Skipping entry ${entry.name}: ${e.message}")
                        }
                    }
                }

                for (embedAarFile in embedAarFiles) {
                    ZipFile(embedAarFile).use { embedZip ->
                        for (entry in embedZip.entries()) {
                            if (entry.isDirectory) continue
                            if (entry.name == "classes.jar") continue
                            if (entry.name == "AndroidManifest.xml") continue
                            if (entry.name.startsWith("META-INF/")) continue
                            try {
                                writeEntry(entry.name, embedZip.getInputStream(entry).readBytes())
                            } catch (e: Exception) {
                                logger.warn("Skipping embed entry ${entry.name}: ${e.message}")
                            }
                        }
                    }
                }
            }
        }
        require(outFile.exists() && outFile.length() > 0) { "merge produced empty AAR" }
    }

    private fun mergeClassesJars(ownAarFile: File, embedAarFiles: Set<File>): ByteArray {
        val tmpJar = File.createTempFile("merged-classes", ".jar")
        try {
            ZipOutputStream(tmpJar.outputStream()).use { zos ->
                val seen = mutableSetOf<String>()

                fun addClassesJarFrom(aarFile: File) {
                    ZipFile(aarFile).use { aarZip ->
                        val classesEntry = aarZip.getEntry("classes.jar") ?: return
                        val tmpIn = File.createTempFile("classes-in", ".jar")
                        try {
                            aarZip.getInputStream(classesEntry).use { input ->
                                tmpIn.outputStream().use { input.copyTo(it) }
                            }
                            ZipFile(tmpIn).use { classesZip ->
                                for (entry in classesZip.entries()) {
                                    if (entry.isDirectory) continue
                                    if (!seen.add(entry.name)) continue
                                    zos.putNextEntry(ZipEntry(entry.name))
                                    classesZip.getInputStream(entry).use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } finally {
                            tmpIn.delete()
                        }
                    }
                }

                // Our own classes come first so they win any (unexpected) name conflicts.
                addClassesJarFrom(ownAarFile)
                for (embedAarFile in embedAarFiles) {
                    addClassesJarFrom(embedAarFile)
                }
            }
            return tmpJar.readBytes()
        } finally {
            tmpJar.delete()
        }
    }
}
