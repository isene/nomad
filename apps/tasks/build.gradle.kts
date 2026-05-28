import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------- Rust core integration ----------
//
// Reused logic lives in /core (fe2o3-mobile-core). On every assemble we:
//   1. cargo-ndk builds the .so for each requested ABI.
//   2. The .so files are copied into src/main/jniLibs/<abi>/.
//   3. uniffi-bindgen reads the metadata symbols from the aarch64 .so and
//      emits the Kotlin bindings under build/generated/source/uniffi/.
//   4. That generated dir is added to the main source set.
//
// Keep PATH cleaned (`/usr/bin:$PATH`) when invoking cargo: ~/bin/cc on
// this user's setup shadows the real C compiler that rusqlite + friends
// call out to. The wrapper assembles a fresh PATH per cargo task; see
// `cargoEnv` below.

val coreDir = rootProject.file("core")
// Cargo workspace lives at nomad/Cargo.toml, so the per-target build
// artifacts land in nomad/target/<triple>/ regardless of where in the
// workspace cargo was invoked from.
val rustTargetDir = rootProject.file("target")

val androidAbis = mapOf(
    "arm64-v8a" to "aarch64-linux-android",
    "armeabi-v7a" to "armv7-linux-androideabi",
    "x86_64" to "x86_64-linux-android",
    "x86" to "i686-linux-android",
)

val ndkHome: String? = System.getenv("ANDROID_NDK_HOME")
    ?: System.getenv("ANDROID_NDK_ROOT")
    ?: project.findProperty("android.ndkPath") as String?

fun cargoEnv(): Map<String, String> {
    val current = System.getenv()
    val cleanPath = "/usr/bin:" + (current["PATH"] ?: "")
    val out = mutableMapOf<String, String>()
    out.putAll(current)
    out["PATH"] = cleanPath
    val ndk = ndkHome
    if (ndk != null) {
        out["ANDROID_NDK_HOME"] = ndk
        out["ANDROID_NDK_ROOT"] = ndk
    }
    return out
}

val cargoBuildAll by tasks.registering {
    group = "rust"
    description = "Build fe2o3-mobile-core for every Android ABI."
}

// Hold task providers so downstream tasks can reference them directly
// rather than by name (which Gradle's name munging mangles for ABIs
// like "arm64-v8a" → "cargoBuildarm64_V8a", easy to typo).
val cargoBuildByAbi: Map<String, TaskProvider<Exec>> = androidAbis.mapValues { (abi, rustTriple) ->
    val safeName = "cargoBuild" + abi
        .split("-", "_")
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    tasks.register<Exec>(safeName) {
        group = "rust"
        description = "cargo-ndk build for $abi ($rustTriple)"
        workingDir = coreDir
        environment(cargoEnv())
        commandLine(
            "cargo", "ndk",
            "-t", abi,
            "build", "--release",
        )
        outputs.file(rustTargetDir.resolve("$rustTriple/release/libfe2o3_mobile_core.so"))
    }
}
cargoBuildByAbi.values.forEach { provider ->
    cargoBuildAll.configure { dependsOn(provider) }
}

val jniLibsOutputDir = layout.projectDirectory.dir("src/main/jniLibs")

val copyJniLibs by tasks.registering(Copy::class) {
    group = "rust"
    description = "Copy the per-ABI .so files into src/main/jniLibs/."
    dependsOn(cargoBuildAll)
    androidAbis.forEach { (abi, rustTriple) ->
        from(rustTargetDir.resolve("$rustTriple/release")) {
            include("libfe2o3_mobile_core.so")
            into(abi)
        }
    }
    into(jniLibsOutputDir)
}

val uniffiOutDir = layout.buildDirectory.dir("generated/source/uniffi")

val generateUniffiBindings by tasks.registering(Exec::class) {
    group = "rust"
    description = "Generate Kotlin bindings from the aarch64 .so."
    dependsOn(cargoBuildByAbi["arm64-v8a"]!!)
    workingDir = coreDir
    environment(cargoEnv())
    val libPath = rustTargetDir.resolve("aarch64-linux-android/release/libfe2o3_mobile_core.so")
    val outDir = uniffiOutDir.get().asFile
    inputs.file(libPath)
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    commandLine(
        "cargo", "run",
        "--bin", "uniffi-bindgen",
        "--features", "cli",
        "--",
        "generate",
        "--library", libPath.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )
}

// ---------- Android module ----------

// Load signing credentials from key.properties if present. Same shape as
// the v0.3.0 app — drop key.properties next to this build file (it is
// gitignored). The release keystore is at ~/.android/tasks-release.jks
// with alias `tasks`; signing with that key makes the new APK an
// in-place upgrade over v0.3.0.
val keyProps = Properties().apply {
    val f = project.file("key.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.isene.tasks"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.isene.tasks"
        minSdk = 33
        targetSdk = 35
        versionCode = 6
        versionName = "0.4.2"
        ndk { abiFilters += androidAbis.keys }
    }

    signingConfigs {
        if (keyProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8 code + resource shrinking. This is how material-icons-extended
            // is meant to be slimmed: only the ~12 icons actually referenced
            // survive, the rest of the icon set + dead Compose/Glance code is
            // stripped. Keep rules for the reflection users (JNA + UniFFI) are
            // in proguard-rules.pro.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDir(uniffiOutDir)
            jniLibs.srcDir(jniLibsOutputDir)
        }
    }
}

// Wire the native build into the Android task graph. preBuild is the
// canonical hook; everything that needs to exist before Java/Kotlin
// compilation must finish before it runs.
tasks.named("preBuild").configure {
    dependsOn(copyJniLibs, generateUniffiBindings)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.documentfile)
    implementation(libs.reorderable)

    // Glance home-screen widget.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)

    // JNA — generated UniFFI bindings depend on com.sun.jna.*. The
    // @aar variant ships the per-ABI native dispatch libs so they
    // bundle into the APK alongside our own libfe2o3_mobile_core.so.
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    debugImplementation(libs.androidx.ui.tooling)
}
