import java.util.Properties
import java.net.URI
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}
val signingFile = file(System.getenv("CAMPANA_SIGNING_FILE") ?: (System.getProperty("user.home") + "/.campana/signing.properties"))
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use { load(it) } }
android {
    namespace = "org.campana.agent"
    compileSdk = 36
    defaultConfig {
        applicationId = "org.campana.agent"
        minSdk = 30
        targetSdk = 36
        versionCode = 17
        versionName = "0.4.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/voiceAssets"))
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/projectNotices"))
    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("generated/codexLibraries"))
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildTypes {
        debug {
            isMinifyEnabled=providers.gradleProperty("minifyVoiceQa").orNull=="true"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if(signingFile.exists()) signingConfig=signingConfigs.create("privateDebug") {
                storeFile=file(signingValues.getProperty("storeFile"))
                storePassword=signingValues.getProperty("storePassword")
                keyAlias=signingValues.getProperty("keyAlias")
                keyPassword=signingValues.getProperty("keyPassword")
            }
        }
        release {
            if(signingFile.exists()) signingConfig=signingConfigs.create("privateRelease") {
                storeFile=file(signingValues.getProperty("storeFile"))
                storePassword=signingValues.getProperty("storePassword")
                keyAlias=signingValues.getProperty("keyAlias")
                keyPassword=signingValues.getProperty("keyPassword")
            }
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("visualQa") {
            initWith(getByName("debug"))
            applicationIdSuffix=".visualqa"
            versionNameSuffix="-visual"
            isMinifyEnabled=false
            matchingFallbacks += "debug"
        }
    }
    if(providers.gradleProperty("visualQa").orNull=="true")testBuildType="visualQa"
    packaging {
        resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/INDEX.LIST")
        jniLibs { useLegacyPackaging = true; keepDebugSymbols += "**/libcodex.so" }
    }
}
val prepareCodexRuntime = tasks.register("prepareCodexRuntime") {
    val output = layout.buildDirectory.file("generated/codexLibraries/arm64-v8a/libcodex.so")
    val version = "0.166.0"
    inputs.property("codexVersion", version)
    outputs.file(output)
    doLast {
        fun digest(file: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    sha.update(buffer, 0, read)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
        val cache = File(gradle.gradleUserHomeDir, "caches/gci-codex/$version").apply { mkdirs() }
        val binary = System.getenv("GCI_CODEX_BINARY")?.let(::File)
            ?: File(cache, "codex-aarch64-unknown-linux-musl").also {
                if (!it.isFile) {
                    val archive = File(cache, "codex-arm64.tar.gz")
                    if (!archive.isFile) URI("https://github.com/openai/codex/releases/download/rust-v$version/codex-aarch64-unknown-linux-musl.tar.gz")
                        .toURL().openStream().use { input -> archive.outputStream().use { dest -> input.copyTo(dest) } }
                    check(digest(archive) == "94eb707605ec2646b2e05b7edf7c4e7b3ccf02b16237c34ae9beb8cfb7f810a9") {
                        "El archivo del motor Codex no coincide con la release fijada."
                    }
                    check(ProcessBuilder("tar", "-xzf", archive.absolutePath, "-C", cache.absolutePath).inheritIO().start().waitFor() == 0)
                }
            }
        check(digest(binary) == "f2e6c980c937e734644bed1b4a9a018d3e63a0ccbe71aafc85c86a8c6e4b462a") {
            "El ejecutable Codex no coincide con la release fijada."
        }
        output.get().asFile.let { it.parentFile.mkdirs(); binary.copyTo(it, overwrite = true) }
    }
}
val prepareVoiceModel=tasks.register("prepareVoiceModel") {
    val output=layout.buildDirectory.file("generated/voiceAssets/vosk-model-small-es-0.42.zip")
    outputs.file(output)
    doLast {
        val expected="09b239888f633ef2f0b4e09736e3d9936acfd810bc65d53fad45261762c6511f"
        val cached=System.getenv("CAMPANA_VOSK_MODEL_ZIP")?.let(::File) ?: File(gradle.gradleUserHomeDir,"caches/campana-models/vosk-model-small-es-0.42.zip")
        if(!cached.isFile){cached.parentFile.mkdirs();val part=File(cached.parentFile,"model.part")
            try {URI("https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip").toURL().openStream().use{input->part.outputStream().use{input.copyTo(it)}};check(part.renameTo(cached))}finally{part.delete()}
        }
        val digest=MessageDigest.getInstance("SHA-256")
        cached.inputStream().use{input->val b=ByteArray(32768);while(true){val n=input.read(b);if(n<0)break;digest.update(b,0,n)}}
        check(digest.digest().joinToString(""){"%02x".format(it)}==expected){"El modelo Vosk no supera la verificación SHA-256."}
        output.get().asFile.let{it.parentFile.mkdirs();cached.copyTo(it,overwrite=true)}
    }
}
val prepareProjectNotices = tasks.register<Sync>("prepareProjectNotices") {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    into(layout.buildDirectory.dir("generated/projectNotices/licenses"))
}
tasks.named("preBuild").configure { dependsOn(prepareVoiceModel, prepareCodexRuntime, prepareProjectNotices) }
dependencies {
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation(platform("androidx.compose:compose-bom:2025.11.01"))
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("com.nimbusds:nimbus-jose-jwt:10.10")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.11.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
kapt { arguments { arg("room.schemaLocation", file("schemas").absolutePath) } }
tasks.register("runtimeInventory") {
    doLast {
        val components=configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }.distinctBy { it.toString() }.sortedBy { it.toString() }
            .map { mapOf("group" to it.group,"name" to it.name,"version" to it.version) }
        val target=layout.buildDirectory.file("reports/dependencies/runtime.json").get().asFile
        target.parentFile.mkdirs()
        target.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(components)))
        println("Runtime inventory: "+target)
    }
}
