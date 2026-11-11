plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.chenfish666.podspanel"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.chenfish666.podspanel"
        minSdk = 28
        targetSdk = 35
        versionCode = 10
        versionName = "1.3.2"
    }

    // The release key is never stored in the repository. A release build is signed only when the
    // key is handed in through the environment (see README); without it, `assembleRelease`
    // produces an unsigned APK and `assembleDebug` is the build to use for development.
    val releaseKeystore = System.getenv("PODSPANEL_KEYSTORE")?.takeIf { it.isNotBlank() }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                val password = System.getenv("PODSPANEL_KEYSTORE_PASSWORD")
                storeFile = file(releaseKeystore)
                storePassword = password
                keyAlias = "podspanel"
                keyPassword = password
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(21)
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    // Lets the app call the platform method that opens the AirPods control channel (see AapLink).
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    // Builds the notification extras that Xiaomi HyperOS turns into its island.
    implementation("io.github.d4viddf:hyperisland_kit:0.4.5")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
