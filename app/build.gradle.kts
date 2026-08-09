plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.jetbrains.kotlin.serialization)
    id("base")
}

android {
    namespace = "io.github.serg987.sudokueinkhtr"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.serg987.sudokueinkhtr"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Suport per 16KB page size (Android 15+)
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            resValue(
                "string",
                "app_name",
                "Sudoku E-Ink HTR"
            )
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        // Debug sense R8 per facilitar el desenvolupament
        debug {
            isMinifyEnabled = false
            resValue(
                "string",
                "app_name",
                "Sudoku E-Ink HTR"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }

    packaging {
        jniLibs {
            pickFirsts += setOf("**/libc++_shared.so")
            useLegacyPackaging = true
        }
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/INDEX.LIST",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "kotlin/**",
                "**.kotlin_builtins"
            )
        }
    }
}

// Nom personalitzat per l'arxiu APK
base {
    archivesName.set(
        "sudokueinkhtr-${android.defaultConfig.versionName}"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.material.icons.extended)

    // Material3 sense icones innecessàries
    implementation(libs.androidx.compose.material3) {
        exclude(group = "androidx.compose.material", module = "material-icons-core")
    }

    // Navigation sense animacions (si no les uses)
    implementation(libs.androidx.navigation.compose) {
        exclude(group = "androidx.compose.animation")
    }

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.tensorflow.lite)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.26.0")
    implementation("com.google.mlkit:digital-ink-recognition:19.0.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    
    // Onyx SDK for stylus support
    implementation("com.onyx.android.sdk:onyxsdk-pen:1.4.11")

    // Software wet-ink path for non-Onyx (LCD) devices — see DeviceCaps.isOnyx. Ported
    // from NonogramEink; don't downgrade to the alphas (StockBrushes members became
    // functions, e.g. pressurePen()).
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.0")

    // Fix namespace conflict from older jetified libraries
    implementation("androidx.vectordrawable:vectordrawable:1.1.0")
    implementation("androidx.vectordrawable:vectordrawable-animated:1.1.0")

    // Mudita E-ink UI package
    implementation("com.mudita:MMD:1.0.2")
}
