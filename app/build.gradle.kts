import java.util.Properties
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Preserve the author's artwork folders; only generated files use Android resource names.
abstract class PrepareArtwork : DefaultTask() {
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apparitions: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val planet: DirectoryProperty
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val icon: DirectoryProperty
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun prepare() {
        val target = outputDirectory.dir("drawable-nodpi").get().asFile
        target.mkdirs()
        val images = apparitions.get().asFile.listFiles().orEmpty().filter { it.extension == "png" } +
            listOf(planet.file("home_planet.png").get().asFile, icon.file("icon.png").get().asFile)
        val expected = images.map { if (it.name == "icon.png") "ic_starglean.png" else it.name }.toSet()
        require(expected.size == images.size && images.all { it.isFile }) { "Artwork is missing or duplicated." }
        target.listFiles().orEmpty().filter { it.name !in expected }.forEach { check(it.delete()) }
        images.forEach { source ->
            source.copyTo(target.resolve(if (source.name == "icon.png") "ic_starglean.png" else source.name), overwrite = true)
        }
    }
}
val prepareArtwork = tasks.register<PrepareArtwork>("prepareArtwork") {
    apparitions.set(layout.projectDirectory.dir("src/main/res/apparition-pic"))
    planet.set(layout.projectDirectory.dir("src/main/res/home-pic"))
    icon.set(layout.projectDirectory.dir("src/main/res/app-icon"))
    outputDirectory.set(layout.buildDirectory.dir("generated/artwork/res"))
}
androidComponents.onVariants { variant ->
    variant.sources.res?.addGeneratedSourceDirectory(prepareArtwork, PrepareArtwork::outputDirectory)
}

// Keep the local-device signing identity outside the repository and APK delivery folder.
val deviceSigningFile = rootProject.file(providers.environmentVariable("STARGLEAN_SIGNING_PROPERTIES")
    .orElse("${System.getProperty("user.home")}/Library/Application Support/Starglean/signing/local-device.properties").get())
val deviceSigning = Properties().apply {
    if (deviceSigningFile.isFile) deviceSigningFile.inputStream().use { load(it) }
}

val cloudConfig = Properties().apply {
    rootProject.file("supabase/client-config.properties").inputStream().use { load(it) }
}
val cloudUrl = cloudConfig.getProperty("url").also {
    require(it.matches(Regex("https://[a-z0-9]+\\.supabase\\.co")))
}
val cloudPublicKey = cloudConfig.getProperty("publishableKey").also {
    require(it.matches(Regex("sb_publishable_[A-Za-z0-9_-]+"))) { "Only a public client key is allowed." }
}

android {
    namespace = "com.starglean.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.starglean.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 24
        versionName = "0.5.5"
        // Both Android Studio and release builds run the real phone experience.
        buildConfigField("boolean", "LOCAL_DEVICE_EDITION", "true")
        buildConfigField("String", "SUPABASE_URL", "\"$cloudUrl\"")
        buildConfigField("String", "SUPABASE_PUBLISHABLE_KEY", "\"$cloudPublicKey\"")
        resValue("string", "app_name", "Starglean")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    signingConfigs {
        if (deviceSigningFile.isFile) create("localDevice") {
            storeFile = file(deviceSigning.getProperty("storeFile"))
            storePassword = deviceSigning.getProperty("storePassword")
            keyAlias = deviceSigning.getProperty("keyAlias")
            keyPassword = deviceSigning.getProperty("keyPassword")
        }
    }
    buildTypes {
        getByName("release") {
            // Preserve the installed phone app's identity and update compatibility.
            applicationIdSuffix = ".local"
            versionNameSuffix = "-en"
            isDebuggable = false
            isMinifyEnabled = false
            buildConfigField("boolean", "LOCAL_DEVICE_EDITION", "true")
            resValue("string", "app_name", "Starglean")
            if (deviceSigningFile.isFile) signingConfig = signingConfigs.getByName("localDevice")
        }
    }
    testBuildType = if (providers.gradleProperty("deviceTests").orNull == "true") "release" else "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // AGP's built-in Kotlin requires a Kotlin source root, not a Java root.
            kotlin.directories += "src/main/app"
            assets.directories.clear()
            assets.directories += "src/main/privacyPolicy"
            // Artwork inputs are packaged only by prepareArtwork, never as nested roots.
            res.directories.clear()
            res.directories += "src/main/res"
        }
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

val verifyLocalDeviceSigning by tasks.registering {
    doLast {
        check(deviceSigningFile.isFile) {
            "Missing local-device signing identity. Recover the original external signing files; do not generate a replacement for an update."
        }
        listOf("storeFile", "storePassword", "keyAlias", "keyPassword").forEach {
            check(!deviceSigning.getProperty(it).isNullOrBlank()) { "Incomplete local-device signing configuration." }
        }
        check(file(deviceSigning.getProperty("storeFile")).isFile) { "Local-device keystore is missing; do not regenerate an existing identity." }
    }
}
tasks.matching { it.name == "packageRelease" || it.name == "validateSigningRelease" }.configureEach {
    dependsOn(verifyLocalDeviceSigning)
}
// lintVital does not include NewApi. A distributable APK must also pass the
// full compatibility check against minSdk, not only run on a recent emulator.
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("lintRelease")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Fused location for nearby reading and publishing on phones with Google Play services.
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // Auth protocol tests must execute JSON parsing, not Android's throwing JVM stubs.
    testImplementation("org.json:json:20260814")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
