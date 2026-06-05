plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.rokkystudio.wifidrop"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.rokkystudio.wifidrop"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.material)
    implementation(libs.okhttp)
}
