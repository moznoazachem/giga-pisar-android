import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    id("com.diffplug.spotless")
}

val appVersionName = "0.2.5"
val appVersionCode = 14

val localProperties =
    Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) {
            file.inputStream().use(::load)
        }
    }

fun signingProperty(name: String): String? =
    System.getenv(name)
        ?: providers.gradleProperty(name).orNull
        ?: localProperties.getProperty(name)

val releaseStoreFile =
    signingProperty("GIGA_PISAR_RELEASE_STORE_FILE")
val releaseStorePassword =
    signingProperty("GIGA_PISAR_RELEASE_STORE_PASSWORD")
val releaseKeyAlias =
    signingProperty("GIGA_PISAR_RELEASE_KEY_ALIAS")
val releaseKeyPassword =
    signingProperty("GIGA_PISAR_RELEASE_KEY_PASSWORD")

val releaseSigningConfigured =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).all { it != null }

val releaseSigningPartiallyConfigured =
    listOf(
        releaseStoreFile,
        releaseStorePassword,
        releaseKeyAlias,
        releaseKeyPassword,
    ).any { it != null } &&
        !releaseSigningConfigured

check(!releaseSigningPartiallyConfigured) {
    "Release signing is partially configured. " +
        "Set all GIGA_PISAR_RELEASE_* properties or none of them."
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint("1.5.0")
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.5.0")
    }
}

android {
    namespace = "ru.gigapisar"
    compileSdk = 36
    defaultConfig {
        applicationId = "ru.gigapisar"
        minSdk = 24
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        // Phones only: x86 builds serve emulators and would double the APK size.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = false
        buildConfig = false
        shaders = false
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.matching { it.name == "assembleRelease" }.configureEach {
    notCompatibleWithConfigurationCache(
        "Renames the generated APK after the release task completes",
    )
    doLast {
        val apkDirectory =
            layout.buildDirectory
                .dir("outputs/apk/release")
                .get()
                .asFile
        val defaultApk =
            apkDirectory.resolve("app-release.apk")
        val unsignedApk =
            apkDirectory.resolve("app-release-unsigned.apk")
        val namedApk =
            apkDirectory.resolve("gigapisar-v$appVersionName-release.apk")
        val sourceApk =
            when {
                defaultApk.isFile -> defaultApk
                unsignedApk.isFile -> unsignedApk
                else -> null
            }

        if (sourceApk != null && sourceApk != namedApk) {
            check(sourceApk.renameTo(namedApk)) {
                "Could not rename ${sourceApk.name} to ${namedApk.name}"
            }
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    // Core Android dependencies
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    // Arch Components
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended:1.7.8")

    // Tooling
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Navigation
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")

    testImplementation(libs.junit)
}
