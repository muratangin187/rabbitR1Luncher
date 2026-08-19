import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Madlen login uses the *public* Firebase Web API key — a client-side app
// identifier (not a server secret), but we keep it out of tracked source. It
// is injected at build time from the local environment so it never lands in
// git history. Populate `madlen.firebaseApiKey` in local.properties or export
// MADLEN_FIREBASE_API_KEY.
val madlenFirebaseApiKey: String = run {
    val local = Properties().apply {
        val f = file("../local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    (local.getProperty("madlen.firebaseApiKey")
        ?: System.getenv("MADLEN_FIREBASE_API_KEY")
        ?: (findProperty("r1.firebaseApiKey") as String?))
        ?.trim().orEmpty()
}

android {
    namespace = "com.r1.launcher"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.r1.launcher"
        minSdk = 23
        targetSdk = 33
        // The copy at /system/app/R1Launcher/ is the install floor: a
        // `/data/app` install is only accepted when its versionCode is at
        // least as high. CarrotOS ships the launcher at versionCode 1000, so
        // a plain release-numbered build is rejected with
        // INSTALL_FAILED_VERSION_DOWNGRADE.
        //
        // Upstream handles this by editing the number and hiding the edit with
        // `git update-index --skip-worktree`, which silently drops the change
        // from every diff. We override it from the command line instead, so the
        // tracked value always stays the real release number:
        //
        //   ./gradlew assembleDebug -Pr1.versionCode=1001
        //
        // `./r1.sh` reads the floor off the connected device and passes this
        // automatically, so day-to-day you never think about it.
        versionCode = (findProperty("r1.versionCode") as String?)?.toInt() ?: 15
        versionName = "1.1.10"

        // CarrotOS identity baked into the APK as a fallback for the About tray
        // when the OS image hasn't wired ro.carrot.* into device.mk. Empty by
        // default so carrotOsInfo() falls through to ro.lineage.* / Build fields.
        buildConfigField("String", "CARROT_VERSION", "\"\"")
        buildConfigField("String", "CARROT_BUILD_ID", "\"\"")
        buildConfigField("String", "MADLEN_FIREBASE_API_KEY", "\"${madlenFirebaseApiKey}\"")

        // R1 is single-ABI (arm64-v8a). Restricting filter avoids accidentally
        // pulling in armeabi-v7a / x86_64 / x86 from any future native deps.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        // Platform key from ~/lineage/build/make/target/product/security/platform.{pk8,x509.pem},
        // converted to PKCS12 via openssl. Signing with the platform key gives the APK every
        // signature-protected permission (e.g. ACCESS_MESSAGES_ON_ICC for SmsManager.getAllMessagesFromIcc),
        // and matches the signature of the prebuilt R1Launcher in the system image — so
        // `adb install -r` over the system version doesn't fail with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
        // Both build types use the same key so debug and release stay swap-compatible with
        // /system/app/R1Launcher/.
        create("release") {
            storeFile = file("../platform.keystore")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        named("debug") {
            storeFile = file("../platform.keystore")
            storePassword = "android"
            keyAlias = "platform"
            keyPassword = "android"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "DebugProbesKt.bin",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL1.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL3.bin.properties",
                "org/bouncycastle/pqc/crypto/picnic/lowmcL5.bin.properties",
                "org/bouncycastle/x509/CertPathReviewerMessages*.properties",
                // jakarta.mail (com.sun.mail:android-mail) ships these resource
                // files that conflict with android-activation when both are
                // packaged together. Last-wins semantics are fine for us.
                "META-INF/mailcap.default",
                "META-INF/mimetypes.default",
                "META-INF/NOTICE.md",
                "META-INF/LICENSE.md",
                "META-INF/NOTICE",
                "META-INF/LICENSE",
            )
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Triggers baseline-profile install on first launch via a ContentProvider.
    // AGP merges the Compose AAR-bundled profiles + any app/src/main/baselineProfiles/
    // entries into assets/dexopt/baseline.prof at release-build time, rewritten
    // through R8's mapping so obfuscated names resolve.
    implementation("androidx.profileinstaller:profileinstaller:1.3.1")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.animation:animation")
    // Material3 pulled in only for the type tokens; we style everything custom.
    implementation("androidx.compose.material3:material3")

    // Markdown rendering for AI chat bubbles. Every version we tried (0.16,
    // 0.20, 0.24) calls DrawScope.drawLine-NGM6Ib0$default with a value-class
    // signature that doesn't exist in compose.ui 1.7.x (the version our BOM
    // ships). It crashes the first time an assistant reply contains a
    // blockquote (`> ...`). Workaround: strip `>` line markers before passing
    // text to Markdown — see OpenClawChatPanel. Real
    // fix would be bumping Compose BOM to 2025.x.
    implementation("com.mikepenz:multiplatform-markdown-renderer-m3:0.24.0")

    // OpenClaw panel: WebSocket JSON-RPC + JSON + encrypted prefs + QR scanner.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // Embedded HTTP + WebSocket server for the companion web panel.
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // Meetings app: SMTP send via JavaMail. The android-mail fork is the only
    // one that links cleanly on AOSP — the upstream jakarta.mail-api JARs
    // reference desktop-only packages (java.beans, JAXB) and won't dex.
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
}
