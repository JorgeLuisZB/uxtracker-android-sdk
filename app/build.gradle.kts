plugins {
    id("com.android.library") version "8.9.3"
    id("org.jetbrains.kotlin.android") version "2.1.0"
}

android {
    namespace = "com.wzagroup.uxtracker_android_sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        testOptions.targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
            // This can be empty.
        }
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
}

dependencies {

    // AndroidX Libraries
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")

    // Kotlin Libraries
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.1.20")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:5.0.0")

    // Gson for JSON parsing
    implementation("com.google.code.gson:gson:2.13.2")

    // AndroidX Testing Libraries
    implementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")

    // Kotlin Test Library
    implementation("org.jetbrains.kotlin:kotlin-test:2.1.0")
}