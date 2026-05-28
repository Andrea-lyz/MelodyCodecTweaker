plugins {
    id("com.android.application")
}

android {
    namespace = "xyz.melodylsp.codec"
    compileSdk = 36

    defaultConfig {
        applicationId = "xyz.melodylsp.codec"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            // R8 disabled for now — we want a clean repro path. Re-enable once we have
            // verified hooks work end-to-end and proguard rules are tuned.
            isMinifyEnabled = false
            isShrinkResources = false
        }
        getByName("debug") {
            isMinifyEnabled = false
        }
    }

    packaging {
        // libxposed entry list and module.prop must survive resource merging.
        resources {
            merges += "META-INF/xposed/*"
            excludes += "META-INF/*.kotlin_module"
        }
    }
}

dependencies {
    // Modern Xposed API. Provided by LSPosed at runtime.
    compileOnly("io.github.libxposed:api:101.0.1")
    // Provided by the host APK at runtime.
    compileOnly("androidx.preference:preference:1.2.1")
    compileOnly("androidx.appcompat:appcompat:1.7.0")
    compileOnly("androidx.lifecycle:lifecycle-common-java8:2.6.2")
    compileOnly("androidx.lifecycle:lifecycle-runtime:2.6.2")
    compileOnly("androidx.annotation:annotation:1.9.1")
}
