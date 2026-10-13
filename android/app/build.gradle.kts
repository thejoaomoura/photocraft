plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ABIs of the Rust library (gradle.properties › photocraft.abis).
val rustAbis: List<String> =
    (findProperty("photocraft.abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }.filter { it.isNotEmpty() }

android {
    namespace = "ai.storyteller.photocraft"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.storyteller.photocraft"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.8.2-lite"
        ndk { abiFilters += rustAbis }
    }

    buildTypes {
        release {
            // R8 drops the unused icons (most of the dex); proguard-rules.pro keeps JNA and the
            // UniFFI bindings, which are reached by reflection.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    sourceSets["main"].java.srcDir("src/generated/kotlin")

    packaging { jniLibs.useLegacyPackaging = false }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.kotlinx.coroutines.android)
    // UniFFI's Kotlin bindings call the Rust library through JNA (the AAR carries its native part).
    implementation("${libs.jna.get()}@aar")
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// ---------------------------------------------------------------------------------------------
// The engine: crates/mobile-ffi built with cargo-ndk, plus its UniFFI Kotlin bindings.
// Needs Rust, the Android targets and cargo-ndk (crates/mobile-ffi/README.md). Set
// photocraft.skipRust=true when the .so and bindings are already in place (CI).
// ---------------------------------------------------------------------------------------------

val skipRust = (findProperty("photocraft.skipRust") as String?).toBoolean()
val workspaceDir: File = rootDir.parentFile
val generatedKotlin = layout.projectDirectory.dir("src/generated/kotlin")
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")

fun ndkHome(): String {
    System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() }?.let { return it }
    val ndkRoot = android.sdkDirectory.resolve("ndk")
    val newest = ndkRoot.listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: throw GradleException("No Android NDK found: set ANDROID_NDK_HOME or install one under $ndkRoot")
    return newest.absolutePath
}

val hostLibrary = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "photocraft_mobile.dll"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "libphotocraft_mobile.dylib"
    else -> "libphotocraft_mobile.so"
}

val cargoNdk by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds libphotocraft_mobile.so for ${rustAbis.joinToString()} (release)."
    workingDir = workspaceDir
    inputs.dir(workspaceDir.resolve("crates"))
    inputs.file(workspaceDir.resolve("Cargo.lock"))
    inputs.property("abis", rustAbis)
    outputs.dir(jniLibsDir)
    doFirst { environment("ANDROID_NDK_HOME", ndkHome()) }
    commandLine(
        listOf("cargo", "ndk") + rustAbis.flatMap { listOf("-t", it) } +
            listOf("-o", jniLibsDir.asFile.absolutePath, "build", "-p", "photocraft-mobile-ffi", "--release"),
    )
}

val cargoHostLib by tasks.registering(Exec::class) {
    group = "rust"
    description = "Builds the host copy of the library that the bindings are generated from."
    workingDir = workspaceDir
    commandLine("cargo", "build", "-p", "photocraft-mobile-ffi", "--lib")
}

val uniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generates the Kotlin bindings (ai.storyteller.photocraft.engine)."
    dependsOn(cargoHostLib)
    workingDir = workspaceDir
    inputs.dir(workspaceDir.resolve("crates/mobile-ffi/src"))
    outputs.dir(generatedKotlin)
    commandLine(
        "cargo", "run", "-p", "photocraft-mobile-ffi", "--features", "bindgen", "--bin", "uniffi-bindgen", "--",
        "generate", "--library", workspaceDir.resolve("target/debug/$hostLibrary").absolutePath,
        "--language", "kotlin", "--out-dir", generatedKotlin.asFile.absolutePath,
    )
}

if (!skipRust) {
    tasks.named("preBuild") { dependsOn(cargoNdk, uniffiBindings) }
}
