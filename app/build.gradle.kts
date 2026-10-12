plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Set by CI (or locally) to sign release builds with the persistent key.
val releaseKeystore: String? = System.getenv("CLOSEITALL_KEYSTORE")

android {
    namespace = "io.github.yvanbetremieux.closeitall"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.yvanbetremieux.closeitall"
        minSdk = 26
        targetSdk = 35
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = System.getenv("GITHUB_REF_NAME")?.removePrefix("v") ?: "0.0.0-dev"
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("CLOSEITALL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("CLOSEITALL_KEY_ALIAS")
                keyPassword = System.getenv("CLOSEITALL_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
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
        aidl = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.datastore)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}
