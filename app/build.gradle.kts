import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val nativeAbis: List<String> = providers.gradleProperty("native.abis")
    .map { it.split(',').map(String::trim).filter(String::isNotEmpty) }
    .getOrElse(listOf("arm64-v8a", "armeabi-v7a", "x86_64"))
val nativeProfile: String = providers.gradleProperty("native.profile").getOrElse("release")
// Optional GIF integration: keep developer-specific configuration out of Git.
// In the PROJECT ROOT local.properties, add: giphySdkKey=YOUR_GIPHY_ANDROID_SDK_KEY
// This file is git-ignored. A user-level Gradle property remains supported for
// existing local setups, but local.properties takes precedence when both exist.
// IMPORTANT: Android SDK client keys are embedded in the resulting APK and can be
// extracted by recipients; git-ignoring this file does NOT protect distributed keys.
val localGiphyKey: String = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}.getProperty("giphySdkKey")?.trim().orEmpty()
val giphySdkKey: String = localGiphyKey.ifBlank {
    providers.gradleProperty("giphySdkKey").getOrElse("").trim()
}
val appMinSdk = 26
/** `-Pscreenshots`: also runs the Robolectric/Roborazzi screenshot tests (images in build/outputs/roborazzi). */
val screenshots: Boolean = providers.gradleProperty("screenshots").isPresent

android {
    namespace = "com.taehagen.spotifygood"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.taehagen.spotifygood"
        minSdk = appMinSdk
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("String", "GIPHY_SDK_KEY", "\"${giphySdkKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        ndk { abiFilters += nativeAbis }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so `assembleRelease` produces an installable APK out of
            // the box. Replace with your own signing config for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        // Keep .so files uncompressed and page aligned (16 KB page size support).
        jniLibs.useLegacyPackaging = false
    }

    testOptions {
        unitTests {
            // Screenshot tests render with Robolectric (merged resources) and only run with
            // -Pscreenshots; the plain unit test run skips them.
            isIncludeAndroidResources = screenshots
            all { test ->
                // Robolectric fetches its Android runtime (android-all-instrumented, ~200 MB) itself
                // while the tests run, not through Gradle, into ~/.m2/repository on first use. The
                // plain run has Robolectric tests too (SpotifyPlayerPlaceholderTest), so every run
                // takes it from Maven Central's Google-hosted mirror: Maven Central rate-limits
                // shared CI addresses.
                test.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
                if (screenshots) {
                    test.systemProperty("roborazzi.test.record", "true")
                } else {
                    test.exclude("**/*ScreenshotTest*")
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.media3.common.util.UnstableApi",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

/**
 * Builds the Rust engine (native/spotcore, librespot) with cargo-ndk for every configured ABI
 * and exposes the resulting `jniLibs` directory to AGP as generated sources.
 *
 * Requires `cargo` and `cargo-ndk` (`cargo install cargo-ndk`) plus the Rust Android targets
 * (`rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android`).
 */
abstract class CargoNdkTask @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Internal
    abstract val workspaceDir: DirectoryProperty

    @get:Input
    abstract val abis: ListProperty<String>

    @get:Input
    abstract val profile: Property<String>

    @get:Input
    abstract val minSdk: Property<Int>

    @get:Internal
    abstract val ndkDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val args = buildList {
            add("ndk")
            abis.get().forEach { add("-t"); add(it) }
            add("-P"); add(minSdk.get().toString())
            add("-o"); add(out.absolutePath)
            add("build")
            add("-p"); add("spotcore")
            add("--locked")
            if (profile.get() == "release") add("--release")
        }
        exec.exec {
            workingDir = workspaceDir.get().asFile
            executable = "cargo"
            args(args)
            environment("ANDROID_NDK_HOME", ndkDirectory.get().asFile.absolutePath)
        }
    }
}

val nativeDir = rootProject.layout.projectDirectory.dir("native")
val cargoBuild = tasks.register<CargoNdkTask>("cargoBuild") {
    group = "build"
    description = "Builds the librespot native engine for ${nativeAbis.joinToString()}."
    workspaceDir.set(nativeDir)
    sources.from(
        nativeDir.file("Cargo.toml"),
        nativeDir.file("Cargo.lock"),
        nativeDir.dir("spotcore").asFileTree.matching { exclude("target/**") },
        nativeDir.dir("vendor").asFileTree,
    )
    abis.set(nativeAbis)
    profile.set(nativeProfile)
    minSdk.set(appMinSdk)
    ndkDirectory.set(androidComponents.sdkComponents.ndkDirectory)
    outputDir.set(layout.buildDirectory.dir("generated/jniLibs/cargo"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkTask::outputDir)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.material3.navigation.suite)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.mediarouter)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.palette.ktx)
    // Official Android GIF picker and animated media renderer, Maven Central.
    implementation("com.giphy.sdk:ui:2.5.1")
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // Coil 3 does not decode animated GIF frames without this artifact.
    // Keep the GIF decoder version aligned to the existing Coil version catalog.
    implementation("io.coil-kt.coil3:coil-gif:${libs.versions.coil.get()}")
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
}
