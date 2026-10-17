import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// --- Release signing: values come from keystore.properties (git-ignored) or environment variables. ---
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

// --- License verification key (PUBLIC key only) written by `license-tool keygen`. ---
val licensePublicKey: String = System.getenv("WSS_LICENSE_PUBLIC_KEY")?.trim()?.takeIf { it.isNotEmpty() }
    ?: rootProject.file("license-public-key.txt").takeIf { it.exists() }?.readText()?.trim()
    ?: "UNCONFIGURED"
val expectedSigner: String = (signingValue("signerSha256", "WSS_SIGNER_SHA256") ?: "").lowercase()

android {
    namespace = "com.smartmanager.whatsappshoot"
    compileSdk = 35

    defaultConfig {
        // Never change applicationId between releases: it is part of the installation identity (docs/RELEASE.md).
        applicationId = "com.smartmanager.whatsappshoot"
        minSdk = 26
        targetSdk = 35
        versionCode = 15
        versionName = "1.2.2"

        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licensePublicKey\"")
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "WSS_STORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("storePassword", "WSS_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "WSS_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "WSS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = null
            buildConfigField("String", "EXPECTED_SIGNER_SHA256", "\"\"")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("String", "EXPECTED_SIGNER_SHA256", "\"$expectedSigner\"")
            signingConfig = signingConfigs.findByName("release")
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
    kotlinOptions { jvmTarget = "17" }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    implementation(project(":license-core"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // coil/glide intentionally NOT used: the preview decodes the local file with BitmapFactory.

    testImplementation("junit:junit:4.13.2")
}

// Release builds must never ship without a license verification key or a signing configuration.
tasks.matching { it.name.startsWith("assembleRelease") || it.name.startsWith("bundleRelease") }.configureEach {
    doFirst {
        check(licensePublicKey != "UNCONFIGURED") {
            "license-public-key.txt is missing. Run: license-tool keygen --out-dir secrets/ and copy license-public.b64 to license-public-key.txt (docs/RELEASE.md)."
        }
        check(android.signingConfigs.findByName("release") != null) {
            "No release signing config. Provide keystore.properties or WSS_STORE_* environment variables (docs/RELEASE.md)."
        }
        check(expectedSigner.isNotEmpty()) { "Set signerSha256 (SHA-256 of the release certificate) so the build can detect repackaging." }
    }
}
