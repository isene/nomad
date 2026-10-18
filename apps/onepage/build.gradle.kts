import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// OnePage: minimal home launcher. Traditional Views for the home surface
// (AppWidgetHost children ARE Views; Compose buys nothing there and adds
// recomposition cost to an always-alive process). Compose only for the
// one-shot first-run permission wizard. Rust core holds the layout-file
// format (core/src/onepage.rs); Kotlin owns all I/O.

// ---------- Rust core integration ----------
// Identical wiring to the other core-consuming apps.

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
    // Panic messages name source files. Keep this machine's folders out
    // of them: the library goes into an APK that others download. Where
    // two prefixes fit a path, rustc takes the last, so the longer one
    // comes last.
    out["RUSTFLAGS"] = listOfNotNull(
        current["RUSTFLAGS"],
        "--remap-path-prefix=${System.getProperty("user.home")}/=",
        "--remap-path-prefix=${rootProject.projectDir.parent}/=",
    ).joinToString(" ")
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
        "cargo", "run", "--bin", "uniffi-bindgen", "--features", "cli", "--",
        "generate",
        "--library", libPath.absolutePath,
        "--language", "kotlin",
        "--out-dir", outDir.absolutePath,
    )
}

// ---------- The core on the laptop, for the tests ----------
// PanelOnAndroidTest runs the panel's own code, and that calls into the
// Rust core. So the tests need the core built for this machine too, and
// JNA's laptop half to load it with.

val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build fe2o3-mobile-core for this machine, for the unit tests."
    workingDir = coreDir
    environment(cargoEnv())
    // The features of the bindings step above, so nothing is compiled twice.
    commandLine("cargo", "build", "--lib", "--features", "cli")
    inputs.dir(coreDir.resolve("src"))
    inputs.file(coreDir.resolve("Cargo.toml"))
    inputs.file(rootProject.file("Cargo.toml"))
    outputs.file(rustTargetDir.resolve("debug/" + System.mapLibraryName("fe2o3_mobile_core")))
}

tasks.withType<Test>().configureEach {
    dependsOn(cargoBuildHost)
    systemProperty("jna.library.path", rustTargetDir.resolve("debug").absolutePath)
}

// The JNA the app ships is Android's (@aar). Asked for by the same name,
// Gradle would hand the tests that one again, so the jar has its own list.
val jnaForTest: Configuration by configurations.creating

// ---------- Android module ----------

val keyProps = Properties().apply {
    val f = project.file("key.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.isene.onepage"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.isene.onepage"
        minSdk = 30
        targetSdk = 35
        versionCode = 21
        versionName = "0.9.0"
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
    testOptions {
        // Robolectric builds real views, and those read the app's theme.
        unitTests.isIncludeAndroidResources = true
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
    // Compose: first-run wizard only. The home surface is traditional Views.
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)

    // JNA — generated UniFFI bindings depend on com.sun.jna.*.
    implementation("net.java.dev.jna:jna:5.15.0@aar")

    // The swipe rule and a folder's sign are plain Kotlin, tested on the laptop.
    testImplementation("junit:junit:4.13.2")
    // The panel on Android's own view code, also on the laptop
    // (PanelOnAndroidTest). Robolectric fetches that code the first time.
    testImplementation("org.robolectric:robolectric:4.14.1")
    jnaForTest("net.java.dev.jna:jna:5.15.0@jar")
    testRuntimeOnly(files(jnaForTest))
}
