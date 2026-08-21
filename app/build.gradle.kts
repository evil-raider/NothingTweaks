plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.souleven.nothingos"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.souleven.nothingos"
        minSdk = 28
        targetSdk = 37
        versionCode = 21
        versionName = "2.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            optimization {
                enable = false
            }
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    compileOnly(files("libs/api-82.jar"))

    implementation(libs.androidx.activity)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}