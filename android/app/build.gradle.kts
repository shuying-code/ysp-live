plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ysp.live"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ysp.live"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        val singleAbi = providers.gradleProperty("yspAbi").orNull
        ndk {
            abiFilters += singleAbi?.let(::listOf)
                ?: listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
        }
        create("kiosk") {
            dimension = "distribution"
            versionNameSuffix = "-kiosk"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    sourceSets.getByName("main").assets.srcDir("../../assets")

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            // Keep dependency LICENSE/NOTICE files in the APK. They are part
            // of the third-party notice surface; only drop Maven metadata.
            excludes += setOf("/META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")
}

