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
        versionCode = 25
        versionName = "2.3.1"
        buildConfigField("String", "LHDC_GOVERNOR_MODE", "\"adaptive_governor\"")
        buildConfigField("boolean", "LHDC_DYN_OBSERVE", "false")
        // Toast-matrix 设备验证开关（release 恒为 false；debug 可由 -P 覆盖，运行时还有 DEBUG 二次门控）
        buildConfigField("boolean", "TOAST_TEST_BLOCK_BITRATE", "false")
        buildConfigField("boolean", "TOAST_TEST_BLOCK_FAST_SWITCH", "false")

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    sourceSets {
        getByName("main").assets.srcDir("../docs/design")
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
            buildConfigField("String", "LHDC_GOVERNOR_MODE", "\"bqr_fallback_ab\"")
            buildConfigField("boolean", "LHDC_DYN_OBSERVE", "true")
            buildConfigField("boolean", "TOAST_TEST_BLOCK_BITRATE",
                    (project.findProperty("toastBlockBitrate") as String? ?: "false").toBoolean().toString())
            buildConfigField("boolean", "TOAST_TEST_BLOCK_FAST_SWITCH",
                    (project.findProperty("toastBlockFastSwitch") as String? ?: "false").toBoolean().toString())
            externalNativeBuild {
                cmake {
                    arguments += "-DMELODY_FIXED_1000_AB=ON"
                    arguments += "-DMELODY_DYN_OBSERVE=ON"
                }
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }

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
    // Runtime dex scanner used to locate R8-renamed Melody host classes by stable strings.
    implementation("org.luckypray:dexkit:2.2.0")
    // androidx.annotation is used inline (@NonNull etc.); we don't compile against
    // androidx.preference / lifecycle since the host APK ships R8-minified copies and we
    // route all access through reflection (see PrefRef).
    compileOnly("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}
