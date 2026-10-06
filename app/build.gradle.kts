plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.melody.melodylink"
    // android-37 is not published in the stable channel yet; 36 builds the
    // same API surface used by this module.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.melody.melodylink"
        minSdk = 35
        targetSdk = 36
        versionCode = 227
        versionName = "2.0.27"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // LSPosed modules are sideloaded; sign release with the debug key
            // so CI artifacts install directly without a private keystore.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    compileOnly(libs.libxposed.api)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.org.json)
    implementation(libs.androidx.core.ktx)
}
