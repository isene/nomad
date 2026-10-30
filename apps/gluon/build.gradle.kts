import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// gluon: kastrup (mail), tock (calendar) and tasks (todo.hl) in one app,
// as the laptop's gluon has them, with the three home-screen widgets.
// Each part keeps its own Kotlin package (com.isene.mail, com.isene.tock,
// com.isene.tasks); com.isene.gluon holds the activity and the Home tab.

// ---------- Rust core integration ----------
// mail and tasks both use fe2o3-mobile-core, built once here.

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

// The tests run the mail code, and it calls into the Rust core. So the
// tests need the core built for this machine too, and JNA's laptop half
// to load it with (as in onepage).

val cargoBuildHost by tasks.registering(Exec::class) {
    group = "rust"
    description = "Build fe2o3-mobile-core for this machine, for the unit tests."
    workingDir = coreDir
    environment(cargoEnv())
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

android {
    namespace = "com.isene.gluon"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.isene.gluon"
        minSdk = 33
        targetSdk = 35
        versionCode = 11
        versionName = "0.7.1"
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
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/{NOTICE,LICENSE}*"
            // Jakarta Mail finds its IMAP provider by reading these, and
            // both android-mail and android-activation ship a copy.
            merges += "META-INF/javamail.*"
            merges += "META-INF/mailcap*"
        }
    }
    // tock's widget test needs the app's own layouts.
    testOptions { unitTests.isIncludeAndroidResources = true }
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
    implementation(libs.androidx.material.icons.extended)
    // SAF access to the Syncthing folder holding the shared read state.
    implementation(libs.androidx.documentfile)
    // Dragging tasks into order.
    implementation(libs.reorderable)
    // Home-screen widget.
    implementation(libs.glance.appwidget)
    implementation(libs.glance.material3)
    // Background fetch, and the widget push — both need to survive the
    // app process going away, which a plain coroutine does not.
    implementation(libs.androidx.work.runtime)
    implementation(libs.okhttp)
    // IMAP with XOAUTH2. The Android-flavoured build of Jakarta Mail;
    // the Rust core never touches a socket.
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")

    // JNA — generated UniFFI bindings depend on com.sun.jna.*.
    implementation("net.java.dev.jna:jna:5.15.0@aar")
    jnaForTest("net.java.dev.jna:jna:5.15.0@jar")
    testRuntimeOnly(files(jnaForTest))

    debugImplementation(libs.androidx.ui.tooling)
    testImplementation("junit:junit:4.13.2")
    // tock's widget on Android's own view code, run on the laptop.
    testImplementation("org.robolectric:robolectric:4.14.1")
}
