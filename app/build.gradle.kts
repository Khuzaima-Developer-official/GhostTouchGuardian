plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace  = "com.ghosttouch.guardian"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.ghosttouch.guardian"
        minSdk        = 26
        targetSdk     = 35
        versionCode   = 27
        versionName   = "4.2.0"
    }

    signingConfigs {
        create("release") {
            storeFile     = file("../release.jks")
            storePassword = "ghostguard123"
            keyAlias      = "ghostguard"
            keyPassword   = "ghostguard123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            signingConfig      = signingConfigs.getByName("release")
            isMinifyEnabled    = true
            isShrinkResources  = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix   = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        viewBinding = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
        implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Testing dependencies
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.3.1")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

