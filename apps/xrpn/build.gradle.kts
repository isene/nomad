import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------- Rust core integration ----------
// Sixth app on fe2o3-mobile-core (xrpn). Same cargo-ndk + uniffi wiring as the others
// (still pending extraction into a shared convention plugin).

val coreDir = rootProject.file("core")
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

val cargoBuildByAbi: Map<String, TaskProvider<Exec>> = androidAbis.mapValues { (abi, rustTriple) ->
    val safeName = "cargoBuild" + abi
        .split("-", "_")
        .joinToString("") { it.replaceFirstChar { c -> c.uppercase() } }
    tasks.register<Exec>(safeName) {
        group = "rust"
        description = "cargo-ndk build for $abi ($rustTriple)"
        workingDir = coreDir
        environment(cargoEnv())
        commandLine("cargo", "ndk", "-t", abi, "build", "--release")
        inputs.dir(coreDir.resolve("src"))
        inputs.file(coreDir.resolve("Cargo.toml"))
        inputs.file(rootProject.file("Cargo.toml"))
        outputs.file(rustTargetDir.resolve("$rustTriple/release/libfe2o3_mobile_core.so"))
    }
}
cargoBuildByAbi.values.forEach { provider ->
    cargoBuildAll.configure { dependsOn(provider) }
}

val jniLibsOutputDir = layout.projectDirectory.dir("src/main/jniLibs")

val copyJniLibs by tasks.registering(Copy::class) {
    group = "rust"
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
    dependsOn(cargoBuildByAbi["arm64-v8a"]!!)
    workingDir = coreDir
    environment(cargoEnv())
    val libPath = rustTargetDir.resolve("aarch64-linux-android/release/libfe2o3_mobile_core.so")
    val outDir = uniffiOutDir.get().asFile
    inputs.file(libPath)
    outputs.dir(outDir)
    doFirst { outDir.mkdirs() }
    commandLine(
        "cargo", "run", "--bin", "uniffi-bindgen", "--features", "cli", "--",
        "generate", "--library", libPath.absolutePath,
        "--language", "kotlin", "--out-dir", outDir.absolutePath,
    )
}

// ---------- Android module ----------

val keyProps = Properties().apply {
    val f = project.file("key.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.isene.xrpn"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.isene.xrpn"
        minSdk = 33
        targetSdk = 35
        versionCode = 7
        versionName = "0.3.4"
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

    // SAF directory enumeration for the synced programs folder.
    implementation("androidx.documentfile:documentfile:1.0.1")

    // JNA — generated UniFFI bindings depend on com.sun.jna.*.
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    debugImplementation(libs.androidx.ui.tooling)
}
